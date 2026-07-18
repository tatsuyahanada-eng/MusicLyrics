<?php
/* ============================================================
   Case By Case — api.php
   サーバーDB(MySQL等)を「唯一の正データ」として複数人で共有編集する API。
   項目(ノード)ごとに 追加/更新/削除/並び替え し、丸ごと上書きしないため
   同時編集でも他人の入力を消しません。画像アップロードにも対応。
   ============================================================ */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

$cfgFile = __DIR__ . '/config.php';
if (is_file($cfgFile)) require_once $cfgFile;
require_once __DIR__ . '/db.php';

if (!defined('API_TOKEN'))        define('API_TOKEN', '');
if (!defined('UPLOAD_DIR'))       define('UPLOAD_DIR', __DIR__ . '/uploads');
if (!defined('UPLOAD_URL'))       define('UPLOAD_URL', 'uploads');
if (!defined('UPLOAD_MAX_BYTES')) define('UPLOAD_MAX_BYTES', 5 * 1024 * 1024);

/* ---------- helpers ---------- */
function fail($msg, $code = 400) {
  http_response_code($code);
  echo json_encode(array('ok' => false, 'error' => $msg), JSON_UNESCAPED_UNICODE);
  exit;
}
function ok($extra = array()) {
  echo json_encode(array_merge(array('ok' => true), $extra), JSON_UNESCAPED_UNICODE);
  exit;
}
function body_json() {
  $raw = file_get_contents('php://input');
  if ($raw === '' || $raw === false) return array();
  $d = json_decode($raw, true);
  return is_array($d) ? $d : array();
}
function gen_id() { return 'n' . bin2hex(random_bytes(9)); }
function now_ms() { return (int) round(microtime(true) * 1000); }

function require_token() {
  if (API_TOKEN === '') return;
  $sent = isset($_SERVER['HTTP_X_API_TOKEN']) ? $_SERVER['HTTP_X_API_TOKEN'] : '';
  if (!hash_equals(API_TOKEN, $sent)) fail('編集にはトークンが必要です（合言葉が違います）', 401);
}

$action = isset($_GET['action']) ? $_GET['action'] : '';

/* config は DB 接続前でも返せるように（未設定でも画面が動くように） */
if ($action === 'config') {
  $connected = false; $err = null;
  try { cbc_pdo(); $connected = true; }
  catch (Throwable $e) { $err = $e->getMessage(); }
  ok(array(
    'dbConnected' => $connected,
    'hasToken'    => (API_TOKEN !== ''),
    'uploads'     => true,
    'driver'      => defined('DB_DRIVER') ? DB_DRIVER : 'mysql',
    'error'       => $connected ? null : $err,
  ));
}

/* 以降は DB 必須 */
try { $pdo = cbc_pdo(); }
catch (Throwable $e) { fail('DBに接続できません: ' . $e->getMessage(), 500); }

switch ($action) {

  case 'tree': {
    $rows = $pdo->query('SELECT id, parent_id, sort_order, title, body FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
    ok(array('nodes' => $rows));
  }

  case 'node_create': {
    require_token();
    $d = body_json();
    $title = trim(isset($d['title']) ? $d['title'] : '');
    if ($title === '') fail('title は必須です');
    $parent = isset($d['parent_id']) && $d['parent_id'] !== '' ? $d['parent_id'] : null;
    if ($parent !== null) {
      $chk = $pdo->prepare('SELECT 1 FROM nodes WHERE id = ?');
      $chk->execute(array($parent));
      if (!$chk->fetchColumn()) fail('親項目が存在しません');
    }
    $ord = $pdo->prepare('SELECT COALESCE(MAX(sort_order), -1) + 1 FROM nodes WHERE ' . ($parent === null ? 'parent_id IS NULL' : 'parent_id = ?'));
    $ord->execute($parent === null ? array() : array($parent));
    $sort = (int) $ord->fetchColumn();
    $id = gen_id();
    $ts = now_ms();
    $ins = $pdo->prepare('INSERT INTO nodes (id, parent_id, sort_order, title, body, updated_at, created_at) VALUES (?,?,?,?,?,?,?)');
    $ins->execute(array($id, $parent, $sort, $title, isset($d['body']) ? $d['body'] : '', $ts, $ts));
    ok(array('node' => array('id' => $id, 'parent_id' => $parent, 'sort_order' => $sort, 'title' => $title, 'body' => isset($d['body']) ? $d['body'] : '')));
  }

  case 'node_update': {
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $title = trim(isset($d['title']) ? $d['title'] : '');
    if ($title === '') fail('title は必須です');
    $up = $pdo->prepare('UPDATE nodes SET title = ?, body = ?, updated_at = ? WHERE id = ?');
    $up->execute(array($title, isset($d['body']) ? $d['body'] : '', now_ms(), $d['id']));
    ok();
  }

  case 'node_delete': {
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    // 子孫をまとめて削除
    $all = $pdo->query('SELECT id, parent_id FROM nodes')->fetchAll();
    $childrenOf = array();
    foreach ($all as $r) { $childrenOf[$r['parent_id']][] = $r['id']; }
    $toDelete = array();
    $stack = array($d['id']);
    while ($stack) {
      $cur = array_pop($stack);
      $toDelete[] = $cur;
      if (!empty($childrenOf[$cur])) foreach ($childrenOf[$cur] as $c) $stack[] = $c;
    }
    $pdo->beginTransaction();
    $del = $pdo->prepare('DELETE FROM nodes WHERE id = ?');
    foreach ($toDelete as $id) $del->execute(array($id));
    $pdo->commit();
    ok(array('deleted' => count($toDelete)));
  }

  case 'node_move': {
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $dir = isset($d['dir']) && (int)$d['dir'] < 0 ? -1 : 1;
    $cur = $pdo->prepare('SELECT id, parent_id, sort_order FROM nodes WHERE id = ?');
    $cur->execute(array($d['id']));
    $node = $cur->fetch();
    if (!$node) fail('項目が存在しません');
    $parent = $node['parent_id'];
    $whereParent = $parent === null ? 'parent_id IS NULL' : 'parent_id = ?';
    $args = $parent === null ? array() : array($parent);
    // 隣接する兄弟を取得
    if ($dir < 0) {
      $q = $pdo->prepare("SELECT id, sort_order FROM nodes WHERE $whereParent AND sort_order < ? ORDER BY sort_order DESC LIMIT 1");
      $q->execute(array_merge($args, array($node['sort_order'])));
    } else {
      $q = $pdo->prepare("SELECT id, sort_order FROM nodes WHERE $whereParent AND sort_order > ? ORDER BY sort_order ASC LIMIT 1");
      $q->execute(array_merge($args, array($node['sort_order'])));
    }
    $sib = $q->fetch();
    if (!$sib) ok(); // 端なので何もしない
    $pdo->beginTransaction();
    $swap = $pdo->prepare('UPDATE nodes SET sort_order = ?, updated_at = ? WHERE id = ?');
    $swap->execute(array($sib['sort_order'], now_ms(), $node['id']));
    $swap->execute(array($node['sort_order'], now_ms(), $sib['id']));
    $pdo->commit();
    ok();
  }

  case 'node_reparent': {
    // ドラッグ&ドロップ用：親の変更＋並び順の指定。
    // {id, parent_id(''=ルート), before_id(''=末尾に追加)}
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $id = $d['id'];
    $parent = isset($d['parent_id']) && $d['parent_id'] !== '' ? $d['parent_id'] : null;
    $beforeId = isset($d['before_id']) && $d['before_id'] !== '' ? $d['before_id'] : null;

    $q = $pdo->prepare('SELECT id FROM nodes WHERE id = ?');
    $q->execute(array($id));
    if (!$q->fetchColumn()) fail('項目が存在しません');

    if ($parent !== null) {
      if ($parent === $id) fail('自分自身の中には移動できません');
      $q = $pdo->prepare('SELECT id FROM nodes WHERE id = ?');
      $q->execute(array($parent));
      if (!$q->fetchColumn()) fail('移動先が存在しません');
      // 循環防止：移動先が自分の子孫ではないこと
      $all = $pdo->query('SELECT id, parent_id FROM nodes')->fetchAll();
      $childrenOf = array();
      foreach ($all as $r) { $childrenOf[$r['parent_id']][] = $r['id']; }
      $desc = array(); $stack = array($id);
      while ($stack) {
        $c = array_pop($stack);
        if (!empty($childrenOf[$c])) foreach ($childrenOf[$c] as $cc) { $desc[$cc] = true; $stack[] = $cc; }
      }
      if (isset($desc[$parent])) fail('子孫の中には移動できません');
    }

    // 移動先の兄弟（自分を除く）を順序どおり取得
    $whereParent = $parent === null ? 'parent_id IS NULL' : 'parent_id = ?';
    $args = $parent === null ? array() : array($parent);
    $sq = $pdo->prepare("SELECT id FROM nodes WHERE $whereParent ORDER BY sort_order, created_at");
    $sq->execute($args);
    $sibs = array();
    foreach ($sq->fetchAll() as $r) { if ($r['id'] !== $id) $sibs[] = $r['id']; }

    // before_id の直前に挿入（無ければ末尾）
    $newOrder = array(); $inserted = false;
    foreach ($sibs as $s) {
      if ($beforeId !== null && $s === $beforeId) { $newOrder[] = $id; $inserted = true; }
      $newOrder[] = $s;
    }
    if (!$inserted) $newOrder[] = $id;

    $pdo->beginTransaction();
    $up = $pdo->prepare('UPDATE nodes SET parent_id = ?, updated_at = ? WHERE id = ?');
    $up->execute(array($parent, now_ms(), $id));
    $ord = $pdo->prepare('UPDATE nodes SET sort_order = ?, updated_at = ? WHERE id = ?');
    foreach ($newOrder as $i => $nid) { $ord->execute(array($i, now_ms(), $nid)); }
    $pdo->commit();
    ok();
  }

  case 'replace_all': {
    // CSV取込などで全置換（トランザクション）。nodes:[{id,parent_id,sort_order,title,body}]
    require_token();
    $d = body_json();
    if (!isset($d['nodes']) || !is_array($d['nodes'])) fail('nodes がありません');
    $ts = now_ms();
    $pdo->beginTransaction();
    $pdo->exec('DELETE FROM nodes');
    $ins = $pdo->prepare('INSERT INTO nodes (id, parent_id, sort_order, title, body, updated_at, created_at) VALUES (?,?,?,?,?,?,?)');
    foreach ($d['nodes'] as $n) {
      $ins->execute(array(
        !empty($n['id']) ? $n['id'] : gen_id(),
        isset($n['parent_id']) && $n['parent_id'] !== '' ? $n['parent_id'] : null,
        isset($n['sort_order']) ? (int)$n['sort_order'] : 0,
        isset($n['title']) ? $n['title'] : '（無題）',
        isset($n['body']) ? $n['body'] : '',
        $ts, $ts,
      ));
    }
    $pdo->commit();
    ok(array('count' => count($d['nodes'])));
  }

  case 'upload': {
    require_token();
    if (empty($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) fail('ファイルがありません');
    $f = $_FILES['file'];
    if ($f['size'] > UPLOAD_MAX_BYTES) fail('ファイルが大きすぎます（上限 ' . round(UPLOAD_MAX_BYTES / 1048576, 1) . 'MB）');
    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = $finfo->file($f['tmp_name']);
    $ext = array(
      'image/jpeg' => 'jpg', 'image/png' => 'png', 'image/gif' => 'gif', 'image/webp' => 'webp',
    );
    if (!isset($ext[$mime])) fail('対応していない画像形式です（JPEG/PNG/GIF/WebP）');
    if (!is_dir(UPLOAD_DIR)) @mkdir(UPLOAD_DIR, 0775, true);
    $name = date('Ymd') . '_' . bin2hex(random_bytes(8)) . '.' . $ext[$mime];
    $dest = rtrim(UPLOAD_DIR, '/') . '/' . $name;
    if (!move_uploaded_file($f['tmp_name'], $dest)) fail('保存に失敗しました（uploadsの書き込み権限をご確認ください）', 500);
    @chmod($dest, 0644);
    ok(array('url' => rtrim(UPLOAD_URL, '/') . '/' . $name));
  }

  default:
    fail('不明なアクションです: ' . $action, 404);
}

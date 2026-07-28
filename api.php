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
if (!defined('ADMIN_PW'))         define('ADMIN_PW', 'Welsys1234');
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
function author_of($d) {
  $a = isset($d['author']) ? trim((string)$d['author']) : '';
  if ($a === '') return null;
  return mb_substr($a, 0, 120);
}
// node_create/update に渡された lock 指定から lock_hash を決める
//   戻り値: array($set(bool), $hash(null|string)) — $set が false のとき更新しない
function lock_hash_from($d, $current = null) {
  if (!array_key_exists('lock_enabled', $d)) return array(false, null); // 指定なし=変更しない
  $enabled = !!$d['lock_enabled'];
  if (!$enabled) return array(true, null); // ロック解除
  $pw = isset($d['lock']) ? (string)$d['lock'] : '';
  if ($pw === '') return array(true, $current); // 有効だがパスワード未入力=既存を維持
  return array(true, password_hash($pw, PASSWORD_DEFAULT));
}
// ロックされた項目は body と配下を隠して返す（locked=1 を付与、lock_hash は返さない）
function prune_locked($rows) {
  $childrenOf = array(); $byId = array();
  foreach ($rows as $r) { $byId[$r['id']] = $r; $childrenOf[$r['parent_id']][] = $r['id']; }
  $out = array();
  $emit = function ($id) use (&$emit, &$out, &$byId, &$childrenOf) {
    $r = $byId[$id];
    $locked = !empty($r['lock_hash']);
    $out[] = array(
      'id' => $r['id'], 'parent_id' => $r['parent_id'], 'sort_order' => $r['sort_order'], 'title' => $r['title'],
      'body' => $locked ? '' : $r['body'], 'created_by' => $r['created_by'], 'updated_by' => $r['updated_by'],
      'updated_at' => $r['updated_at'], 'created_at' => $r['created_at'], 'locked' => $locked ? 1 : 0,
    );
    if ($locked) return; // 配下は隠す
    if (!empty($childrenOf[$id])) foreach ($childrenOf[$id] as $c) $emit($c);
  };
  if (!empty($childrenOf[null])) foreach ($childrenOf[null] as $c) $emit($c);
  return $out;
}

function require_token() {
  if (API_TOKEN === '') return;
  $sent = isset($_SERVER['HTTP_X_API_TOKEN']) ? $_SERVER['HTTP_X_API_TOKEN'] : '';
  if (!hash_equals(API_TOKEN, $sent)) fail('編集にはトークンが必要です（合言葉が違います）', 401);
}
// 在庫の「修正」系（数量修正・履歴の編集/削除・商品編集/削除）は管理者パスワード必須
function require_admin($d) {
  $pw = isset($d['admin']) ? (string)$d['admin'] : '';
  if (ADMIN_PW === '' || $pw === '' || !hash_equals(ADMIN_PW, $pw)) fail('管理者パスワードが必要です', 403);
}
// 履歴から在庫数と各行の残数(balance)を再計算（履歴を修正・削除したとき用）
function cbc_inv_recalc($pdo, $itemId) {
  $q = $pdo->prepare('SELECT id, action, qty FROM inv_logs WHERE item_id = ? ORDER BY created_at ASC, id ASC');
  $q->execute(array($itemId));
  $bal = 0;
  $up = $pdo->prepare('UPDATE inv_logs SET balance = ? WHERE id = ?');
  foreach ($q->fetchAll() as $r) {
    $n = (int)$r['qty']; $a = $r['action'];
    if ($a === 'out' || $a === 'use') $bal -= $n;
    else $bal += $n; // return / init は＋qty、adjust は qty が符号付き差分
    $up->execute(array($bal, $r['id']));
  }
  $pdo->prepare('UPDATE inv_items SET qty = ?, updated_at = ? WHERE id = ?')->execute(array($bal, now_ms(), $itemId));
  return $bal;
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
    $rows = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
    ok(array('nodes' => prune_locked($rows)));
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
    $who = author_of($d);
    list($lset, $lhash) = lock_hash_from($d, null);
    $finalLock = $lset ? $lhash : null;
    $ins = $pdo->prepare('INSERT INTO nodes (id, parent_id, sort_order, title, body, created_by, updated_by, lock_hash, updated_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)');
    $ins->execute(array($id, $parent, $sort, $title, isset($d['body']) ? $d['body'] : '', $who, $who, $finalLock, $ts, $ts));
    ok(array('node' => array('id' => $id, 'parent_id' => $parent, 'sort_order' => $sort, 'title' => $title, 'body' => isset($d['body']) ? $d['body'] : '')));
  }

  case 'node_update': {
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $title = trim(isset($d['title']) ? $d['title'] : '');
    if ($title === '') fail('title は必須です');
    $who = author_of($d);
    $curq = $pdo->prepare('SELECT lock_hash FROM nodes WHERE id = ?');
    $curq->execute(array($d['id']));
    $curHash = $curq->fetchColumn();
    if ($curHash === false) $curHash = null;
    list($lset, $lhash) = lock_hash_from($d, $curHash);
    // 更新日時：明示指定があればその値、なければ現在時刻（履歴の並び順を手動調整するため）
    $ua = (isset($d['updated_at']) && (int)$d['updated_at'] > 0) ? (int)$d['updated_at'] : now_ms();
    if ($lset) {
      $up = $pdo->prepare('UPDATE nodes SET title = ?, body = ?, updated_by = COALESCE(?, updated_by), lock_hash = ?, updated_at = ? WHERE id = ?');
      $up->execute(array($title, isset($d['body']) ? $d['body'] : '', $who, $lhash, $ua, $d['id']));
    } else {
      $up = $pdo->prepare('UPDATE nodes SET title = ?, body = ?, updated_by = COALESCE(?, updated_by), updated_at = ? WHERE id = ?');
      $up->execute(array($title, isset($d['body']) ? $d['body'] : '', $who, $ua, $d['id']));
    }
    ok();
  }

  case 'unlock': {
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $pw = isset($d['password']) ? (string)$d['password'] : '';
    $q = $pdo->prepare('SELECT lock_hash FROM nodes WHERE id = ?');
    $q->execute(array($d['id']));
    $row = $q->fetch();
    if (!$row) fail('項目が存在しません', 404);
    $hash = $row['lock_hash'];
    $okpw = empty($hash) || ($pw !== '' && (password_verify($pw, $hash) || hash_equals(ADMIN_PW, $pw)));
    if (!$okpw) fail('パスワードが違います', 403);
    // 対象のサブツリーを返す（対象自身は解錠、ネストされたロックは維持）
    $all = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
    $childrenOf = array(); $byId = array();
    foreach ($all as $r) { $byId[$r['id']] = $r; $childrenOf[$r['parent_id']][] = $r['id']; }
    $out = array();
    $emit = function ($id, $isRoot) use (&$emit, &$out, &$byId, &$childrenOf) {
      $r = $byId[$id];
      $locked = !empty($r['lock_hash']) && !$isRoot;
      $out[] = array(
        'id' => $r['id'], 'parent_id' => $r['parent_id'], 'sort_order' => $r['sort_order'], 'title' => $r['title'],
        'body' => $locked ? '' : $r['body'], 'created_by' => $r['created_by'], 'updated_by' => $r['updated_by'],
        'updated_at' => $r['updated_at'], 'created_at' => $r['created_at'], 'locked' => $locked ? 1 : 0,
      );
      if ($locked) return;
      if (!empty($childrenOf[$id])) foreach ($childrenOf[$id] as $c) $emit($c, false);
    };
    $emit($d['id'], true);
    ok(array('nodes' => $out));
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
    $ins = $pdo->prepare('INSERT INTO nodes (id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at) VALUES (?,?,?,?,?,?,?,?,?)');
    foreach ($d['nodes'] as $n) {
      $cb = isset($n['created_by']) && $n['created_by'] !== '' ? $n['created_by'] : (isset($n['updated_by']) && $n['updated_by'] !== '' ? $n['updated_by'] : null);
      $ub = isset($n['updated_by']) && $n['updated_by'] !== '' ? $n['updated_by'] : $cb;
      $ins->execute(array(
        !empty($n['id']) ? $n['id'] : gen_id(),
        isset($n['parent_id']) && $n['parent_id'] !== '' ? $n['parent_id'] : null,
        isset($n['sort_order']) ? (int)$n['sort_order'] : 0,
        isset($n['title']) ? $n['title'] : '（無題）',
        isset($n['body']) ? $n['body'] : '',
        $cb, $ub, $ts, $ts,
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

    $imageExt = array('jpg', 'jpeg', 'png', 'gif', 'webp');
    $fileExt  = array('pdf', 'txt', 'csv', 'zip', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx');
    $ext = strtolower(pathinfo($f['name'], PATHINFO_EXTENSION));
    $isImage = in_array($ext, $imageExt, true);
    if (!$isImage && !in_array($ext, $fileExt, true)) {
      fail('対応していないファイル形式です（画像／PDF／Office／txt／csv／zip）');
    }
    // 画像は中身のMIMEも確認（なりすまし防止）
    if ($isImage) {
      $finfo = new finfo(FILEINFO_MIME_TYPE);
      $mime = $finfo->file($f['tmp_name']);
      $okMime = array('image/jpeg', 'image/png', 'image/gif', 'image/webp');
      if (!in_array($mime, $okMime, true)) fail('画像ファイルが不正です');
    }
    if (!is_dir(UPLOAD_DIR)) @mkdir(UPLOAD_DIR, 0775, true);
    $stored = date('Ymd') . '_' . bin2hex(random_bytes(8)) . '.' . $ext;
    $dest = rtrim(UPLOAD_DIR, '/') . '/' . $stored;
    if (!move_uploaded_file($f['tmp_name'], $dest)) fail('保存に失敗しました（uploadsの書き込み権限をご確認ください）', 500);
    @chmod($dest, 0644);
    // 表示用の元ファイル名（危険文字を除去）
    $label = preg_replace('/[\\/\\\\:*?"<>|]+/', '_', $f['name']);
    ok(array(
      'url'      => rtrim(UPLOAD_URL, '/') . '/' . $stored,
      'name'     => $label,
      'is_image' => $isImage,
    ));
  }

  /* ============================================================
     在庫管理（inventory）
     ============================================================ */
  case 'inv_list': {
    $rows = $pdo->query('SELECT id, name, model, qty, note, sort_order, created_at, updated_at FROM inv_items ORDER BY sort_order, created_at')->fetchAll();
    $items = array();
    foreach ($rows as $r) {
      $items[] = array(
        'id' => $r['id'], 'name' => $r['name'], 'model' => $r['model'],
        'qty' => (int)$r['qty'], 'note' => $r['note'],
        'created_at' => (int)$r['created_at'], 'updated_at' => (int)$r['updated_at'],
      );
    }
    ok(array('items' => $items));
  }

  case 'inv_history': {
    $d = body_json();
    $itemId = isset($d['item_id']) && $d['item_id'] !== '' ? $d['item_id']
            : (isset($_GET['item_id']) ? $_GET['item_id'] : '');
    if ($itemId !== '') {
      $q = $pdo->prepare('SELECT id, item_id, action, qty, balance, person, note, created_at FROM inv_logs WHERE item_id = ? ORDER BY created_at DESC, id DESC');
      $q->execute(array($itemId));
    } else {
      $q = $pdo->query('SELECT id, item_id, action, qty, balance, person, note, created_at FROM inv_logs ORDER BY created_at DESC, id DESC');
    }
    $logs = array();
    foreach ($q->fetchAll() as $r) {
      $logs[] = array(
        'id' => $r['id'], 'item_id' => $r['item_id'], 'action' => $r['action'],
        'qty' => (int)$r['qty'], 'balance' => (int)$r['balance'],
        'person' => $r['person'], 'note' => $r['note'], 'created_at' => (int)$r['created_at'],
      );
    }
    ok(array('logs' => $logs));
  }

  case 'inv_item_create': {
    require_token();
    $d = body_json();
    $name = trim(isset($d['name']) ? $d['name'] : '');
    if ($name === '') fail('商品名は必須です');
    $model = trim(isset($d['model']) ? $d['model'] : '');
    $note  = trim(isset($d['note']) ? $d['note'] : '');
    $qty   = isset($d['qty']) ? (int)$d['qty'] : 0;
    if ($qty < 0) $qty = 0;
    $person = author_of($d);
    $id = gen_id();
    $ts = now_ms();
    $ord = (int)$pdo->query('SELECT COALESCE(MAX(sort_order), -1) + 1 FROM inv_items')->fetchColumn();
    $ins = $pdo->prepare('INSERT INTO inv_items (id, name, model, qty, note, sort_order, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)');
    $ins->execute(array($id, $name, $model, $qty, $note, $ord, $ts, $ts));
    if ($qty > 0) {
      $lg = $pdo->prepare('INSERT INTO inv_logs (id, item_id, action, qty, balance, person, note, created_at) VALUES (?,?,?,?,?,?,?,?)');
      $lg->execute(array(gen_id(), $id, 'init', $qty, $qty, $person, '初期登録', $ts));
    }
    ok(array('id' => $id));
  }

  case 'inv_item_update': {
    require_token();
    $d = body_json();
    require_admin($d);
    if (empty($d['id'])) fail('id は必須です');
    $name = trim(isset($d['name']) ? $d['name'] : '');
    if ($name === '') fail('商品名は必須です');
    $model = trim(isset($d['model']) ? $d['model'] : '');
    $note  = trim(isset($d['note']) ? $d['note'] : '');
    $up = $pdo->prepare('UPDATE inv_items SET name = ?, model = ?, note = ?, updated_at = ? WHERE id = ?');
    $up->execute(array($name, $model, $note, now_ms(), $d['id']));
    ok();
  }

  case 'inv_item_delete': {
    require_token();
    $d = body_json();
    require_admin($d);
    if (empty($d['id'])) fail('id は必須です');
    $pdo->beginTransaction();
    $pdo->prepare('DELETE FROM inv_logs WHERE item_id = ?')->execute(array($d['id']));
    $pdo->prepare('DELETE FROM inv_items WHERE id = ?')->execute(array($d['id']));
    $pdo->commit();
    ok();
  }

  case 'inv_action': {
    // 持ち出し(out) / 返却(return) / 使用(use) / 調整(adjust)
    require_token();
    $d = body_json();
    if (empty($d['id'])) fail('id は必須です');
    $action = isset($d['action']) ? $d['action'] : '';
    $allowed = array('out' => 1, 'return' => 1, 'use' => 1, 'adjust' => 1);
    if (!isset($allowed[$action])) fail('不明な操作です');
    if ($action === 'adjust') require_admin($d); // 数量の直接修正は管理者のみ
    $qty = isset($d['qty']) ? (int)$d['qty'] : 0;
    if ($qty <= 0 && $action !== 'adjust') fail('個数は1以上を入力してください');
    $person = author_of($d);
    $note = trim(isset($d['note']) ? $d['note'] : '');

    $drv = defined('DB_DRIVER') ? DB_DRIVER : 'mysql';
    $lock = ($drv === 'mysql' || $drv === 'pgsql') ? ' FOR UPDATE' : '';
    $pdo->beginTransaction();
    $q = $pdo->prepare('SELECT qty FROM inv_items WHERE id = ?' . $lock);
    $q->execute(array($d['id']));
    $cur = $q->fetchColumn();
    if ($cur === false) { $pdo->rollBack(); fail('商品が存在しません', 404); }
    $cur = (int)$cur;

    if ($action === 'out' || $action === 'use') {
      if ($qty > $cur) { $pdo->rollBack(); fail('現在個数（' . $cur . '）を超える数は指定できません'); }
      $newQty = $cur - $qty;
      $logQty = $qty;
    } elseif ($action === 'return') {
      $newQty = $cur + $qty;
      $logQty = $qty;
    } else { // adjust: qty を「新しい現在個数」として設定
      $newQty = $qty < 0 ? 0 : $qty;
      $logQty = $newQty - $cur; // 差分（±）
    }

    $up = $pdo->prepare('UPDATE inv_items SET qty = ?, updated_at = ? WHERE id = ?');
    $up->execute(array($newQty, now_ms(), $d['id']));
    $lg = $pdo->prepare('INSERT INTO inv_logs (id, item_id, action, qty, balance, person, note, created_at) VALUES (?,?,?,?,?,?,?,?)');
    $lg->execute(array(gen_id(), $d['id'], $action, $logQty, $newQty, $person, $note, now_ms()));
    $pdo->commit();
    ok(array('qty' => $newQty));
  }

  case 'inv_log_update': {
    // 過去履歴の修正（管理者）。qty/person/note/created_at を更新し、在庫と残数を再計算
    require_token();
    $d = body_json();
    require_admin($d);
    if (empty($d['id'])) fail('id は必須です');
    $q = $pdo->prepare('SELECT item_id FROM inv_logs WHERE id = ?');
    $q->execute(array($d['id']));
    $itemId = $q->fetchColumn();
    if ($itemId === false) fail('履歴が存在しません', 404);
    $sets = array(); $args = array();
    if (array_key_exists('qty', $d))    { $sets[] = 'qty = ?';        $args[] = (int)$d['qty']; }
    if (array_key_exists('person', $d)) { $sets[] = 'person = ?';     $args[] = mb_substr(trim((string)$d['person']), 0, 120); }
    if (array_key_exists('note', $d))   { $sets[] = 'note = ?';       $args[] = mb_substr(trim((string)$d['note']), 0, 255); }
    if (array_key_exists('created_at', $d) && $d['created_at'] !== '') { $sets[] = 'created_at = ?'; $args[] = (int)$d['created_at']; }
    if ($sets) {
      $args[] = $d['id'];
      $pdo->beginTransaction();
      $pdo->prepare('UPDATE inv_logs SET ' . implode(', ', $sets) . ' WHERE id = ?')->execute($args);
      $bal = cbc_inv_recalc($pdo, $itemId);
      $pdo->commit();
      ok(array('qty' => $bal));
    }
    ok();
  }

  case 'inv_log_delete': {
    // 過去履歴の削除（管理者）。在庫と残数を再計算
    require_token();
    $d = body_json();
    require_admin($d);
    if (empty($d['id'])) fail('id は必須です');
    $q = $pdo->prepare('SELECT item_id FROM inv_logs WHERE id = ?');
    $q->execute(array($d['id']));
    $itemId = $q->fetchColumn();
    if ($itemId === false) fail('履歴が存在しません', 404);
    $pdo->beginTransaction();
    $pdo->prepare('DELETE FROM inv_logs WHERE id = ?')->execute(array($d['id']));
    $bal = cbc_inv_recalc($pdo, $itemId);
    $pdo->commit();
    ok(array('qty' => $bal));
  }

  default:
    fail('不明なアクションです: ' . $action, 404);
}

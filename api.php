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
if (!defined('GEMINI_API_KEY'))   define('GEMINI_API_KEY', '');
if (!defined('GEMINI_MODEL'))     define('GEMINI_MODEL', 'gemini-1.5-flash');
if (!defined('GOOGLE_MAPS_API_KEY')) define('GOOGLE_MAPS_API_KEY', '');
if (!defined('TRAVEL_ORIGIN'))    define('TRAVEL_ORIGIN', '東京都台東区台東2-1-1'); // 交通費の起点（日本リテイル）

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

/* HTML本文をプレーンテキスト化（AIへ渡す用・検索用） */
function cbc_html_to_plain($html) {
  $s = (string)$html;
  $s = preg_replace('/<\s*br\s*\/?>/i', "\n", $s);
  $s = preg_replace('/<\/(div|p|li|h[1-6]|tr)\s*>/i', "\n", $s);
  $s = strip_tags($s);
  $s = html_entity_decode($s, ENT_QUOTES | ENT_HTML5, 'UTF-8');
  $s = preg_replace("/[ \t]+\n/", "\n", $s);
  $s = preg_replace("/\n{3,}/", "\n\n", $s);
  return trim($s);
}

/* app_settings（キー/値）の読み書き（ピン留め・AIモデルのキャッシュ等に使用） */
function cbc_setting_get($pdo, $k, $def = '') {
  try { $st = $pdo->prepare('SELECT v FROM app_settings WHERE k = ?'); $st->execute(array($k));
    $v = $st->fetchColumn(); return ($v === false || $v === null) ? $def : $v; }
  catch (Throwable $e) { return $def; }
}
function cbc_setting_set($pdo, $k, $v) {
  try { $pdo->prepare('DELETE FROM app_settings WHERE k = ?')->execute(array($k));
    $pdo->prepare('INSERT INTO app_settings (k, v) VALUES (?, ?)')->execute(array($k, $v)); }
  catch (Throwable $e) { /* 保存失敗は致命ではない */ }
}

/* 1モデルに対して1回だけ generateContent を呼ぶ。戻り値: array(httpCode, responseBody, networkError) */
function cbc_gemini_call($model, $bodyJson) {
  $url = 'https://generativelanguage.googleapis.com/v1beta/models/' . rawurlencode($model) . ':generateContent?key=' . urlencode(GEMINI_API_KEY);
  if (function_exists('curl_init')) {
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
      CURLOPT_POST => true,
      CURLOPT_HTTPHEADER => array('Content-Type: application/json'),
      CURLOPT_POSTFIELDS => $bodyJson,
      CURLOPT_RETURNTRANSFER => true,
      CURLOPT_TIMEOUT => 30,
    ));
    $resp = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if ($resp === false) return array(0, '', $err !== '' ? $err : 'connection error');
    return array($code, $resp, '');
  }
  $ctx = stream_context_create(array('http' => array(
    'method' => 'POST', 'header' => "Content-Type: application/json\r\n",
    'content' => $bodyJson, 'timeout' => 30, 'ignore_errors' => true,
  )));
  $resp = @file_get_contents($url, false, $ctx);
  if ($resp === false) return array(0, '', 'サーバーの外部通信設定をご確認ください');
  $code = 200;
  if (isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m)) $code = (int)$m[1];
  return array($code, $resp, '');
}

/* Google Gemini 呼び出し（APIキーはサーバー内のみ）。$jsonMode=true でJSON応答を要求。
   モデル名の違い（廃止・改名）を吸収するため、候補モデルを順に試し、成功したモデルを記憶する。 */
function cbc_gemini($pdo, $prompt, $jsonMode = false, $maxTokens = 1024) {
  if (GEMINI_API_KEY === '') fail('AI機能が未設定です（config.php に GEMINI_API_KEY を設定してください）', 400);
  $payload = array(
    'contents' => array(array('parts' => array(array('text' => $prompt)))),
    'generationConfig' => array('temperature' => 0.2, 'maxOutputTokens' => (int)$maxTokens),
  );
  if ($jsonMode) $payload['generationConfig']['responseMimeType'] = 'application/json';
  $bodyJson = json_encode($payload, JSON_UNESCAPED_UNICODE);

  // 候補モデル: 前回成功したもの → config指定 → 現行の既定候補（重複除去）
  $candidates = array();
  foreach (array(cbc_setting_get($pdo, 'gemini_model_ok', ''), GEMINI_MODEL,
    'gemini-2.0-flash', 'gemini-2.5-flash', 'gemini-flash-latest', 'gemini-1.5-flash') as $m) {
    $m = trim((string)$m);
    if ($m !== '' && !in_array($m, $candidates, true)) $candidates[] = $m;
  }

  $lastMsg = '';
  foreach ($candidates as $model) {
    list($code, $resp, $neterr) = cbc_gemini_call($model, $bodyJson);
    if ($neterr !== '') fail('AIサーバーに接続できません: ' . $neterr, 502); // 通信自体の失敗は打ち切り
    $data = json_decode($resp, true);
    if ($code >= 200 && $code < 300 && is_array($data)) {
      cbc_setting_set($pdo, 'gemini_model_ok', $model); // 使えたモデルを記憶（次回から直接）
      $text = '';
      if (isset($data['candidates'][0]['content']['parts']) && is_array($data['candidates'][0]['content']['parts'])) {
        foreach ($data['candidates'][0]['content']['parts'] as $p) { if (isset($p['text'])) $text .= $p['text']; }
      }
      return $text;
    }
    $lastMsg = (is_array($data) && isset($data['error']['message'])) ? $data['error']['message'] : ('HTTP ' . $code);
    // モデルが無い/未対応のときだけ次の候補へ。それ以外（キー不正・権限・課金等）は即エラー。
    if (!preg_match('/not found|not supported|unknown|does not exist|unsupported/i', $lastMsg)) {
      $hint = '';
      if (preg_match('/quota|billing|free_tier|limit:\s*0|RESOURCE_EXHAUSTED/i', $lastMsg)) {
        $hint = "\n【対処】Googleの無料枠が0/上限超過です。Google AI Studio の「課金」でプロジェクトにお支払い情報を設定すると解消します（Flashは低額）。";
      } elseif (preg_match('/API key not valid|API_KEY_INVALID|PERMISSION_DENIED|permission/i', $lastMsg)) {
        $hint = "\n【対処】APIキーが正しくないか権限がありません。config.php の GEMINI_API_KEY を再確認してください。";
      }
      fail('AI呼び出しに失敗しました: ' . $lastMsg . $hint, ($code === 429 ? 429 : 502));
    }
  }
  fail('利用可能なGeminiモデルが見つかりませんでした。config.php の GEMINI_MODEL をご確認ください（例: gemini-2.0-flash）。詳細: ' . $lastMsg, 502);
}
function now_ms() { return (int) round(microtime(true) * 1000); }

/* 汎用 HTTP GET（curl があれば curl、無ければ file_get_contents）。戻り値: array($code, $body, $err) */
function cbc_http_get($url) {
  if (function_exists('curl_init')) {
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
      CURLOPT_RETURNTRANSFER => true,
      CURLOPT_TIMEOUT => 20,
      CURLOPT_FOLLOWLOCATION => true,
    ));
    $resp = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if ($resp === false) return array(0, '', $err !== '' ? $err : 'connection error');
    return array($code, $resp, '');
  }
  $ctx = stream_context_create(array('http' => array('timeout' => 20, 'ignore_errors' => true)));
  $resp = @file_get_contents($url, false, $ctx);
  if ($resp === false) return array(0, '', 'サーバーの外部通信設定をご確認ください');
  $code = 200;
  if (isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m)) $code = (int)$m[1];
  return array($code, $resp, '');
}

/* Google Maps Distance Matrix で、車の移動距離（km・片道）を求める。
   戻り値: array($km|null, $errorMessage, $resolvedDestination) */
function cbc_maps_distance_km($origin, $dest) {
  if (GOOGLE_MAPS_API_KEY === '') return array(null, 'Googleマップの距離計算は未設定です（config.php の GOOGLE_MAPS_API_KEY）。距離は手入力してください。', '');
  $url = 'https://maps.googleapis.com/maps/api/distancematrix/json'
    . '?origins=' . rawurlencode($origin)
    . '&destinations=' . rawurlencode($dest)
    . '&mode=driving&language=ja&region=jp&units=metric&key=' . urlencode(GOOGLE_MAPS_API_KEY);
  list($code, $body, $err) = cbc_http_get($url);
  if ($code !== 200 || $body === '') return array(null, '距離の取得に失敗しました（通信エラー）: ' . $err, '');
  $j = json_decode($body, true);
  if (!is_array($j)) return array(null, '距離の応答を解析できませんでした', '');
  $status = isset($j['status']) ? $j['status'] : '';
  if ($status !== 'OK') {
    $msg = isset($j['error_message']) ? $j['error_message'] : $status;
    return array(null, 'Googleマップのエラー: ' . $msg, '');
  }
  $el = isset($j['rows'][0]['elements'][0]) ? $j['rows'][0]['elements'][0] : null;
  if (!$el || !isset($el['status']) || $el['status'] !== 'OK') {
    $es = $el && isset($el['status']) ? $el['status'] : 'NOT_FOUND';
    return array(null, '目的地までの経路が見つかりませんでした（住所をご確認ください / ' . $es . '）', '');
  }
  $meters = isset($el['distance']['value']) ? (int)$el['distance']['value'] : 0;
  $resolved = isset($j['destination_addresses'][0]) ? $j['destination_addresses'][0] : '';
  $km = round($meters / 1000, 1);
  return array($km, '', $resolved);
}

/* 費用の内訳（[{amount,note}, ...]）を正規化する。金額0・空行は除く。 */
function cbc_trip_lines($v) {
  $out = array();
  if (!is_array($v)) return $out;
  foreach ($v as $row) {
    if (is_array($row)) {
      $amt = isset($row['amount']) ? (int)$row['amount'] : 0;
      $note = isset($row['note']) ? mb_substr(trim((string)$row['note']), 0, 100) : '';
    } else {
      $amt = (int)$row; $note = '';
    }
    if ($amt < 0) $amt = 0;
    if ($amt === 0 && $note === '') continue;
    $out[] = array('amount' => $amt, 'note' => $note);
  }
  return $out;
}
function cbc_trip_lines_sum($lines) {
  $s = 0; foreach ($lines as $l) $s += (int)$l['amount']; return $s;
}
/* 移動手段。'car'（車）と 'train'（電車）のみ。既定は車。 */
function cbc_trip_mode($d) {
  $m = isset($d['mode']) ? strtolower(trim((string)$d['mode'])) : 'car';
  return ($m === 'train') ? 'train' : 'car';
}
/* 交通費の金額を計算。内訳（*_items）があればその合計を採用し、無ければ従来の単一金額を使う。
   車＝ガソリン代＋高速代＋駐車場代＋その他、電車＝運賃＋その他（他方の項目は 0 にそろえる）。
   戻り値: array(区分, 片道km, 往復1/0, 単価, ガソリン代, 運賃, 高速代, 駐車場代, その他, 合計, 内訳配列) */
function cbc_trip_costs($d) {
  $mode = cbc_trip_mode($d);
  $oneWay = isset($d['one_way_km']) ? (float)$d['one_way_km'] : 0;
  if ($oneWay < 0) $oneWay = 0;
  $round = !empty($d['round_trip']) ? 1 : 0;
  $rate = isset($d['gas_rate']) ? (int)$d['gas_rate'] : 18;
  if ($rate < 0) $rate = 0;

  $fareLines = cbc_trip_lines(isset($d['fare_items']) ? $d['fare_items'] : null);
  $tollLines = cbc_trip_lines(isset($d['toll_items']) ? $d['toll_items'] : null);
  $parkLines = cbc_trip_lines(isset($d['parking_items']) ? $d['parking_items'] : null);
  $otherLines = cbc_trip_lines(isset($d['other_items']) ? $d['other_items'] : null);

  $fare  = isset($d['fare_items'])    ? cbc_trip_lines_sum($fareLines)  : (isset($d['fare_cost']) ? (int)$d['fare_cost'] : 0);
  $toll  = isset($d['toll_items'])    ? cbc_trip_lines_sum($tollLines)  : (isset($d['toll_cost']) ? (int)$d['toll_cost'] : 0);
  $park  = isset($d['parking_items']) ? cbc_trip_lines_sum($parkLines)  : (isset($d['parking_cost']) ? (int)$d['parking_cost'] : 0);
  $other = isset($d['other_items'])   ? cbc_trip_lines_sum($otherLines) : (isset($d['other_cost']) ? (int)$d['other_cost'] : 0);
  if ($fare < 0) $fare = 0;
  if ($toll < 0) $toll = 0;
  if ($park < 0) $park = 0;
  if ($other < 0) $other = 0;

  if ($mode === 'train') {
    // 電車は距離・ガソリン代・高速代・駐車場代を使わない。
    // round（往復）は、電車では「運賃を往復ぶんで入力したか」のフラグとして、そのまま保持する。
    $oneWay = 0; $gas = 0; $toll = 0; $park = 0;
    $tollLines = array(); $parkLines = array();
  } else {
    // 車は運賃を使わない
    $fare = 0; $fareLines = array();
    $gas = (int) round($oneWay * ($round ? 2 : 1) * $rate);
  }
  $total = $gas + $fare + $toll + $park + $other;
  $details = array('fare' => $fareLines, 'toll' => $tollLines, 'park' => $parkLines, 'other' => $otherLines);
  return array($mode, $oneWay, $round, $rate, $gas, $fare, $toll, $park, $other, $total, $details);
}
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

/* ---------- アプリ内ログイン（ページ閲覧権限） ---------- */
// 現在のセッション（X-User-Token）を返す。無効/期限切れは null。
function cbc_session($pdo) {
  static $cached = false;
  if ($cached !== false) return $cached;
  $cached = null;
  $tok = isset($_SERVER['HTTP_X_USER_TOKEN']) ? trim($_SERVER['HTTP_X_USER_TOKEN']) : '';
  if ($tok !== '') {
    try {
      $q = $pdo->prepare('SELECT token, username, is_admin, expires_at FROM sessions WHERE token = ?');
      $q->execute(array($tok));
      $r = $q->fetch();
      if ($r && (int)$r['expires_at'] > now_ms()) {
        $cached = array('token' => $tok, 'username' => $r['username'], 'is_admin' => ((int)$r['is_admin'] === 1));
      }
    } catch (Throwable $e) { $cached = null; }
  }
  return $cached;
}
// ユーザーに許可された大項目（カテゴリ）ID配列。未設定は空配列（何も見えない）。
function cbc_user_allowed($pdo, $username) {
  try {
    $q = $pdo->prepare('SELECT allowed FROM users WHERE username = ?');
    $q->execute(array($username));
    $v = $q->fetchColumn();
    if ($v === false || $v === null || $v === '') return array();
    $a = json_decode($v, true);
    return is_array($a) ? array_values(array_filter($a, 'is_string')) : array();
  } catch (Throwable $e) { return array(); }
}
// ユーザーの表示名（登録者名に使う）。display_name が空なら username を返す。
function cbc_display_name($pdo, $username) {
  if ($username === null || $username === '') return null;
  try {
    $q = $pdo->prepare('SELECT display_name FROM users WHERE username = ?');
    $q->execute(array($username));
    $n = $q->fetchColumn();
    if ($n !== false && $n !== null && trim((string)$n) !== '') return mb_substr(trim((string)$n), 0, 120);
  } catch (Throwable $e) {}
  return mb_substr((string)$username, 0, 120);
}
// 項目の登録者名：ログイン中ならそのユーザーの表示名を自動採用（端末のみ表示のときは author 指定にフォールバック）。
function cbc_node_author($pdo, $d) {
  $s = cbc_session($pdo);
  if ($s) { $n = cbc_display_name($pdo, $s['username']); if ($n !== null && $n !== '') return $n; }
  return author_of($d);
}
// ユーザー登録（users行）が存在するか。存在すれば本人パスワード変更が可能。
function cbc_user_exists($pdo, $username) {
  try {
    $q = $pdo->prepare('SELECT 1 FROM users WHERE username = ?');
    $q->execute(array($username));
    return (bool)$q->fetchColumn();
  } catch (Throwable $e) { return false; }
}
function require_login($pdo) {
  $s = cbc_session($pdo);
  if (!$s) fail('ログインが必要です', 401);
  return $s;
}
function require_admin_session($pdo) {
  $s = cbc_session($pdo);
  if (!$s || !$s['is_admin']) fail('管理者としてログインしてください', 403);
  return $s;
}
// フラットなノード配列を、許可された大項目（＝ルート）配下だけに絞る
function cbc_filter_allowed($rows, $allowed) {
  $allow = array(); foreach ($allowed as $a) $allow[$a] = true;
  $parent = array(); foreach ($rows as $r) $parent[$r['id']] = $r['parent_id'];
  $rootOf = function ($id) use ($parent) {
    $g = 0;
    while (isset($parent[$id]) && $parent[$id] !== null && $parent[$id] !== '' && $g++ < 60) $id = $parent[$id];
    return $id;
  };
  $out = array();
  foreach ($rows as $r) { if (isset($allow[$rootOf($r['id'])])) $out[] = $r; }
  return $out;
}
// 「オンサイト」を含む項目のルート大項目ID一覧（交通費のユーザー絞り込み用）
function cbc_onsite_root_ids($pdo) {
  try {
    $rows = $pdo->query('SELECT id, parent_id, title FROM nodes')->fetchAll();
  } catch (Throwable $e) { return array(); }
  $parent = array();
  foreach ($rows as $r) { $parent[$r['id']] = $r['parent_id']; }
  $rootOf = function ($id) use ($parent) {
    $g = 0;
    while (isset($parent[$id]) && $parent[$id] !== null && $parent[$id] !== '' && $g++ < 60) $id = $parent[$id];
    return $id;
  };
  $roots = array();
  foreach ($rows as $r) {
    if (mb_strpos((string)$r['title'], 'オンサイト') !== false) $roots[$rootOf($r['id'])] = true;
  }
  return array_keys($roots);
}
// 「オンサイト」カテゴリを閲覧できるアカウント（管理者＋許可された一般ユーザー）
function cbc_onsite_users($pdo) {
  $onsite = cbc_onsite_root_ids($pdo);
  $allow = array(); foreach ($onsite as $r) $allow[$r] = true;
  $out = array();
  try {
    foreach ($pdo->query('SELECT username, display_name, is_admin, allowed FROM users ORDER BY display_name, username')->fetchAll() as $u) {
      $ok = ((int)$u['is_admin'] === 1);
      if (!$ok) {
        $al = json_decode($u['allowed'], true);
        if (is_array($al)) foreach ($al as $a) { if (isset($allow[$a])) { $ok = true; break; } }
      }
      if ($ok) $out[] = array('username' => $u['username'], 'display_name' => $u['display_name']);
    }
  } catch (Throwable $e) {}
  return $out;
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
  $aiOn = true; // AI機能の表示ON/OFF（管理者が切替・全端末共有）。既定はON。
  $invOn = false; // 在庫管理の表示ON/OFF（管理者が切替・全端末共有）。既定はOFF（非表示）。
  $tripOn = true; // 交通費精算の表示ON/OFF（管理者が切替・全端末共有）。既定はON。
  $tripPos = 999; // TOPの大項目一覧での「交通費精算」の並び順（大きいほど後ろ）
  $logoOn = true; // ヘッダーロゴの表示ON/OFF（管理者が切替・全端末共有）。既定はON。
  $logoUrl = 'logo-default.png'; // ヘッダーロゴの画像URL。既定は同梱のロゴ画像。
  if ($connected) {
    try { $aiOn = (cbc_setting_get(cbc_pdo(), 'ai_enabled', '1') !== '0'); } catch (Throwable $e) {}
    try { $invOn = (cbc_setting_get(cbc_pdo(), 'inv_enabled', '0') === '1'); } catch (Throwable $e) {}
    try { $tripOn = (cbc_setting_get(cbc_pdo(), 'trip_enabled', '1') !== '0'); } catch (Throwable $e) {}
    try { $tripPos = (int)cbc_setting_get(cbc_pdo(), 'trip_pos', '999'); } catch (Throwable $e) {}
    try { $logoOn = (cbc_setting_get(cbc_pdo(), 'logo_enabled', '1') !== '0'); } catch (Throwable $e) {}
    try { $logoUrl = cbc_setting_get(cbc_pdo(), 'logo_url', 'logo-default.png'); } catch (Throwable $e) {}
  }
  ok(array(
    'dbConnected' => $connected,
    'hasToken'    => (API_TOKEN !== ''),
    'hasGemini'   => (GEMINI_API_KEY !== ''),
    'hasMaps'     => (GOOGLE_MAPS_API_KEY !== ''),
    'travelOrigin' => TRAVEL_ORIGIN,
    'aiOn'        => $aiOn,
    'invOn'       => $invOn,
    'tripOn'      => $tripOn,
    'tripPos'     => $tripPos,
    'logoOn'      => $logoOn,
    'logoUrl'     => $logoUrl,
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
    $s = require_login($pdo); // アプリ内ログイン必須
    $rows = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
    $nodes = prune_locked($rows);
    if (!$s['is_admin']) $nodes = cbc_filter_allowed($nodes, cbc_user_allowed($pdo, $s['username'])); // 権限フィルタ
    ok(array('nodes' => $nodes));
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
    $who = cbc_node_author($pdo, $d);
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
    $who = cbc_node_author($pdo, $d);
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

  case 'pins_get': {
    // ピン留め（全端末で共有）。読み取りは誰でも可。
    $st = $pdo->prepare("SELECT v FROM app_settings WHERE k = ?");
    $st->execute(array('pins'));
    $v = $st->fetchColumn();
    ok(array('pins' => ($v === false || $v === null) ? '[]' : $v));
  }

  case 'pins_set': {
    require_token();
    $d = body_json();
    $arr = (isset($d['pins']) && is_array($d['pins'])) ? array_values(array_filter($d['pins'], 'is_string')) : array();
    $json = json_encode($arr, JSON_UNESCAPED_UNICODE);
    // 全ドライバ共通の upsert（DELETE→INSERT）
    $pdo->beginTransaction();
    $pdo->prepare("DELETE FROM app_settings WHERE k = ?")->execute(array('pins'));
    $pdo->prepare("INSERT INTO app_settings (k, v) VALUES (?, ?)")->execute(array('pins', $json));
    $pdo->commit();
    ok(array('pins' => $json));
  }

  case 'ai_set_enabled': {
    // AI機能の表示ON/OFFを切り替え（全端末共有）
    require_token();
    $d = body_json();
    $on = !empty($d['on']);
    cbc_setting_set($pdo, 'ai_enabled', $on ? '1' : '0');
    ok(array('aiOn' => $on));
  }

  case 'trip_set_enabled': {
    // 交通費精算の表示ON/OFFを切り替え（全端末共有・管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $on = !empty($d['on']);
    cbc_setting_set($pdo, 'trip_enabled', $on ? '1' : '0');
    ok(array('tripOn' => $on));
  }

  case 'trip_set_pos': {
    // TOPの大項目一覧での「交通費精算」の並び順（全端末共有）
    require_token();
    $d = body_json();
    $pos = isset($d['pos']) ? (int)$d['pos'] : 999;
    if ($pos < 0) $pos = 0;
    cbc_setting_set($pdo, 'trip_pos', (string)$pos);
    ok(array('tripPos' => $pos));
  }

  case 'inv_set_enabled': {
    // 在庫管理の表示ON/OFFを切り替え（全端末共有・管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $on = !empty($d['on']);
    cbc_setting_set($pdo, 'inv_enabled', $on ? '1' : '0');
    ok(array('invOn' => $on));
  }

  case 'logo_set_enabled': {
    // ヘッダーロゴの表示ON/OFFを切り替え（全端末共有・管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $on = !empty($d['on']);
    cbc_setting_set($pdo, 'logo_enabled', $on ? '1' : '0');
    ok(array('logoOn' => $on));
  }

  case 'logo_set_url': {
    // ヘッダーロゴの画像を入れ替え（事前に action=upload でアップロード済みのURLを指定。全端末共有・管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $url = trim((string)(isset($d['url']) ? $d['url'] : ''));
    if ($url === '') fail('url は必須です');
    $url = mb_substr($url, 0, 500);
    cbc_setting_set($pdo, 'logo_url', $url);
    ok(array('logoUrl' => $url));
  }

  case 'ai_summarize': {
    // 指定項目の本文を AI で要約
    $s = require_login($pdo);
    $d = body_json();
    $id = isset($d['id']) ? $d['id'] : '';
    if ($id === '') fail('id は必須です');
    // 非管理者は、許可された大項目配下の項目のみ要約可
    if (!$s['is_admin']) {
      $all = $pdo->query('SELECT id, parent_id FROM nodes')->fetchAll();
      $vis = cbc_filter_allowed($all, cbc_user_allowed($pdo, $s['username']));
      $okv = false; foreach ($vis as $v) { if ($v['id'] === $id) { $okv = true; break; } }
      if (!$okv) fail('この項目を要約する権限がありません', 403);
    }
    $q = $pdo->prepare('SELECT title, body FROM nodes WHERE id = ?');
    $q->execute(array($id));
    $row = $q->fetch();
    if (!$row) fail('項目が見つかりません', 404);
    $plain = cbc_html_to_plain($row['body']);
    if (trim($plain) === '') ok(array('summary' => '（この項目には要約できる本文がありません）'));
    if (mb_strlen($plain) > 8000) $plain = mb_substr($plain, 0, 8000);
    $prompt = "あなたは作業マニュアルの要約アシスタントです。次の項目の内容を日本語で、要点を箇条書き（3〜6個）で簡潔に要約してください。"
      . "手順の順番が重要な場合は順序を保ってください。本文に書かれていない情報は追加しないでください。\n\n"
      . "【タイトル】" . $row['title'] . "\n【本文】\n" . $plain;
    $summary = cbc_gemini($pdo, $prompt, false, 800);
    ok(array('summary' => trim($summary)));
  }

  case 'ai_search': {
    // 自然文の質問に最も関連する項目を AI が選ぶ（意味で探す）
    $s = require_login($pdo);
    $d = body_json();
    $query = isset($d['q']) ? trim($d['q']) : '';
    if ($query === '') fail('検索語が必要です');
    $rows = $pdo->query('SELECT id, parent_id, title, body FROM nodes')->fetchAll();
    if (!$s['is_admin']) $rows = cbc_filter_allowed($rows, cbc_user_allowed($pdo, $s['username'])); // 権限内のみ
    if (!$rows) ok(array('results' => array()));
    $byId = array();
    foreach ($rows as $r) $byId[$r['id']] = $r;
    $lines = array();
    foreach ($rows as $r) {
      $path = array(); $cur = $r; $guard = 0;
      while ($cur && $guard++ < 20) {
        array_unshift($path, $cur['title']);
        $pid = $cur['parent_id'];
        $cur = ($pid !== null && $pid !== '' && isset($byId[$pid])) ? $byId[$pid] : null;
      }
      $snippet = mb_substr(cbc_html_to_plain($r['body']), 0, 160);
      $snippet = str_replace(array("\r", "\n"), ' ', $snippet);
      $lines[] = '- id:' . $r['id'] . ' | 見出し:' . implode(' > ', $path) . ' | 内容:' . $snippet;
    }
    $prompt = "あなたは作業マニュアルの検索アシスタントです。ユーザーの質問に最も関連する項目を、下の一覧から関連度の高い順に最大5件選び、"
      . "JSON配列だけを出力してください。各要素は {\"id\":\"項目ID\",\"reason\":\"関連する理由（日本語40字以内）\"} の形式。"
      . "該当が無ければ [] を出力。JSON以外は一切出力しないこと。\n\n【質問】" . $query . "\n\n【項目一覧】\n" . implode("\n", $lines);
    $text = cbc_gemini($pdo, $prompt, true, 1024);
    $arr = json_decode($text, true);
    if (!is_array($arr)) $arr = array();
    $results = array();
    foreach ($arr as $item) {
      if (!is_array($item) || !isset($item['id']) || !isset($byId[$item['id']])) continue;
      $results[] = array('id' => (string)$item['id'], 'reason' => isset($item['reason']) ? (string)$item['reason'] : '');
      if (count($results) >= 5) break;
    }
    ok(array('results' => $results));
  }

  case 'login': {
    // アプリ内ログイン。ADMIN_PW でのログインは管理者（ID不問・全ページ）。
    $d = body_json();
    $id = isset($d['id']) ? trim((string)$d['id']) : '';
    $pw = isset($d['pw']) ? (string)$d['pw'] : '';
    if ($id === '' || $pw === '') fail('IDとパスワードを入力してください');
    $isAdmin = false; $allowed = array();
    if (ADMIN_PW !== '' && hash_equals(ADMIN_PW, $pw)) {
      $isAdmin = true; // 管理者パスワードでログイン
    } else {
      $q = $pdo->prepare('SELECT username, pass_hash, is_admin FROM users WHERE username = ?');
      $q->execute(array($id));
      $u = $q->fetch();
      if (!$u || empty($u['pass_hash']) || !password_verify($pw, $u['pass_hash'])) fail('IDまたはパスワードが違います', 401);
      $isAdmin = ((int)$u['is_admin'] === 1);
      $allowed = cbc_user_allowed($pdo, $id);
    }
    // 期限切れセッションの掃除（ベストエフォート）
    try { $pdo->prepare('DELETE FROM sessions WHERE expires_at < ?')->execute(array(now_ms())); } catch (Throwable $e) {}
    $token = bin2hex(random_bytes(24));
    $exp = now_ms() + 30 * 24 * 60 * 60 * 1000; // 30日
    $pdo->prepare('INSERT INTO sessions (token, username, is_admin, created_at, expires_at) VALUES (?,?,?,?,?)')
      ->execute(array($token, $id, $isAdmin ? 1 : 0, now_ms(), $exp));
    ok(array('token' => $token, 'username' => $id, 'name' => cbc_display_name($pdo, $id),
      'isAdmin' => $isAdmin, 'allowed' => $isAdmin ? null : $allowed,
      'canChangePw' => cbc_user_exists($pdo, $id)));
  }

  case 'logout': {
    $s = cbc_session($pdo);
    if ($s) $pdo->prepare('DELETE FROM sessions WHERE token = ?')->execute(array($s['token']));
    ok();
  }

  case 'me': {
    $s = cbc_session($pdo);
    if (!$s) fail('未ログインです', 401);
    ok(array('username' => $s['username'], 'name' => cbc_display_name($pdo, $s['username']),
      'isAdmin' => $s['is_admin'],
      'allowed' => $s['is_admin'] ? null : cbc_user_allowed($pdo, $s['username']),
      'canChangePw' => cbc_user_exists($pdo, $s['username'])));
  }

  case 'users_list': {
    require_admin_session($pdo);
    $rows = $pdo->query('SELECT username, display_name, is_admin, allowed FROM users ORDER BY username')->fetchAll();
    $out = array();
    foreach ($rows as $r) {
      $a = json_decode($r['allowed'], true);
      $out[] = array('username' => $r['username'], 'name' => $r['display_name'],
        'isAdmin' => ((int)$r['is_admin'] === 1),
        'allowed' => is_array($a) ? array_values(array_filter($a, 'is_string')) : array());
    }
    ok(array('users' => $out));
  }

  case 'user_save': {
    require_admin_session($pdo);
    $d = body_json();
    $u = trim((string)(isset($d['username']) ? $d['username'] : ''));
    if ($u === '') fail('IDは必須です');
    if (!preg_match('/^[\w.@\-]{1,64}$/u', $u)) fail('IDに使えない文字が含まれています');
    $name = trim((string)(isset($d['name']) ? $d['name'] : ''));
    $name = ($name === '') ? null : mb_substr($name, 0, 120);
    $allowed = (isset($d['allowed']) && is_array($d['allowed'])) ? array_values(array_filter($d['allowed'], 'is_string')) : array();
    $isAdmin = !empty($d['isAdmin']) ? 1 : 0;
    $allowedJson = json_encode($allowed, JSON_UNESCAPED_UNICODE);
    $q = $pdo->prepare('SELECT pass_hash FROM users WHERE username = ?');
    $q->execute(array($u));
    $ex = $q->fetch();
    $hash = $ex ? $ex['pass_hash'] : '';
    if (isset($d['pw']) && $d['pw'] !== '') $hash = password_hash((string)$d['pw'], PASSWORD_DEFAULT);
    if (!$ex && ($hash === '' || $hash === null)) fail('新規ユーザーにはパスワードを設定してください');
    if ($ex) {
      $pdo->prepare('UPDATE users SET display_name = ?, pass_hash = ?, is_admin = ?, allowed = ?, updated_at = ? WHERE username = ?')
        ->execute(array($name, $hash, $isAdmin, $allowedJson, now_ms(), $u));
    } else {
      $pdo->prepare('INSERT INTO users (username, display_name, pass_hash, is_admin, allowed, created_at, updated_at) VALUES (?,?,?,?,?,?,?)')
        ->execute(array($u, $name, $hash, $isAdmin, $allowedJson, now_ms(), now_ms()));
    }
    ok(array('username' => $u));
  }

  case 'user_delete': {
    require_admin_session($pdo);
    $d = body_json();
    $u = trim((string)(isset($d['username']) ? $d['username'] : ''));
    if ($u === '') fail('IDは必須です');
    $pdo->prepare('DELETE FROM users WHERE username = ?')->execute(array($u));
    $pdo->prepare('DELETE FROM sessions WHERE username = ?')->execute(array($u));
    ok();
  }

  case 'change_password': {
    // ログイン中の本人が自分のパスワードを変更する（現在のパスワード確認あり）。
    $s = require_login($pdo);
    $d = body_json();
    $cur = (string)(isset($d['current']) ? $d['current'] : '');
    $new = (string)(isset($d['new']) ? $d['new'] : '');
    if (mb_strlen($new) < 4) fail('新しいパスワードは4文字以上にしてください');
    $q = $pdo->prepare('SELECT pass_hash FROM users WHERE username = ?');
    $q->execute(array($s['username']));
    $u = $q->fetch();
    // 管理者パスワード(ADMIN_PW)でのログインはユーザー登録が無いので、本人変更の対象外。
    if (!$u) fail('このログインは管理者パスワードによるログインのため、ここでは変更できません（config.php の ADMIN_PW を変更してください）', 400);
    if (empty($u['pass_hash']) || !password_verify($cur, $u['pass_hash'])) fail('現在のパスワードが違います', 403);
    $pdo->prepare('UPDATE users SET pass_hash = ?, updated_at = ? WHERE username = ?')
      ->execute(array(password_hash($new, PASSWORD_DEFAULT), now_ms(), $s['username']));
    ok();
  }

  case 'backup_export': {
    // 完全バックアップ（管理者のみ）。本文HTML（色・サイズ・入力欄）・ロック・
    // ユーザー権限（パスワードハッシュ含む）・在庫・設定を、無加工でそのまま書き出す。
    require_admin_session($pdo);
    $nodes    = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
    $users    = $pdo->query('SELECT username, display_name, pass_hash, is_admin, allowed, created_at, updated_at FROM users ORDER BY username')->fetchAll();
    $invItems = $pdo->query('SELECT id, name, model, qty, note, sort_order, created_at, updated_at FROM inv_items ORDER BY sort_order, created_at')->fetchAll();
    $invLogs  = $pdo->query('SELECT id, item_id, action, qty, balance, person, note, created_at FROM inv_logs ORDER BY created_at, id')->fetchAll();
    $settings = $pdo->query('SELECT k, v FROM app_settings')->fetchAll();
    ok(array('backup' => array(
      'app'         => 'case-by-case',
      'version'     => 1,
      'exported_at' => now_ms(),
      'nodes'       => $nodes,
      'users'       => $users,
      'inv_items'   => $invItems,
      'inv_logs'    => $invLogs,
      'settings'    => $settings,
    )));
  }

  case 'backup_import': {
    // 完全バックアップの復元（管理者のみ）。全データを置き換える（トランザクション）。
    // sessions は触らないので、実行中の管理者はログインを維持したまま復元できる。
    require_admin_session($pdo);
    $d = body_json();
    $b = (isset($d['backup']) && is_array($d['backup'])) ? $d['backup'] : $d;
    if (!isset($b['nodes']) || !is_array($b['nodes'])) fail('バックアップの形式が正しくありません（nodes がありません）');
    $ts = now_ms();
    $pdo->beginTransaction();
    try {
      // ---- nodes ----
      $pdo->exec('DELETE FROM nodes');
      $ins = $pdo->prepare('INSERT INTO nodes (id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash) VALUES (?,?,?,?,?,?,?,?,?,?)');
      foreach ($b['nodes'] as $n) {
        $ins->execute(array(
          !empty($n['id']) ? $n['id'] : gen_id(),
          (isset($n['parent_id']) && $n['parent_id'] !== '') ? $n['parent_id'] : null,
          isset($n['sort_order']) ? (int)$n['sort_order'] : 0,
          isset($n['title']) ? $n['title'] : '（無題）',
          isset($n['body']) ? $n['body'] : '',
          (isset($n['created_by']) && $n['created_by'] !== '') ? $n['created_by'] : null,
          (isset($n['updated_by']) && $n['updated_by'] !== '') ? $n['updated_by'] : null,
          isset($n['updated_at']) ? (int)$n['updated_at'] : $ts,
          isset($n['created_at']) ? (int)$n['created_at'] : $ts,
          (isset($n['lock_hash']) && $n['lock_hash'] !== '') ? $n['lock_hash'] : null,
        ));
      }
      // ---- users（ユーザー権限）----
      if (isset($b['users']) && is_array($b['users'])) {
        $pdo->exec('DELETE FROM users');
        $iu = $pdo->prepare('INSERT INTO users (username, display_name, pass_hash, is_admin, allowed, created_at, updated_at) VALUES (?,?,?,?,?,?,?)');
        foreach ($b['users'] as $u) {
          if (empty($u['username'])) continue;
          $allowed = null;
          if (isset($u['allowed'])) $allowed = is_array($u['allowed']) ? json_encode(array_values($u['allowed']), JSON_UNESCAPED_UNICODE) : (string)$u['allowed'];
          $iu->execute(array(
            $u['username'],
            isset($u['display_name']) ? $u['display_name'] : null,
            isset($u['pass_hash']) ? $u['pass_hash'] : null,
            !empty($u['is_admin']) ? 1 : 0,
            $allowed,
            isset($u['created_at']) ? (int)$u['created_at'] : $ts,
            isset($u['updated_at']) ? (int)$u['updated_at'] : $ts,
          ));
        }
      }
      // ---- inv_items ----
      if (isset($b['inv_items']) && is_array($b['inv_items'])) {
        $pdo->exec('DELETE FROM inv_items');
        $ii = $pdo->prepare('INSERT INTO inv_items (id, name, model, qty, note, sort_order, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)');
        foreach ($b['inv_items'] as $it) {
          if (empty($it['id'])) continue;
          $ii->execute(array($it['id'], isset($it['name']) ? $it['name'] : '', isset($it['model']) ? $it['model'] : null,
            isset($it['qty']) ? (int)$it['qty'] : 0, isset($it['note']) ? $it['note'] : null, isset($it['sort_order']) ? (int)$it['sort_order'] : 0,
            isset($it['created_at']) ? (int)$it['created_at'] : $ts, isset($it['updated_at']) ? (int)$it['updated_at'] : $ts));
        }
      }
      // ---- inv_logs ----
      if (isset($b['inv_logs']) && is_array($b['inv_logs'])) {
        $pdo->exec('DELETE FROM inv_logs');
        $il = $pdo->prepare('INSERT INTO inv_logs (id, item_id, action, qty, balance, person, note, created_at) VALUES (?,?,?,?,?,?,?,?)');
        foreach ($b['inv_logs'] as $lg) {
          if (empty($lg['id'])) continue;
          $il->execute(array($lg['id'], isset($lg['item_id']) ? $lg['item_id'] : '', isset($lg['action']) ? $lg['action'] : '',
            isset($lg['qty']) ? (int)$lg['qty'] : 0, isset($lg['balance']) ? (int)$lg['balance'] : 0,
            isset($lg['person']) ? $lg['person'] : null, isset($lg['note']) ? $lg['note'] : null,
            isset($lg['created_at']) ? (int)$lg['created_at'] : $ts));
        }
      }
      // ---- settings（ピン留め・AI/在庫トグル等）----
      if (isset($b['settings']) && is_array($b['settings'])) {
        $pdo->exec('DELETE FROM app_settings');
        $is = $pdo->prepare('INSERT INTO app_settings (k, v) VALUES (?, ?)');
        foreach ($b['settings'] as $st) {
          if (!isset($st['k'])) continue;
          $is->execute(array($st['k'], isset($st['v']) ? $st['v'] : ''));
        }
      }
      $pdo->commit();
    } catch (Throwable $e) {
      if ($pdo->inTransaction()) $pdo->rollBack();
      fail('復元に失敗しました：' . $e->getMessage(), 500);
    }
    ok(array(
      'nodes' => count($b['nodes']),
      'users' => (isset($b['users']) && is_array($b['users'])) ? count($b['users']) : 0,
    ));
  }

  case 'trip_distance': {
    // 起点（TRAVEL_ORIGIN）から目的地までの車の移動距離（片道km）をGoogleマップで求める。
    require_login($pdo);
    $d = body_json();
    $dest = trim((string)(isset($d['destination']) ? $d['destination'] : ''));
    if ($dest === '') fail('目的地の住所を入力してください');
    list($km, $err, $resolved) = cbc_maps_distance_km(TRAVEL_ORIGIN, $dest);
    if ($km === null) fail($err, 400);
    ok(array('km' => $km, 'origin' => TRAVEL_ORIGIN, 'destination' => $resolved));
  }

  case 'trip_save': {
    // 交通費レコードの登録/更新。username はログインセッションから決定（なりすまし不可）。
    // 金額（ガソリン代・合計）はサーバー側で計算して確定する。
    $s = require_login($pdo);
    $d = body_json();
    $date = trim((string)(isset($d['trip_date']) ? $d['trip_date'] : ''));
    if ($date === '' || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) $date = date('Y-m-d');
    list($mode, $oneWay, $round, $rate, $gas, $fare, $toll, $park, $other, $total, $details) = cbc_trip_costs($d);
    $detailsJson = json_encode($details, JSON_UNESCAPED_UNICODE);
    $caseName = mb_substr(trim((string)(isset($d['case_name']) ? $d['case_name'] : '')), 0, 255);
    $origin   = mb_substr(trim((string)(isset($d['origin']) ? $d['origin'] : '')), 0, 255);
    $dest     = mb_substr(trim((string)(isset($d['destination']) ? $d['destination'] : '')), 0, 1000);
    $note     = mb_substr(trim((string)(isset($d['note']) ? $d['note'] : '')), 0, 500);
    $ts = now_ms();
    $id = trim((string)(isset($d['id']) ? $d['id'] : ''));
    if ($id !== '') {
      // 既存レコードの更新：本人か管理者のみ
      $q = $pdo->prepare('SELECT username FROM trips WHERE id = ?');
      $q->execute(array($id));
      $owner = $q->fetchColumn();
      if ($owner === false) fail('対象の記録が見つかりません', 404);
      if (!$s['is_admin'] && $owner !== $s['username']) fail('他のユーザーの記録は編集できません', 403);
      $pdo->prepare('UPDATE trips SET trip_date=?, case_name=?, mode=?, origin=?, destination=?, one_way_km=?, round_trip=?, gas_rate=?, gas_cost=?, fare_cost=?, toll_cost=?, parking_cost=?, other_cost=?, total=?, cost_details=?, note=?, updated_at=? WHERE id=?')
        ->execute(array($date, $caseName, $mode, $origin, $dest, $oneWay, $round, $rate, $gas, $fare, $toll, $park, $other, $total, $detailsJson, $note, $ts, $id));
    } else {
      $id = 't' . bin2hex(random_bytes(9));
      $pdo->prepare('INSERT INTO trips (id, username, display_name, trip_date, case_name, mode, origin, destination, one_way_km, round_trip, gas_rate, gas_cost, fare_cost, toll_cost, parking_cost, other_cost, total, cost_details, note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)')
        ->execute(array($id, $s['username'], cbc_display_name($pdo, $s['username']), $date, $caseName, $mode, $origin, $dest, $oneWay, $round, $rate, $gas, $fare, $toll, $park, $other, $total, $detailsJson, $note, $ts, $ts));
    }
    ok(array('id' => $id, 'gas_cost' => $gas, 'total' => $total));
  }

  case 'trip_users': {
    // 交通費の絞り込み用ユーザー一覧（「オンサイト」を閲覧できるアカウント）。
    // 履歴検索を開いた時点で、記録を読み込まずに担当者リストだけを取得するために使う。
    $s = require_login($pdo);
    ok(array('users' => $s['is_admin'] ? cbc_onsite_users($pdo) : array(), 'is_admin' => $s['is_admin']));
  }

  case 'trip_list': {
    // 一覧。管理者は全員（username で絞り込み可）、一般ユーザーは自分の分のみ。
    // month（YYYY-MM）または from/to（YYYY-MM-DD）で期間を絞れる。
    $s = require_login($pdo);
    $d = body_json();
    $where = array(); $args = array();
    if (!$s['is_admin']) { $where[] = 'username = ?'; $args[] = $s['username']; }
    else if (!empty($d['username'])) { $where[] = 'username = ?'; $args[] = (string)$d['username']; }
    $month = trim((string)(isset($d['month']) ? $d['month'] : ''));
    if ($month !== '' && preg_match('/^\d{4}-\d{2}$/', $month)) { $where[] = 'trip_date LIKE ?'; $args[] = $month . '-%'; }
    $from = trim((string)(isset($d['from']) ? $d['from'] : ''));
    $to   = trim((string)(isset($d['to']) ? $d['to'] : ''));
    if ($from !== '' && preg_match('/^\d{4}-\d{2}-\d{2}$/', $from)) { $where[] = 'trip_date >= ?'; $args[] = $from; }
    if ($to   !== '' && preg_match('/^\d{4}-\d{2}-\d{2}$/', $to))   { $where[] = 'trip_date <= ?'; $args[] = $to; }
    // 移動手段（車／電車）での絞り込み。指定なしは両方。
    $mode = isset($d['mode']) ? strtolower(trim((string)$d['mode'])) : '';
    if ($mode === 'train') { $where[] = 'mode = ?'; $args[] = 'train'; }
    else if ($mode === 'car') { $where[] = "(mode = ? OR mode IS NULL OR mode = '')"; $args[] = 'car'; }
    $sql = 'SELECT id, username, display_name, trip_date, case_name, mode, origin, destination, one_way_km, round_trip, gas_rate, gas_cost, fare_cost, toll_cost, parking_cost, other_cost, total, cost_details, note FROM trips';
    if ($where) $sql .= ' WHERE ' . implode(' AND ', $where);
    $sql .= ' ORDER BY trip_date DESC, created_at DESC';
    $st = $pdo->prepare($sql);
    $st->execute($args);
    $rows = $st->fetchAll();
    $items = array(); $sum = 0;
    foreach ($rows as $r) {
      $items[] = array(
        'id' => $r['id'], 'username' => $r['username'], 'display_name' => $r['display_name'],
        'trip_date' => $r['trip_date'], 'case_name' => $r['case_name'],
        'mode' => (isset($r['mode']) && $r['mode'] === 'train') ? 'train' : 'car',
        'origin' => $r['origin'], 'destination' => $r['destination'],
        'one_way_km' => (float)$r['one_way_km'], 'round_trip' => (int)$r['round_trip'],
        'gas_rate' => (int)$r['gas_rate'], 'gas_cost' => (int)$r['gas_cost'],
        'fare_cost' => (int)$r['fare_cost'],
        'toll_cost' => (int)$r['toll_cost'], 'parking_cost' => (int)$r['parking_cost'],
        'other_cost' => (int)$r['other_cost'],
        'cost_details' => (isset($r['cost_details']) && $r['cost_details'] !== '') ? json_decode($r['cost_details'], true) : null,
        'total' => (int)$r['total'], 'note' => $r['note'],
      );
      $sum += (int)$r['total'];
    }
    // 管理者向け：ユーザー絞り込みプルダウン＝「リテイルオンサイト」を閲覧できるアカウント
    $users = array();
    if ($s['is_admin']) $users = cbc_onsite_users($pdo);
    ok(array('items' => $items, 'total_sum' => $sum, 'is_admin' => $s['is_admin'], 'users' => $users));
  }

  case 'trip_delete': {
    $s = require_login($pdo);
    $d = body_json();
    $id = trim((string)(isset($d['id']) ? $d['id'] : ''));
    if ($id === '') fail('id は必須です');
    $q = $pdo->prepare('SELECT username FROM trips WHERE id = ?');
    $q->execute(array($id));
    $owner = $q->fetchColumn();
    if ($owner === false) { ok(); }
    if (!$s['is_admin'] && $owner !== $s['username']) fail('他のユーザーの記録は削除できません', 403);
    $pdo->prepare('DELETE FROM trips WHERE id = ?')->execute(array($id));
    ok();
  }

  default:
    fail('不明なアクションです: ' . $action, 404);
}

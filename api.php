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
if (!defined('BACKUP_DIR'))       define('BACKUP_DIR', __DIR__ . '/backups'); // 自動バックアップ（完全バックアップJSON）の保存先
if (!defined('GEMINI_API_KEY'))   define('GEMINI_API_KEY', '');
if (!defined('GEMINI_MODEL'))     define('GEMINI_MODEL', 'gemini-2.5-flash');
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

/* 配列が「連番のリスト」かどうか（json_decodeしたJSON配列との区別に使う） */
function cbc_is_list($arr) {
  $i = 0;
  foreach ($arr as $k => $v) { if ($k !== $i) return false; $i++; }
  return true;
}
/* AIの応答テキストからJSON配列を取り出す。コードフェンス（```json ... ```）や
   {"results":[...]} のような包装にもできるだけ対応する（モデルが指示を完全に守らない場合の保険）。 */
function cbc_json_extract_list($text) {
  $text = trim((string)$text);
  if (preg_match('/```(?:json)?\s*(.*?)```/is', $text, $m)) $text = trim($m[1]);
  $data = json_decode($text, true);
  if (!is_array($data)) return array();
  if (cbc_is_list($data)) return $data;
  foreach (array('results', 'items', 'candidates', 'data') as $k) {
    if (isset($data[$k]) && is_array($data[$k])) return $data[$k];
  }
  return array();
}
/* AIの応答テキストから {"summary":"...","items":[...]} 形式のオブジェクトを取り出す（検索＋まとめの同時取得用）。
   コードフェンスにも対応。トップレベルがそのまま配列で返ってきた場合（旧形式）は items 扱いにする。 */
function cbc_json_extract_search_obj($text) {
  $text = trim((string)$text);
  if (preg_match('/```(?:json)?\s*(.*?)```/is', $text, $m)) $text = trim($m[1]);
  $data = json_decode($text, true);
  if (!is_array($data)) return array('summary' => null, 'items' => array());
  if (cbc_is_list($data)) return array('summary' => null, 'items' => $data);
  $items = (isset($data['items']) && is_array($data['items'])) ? $data['items'] : array();
  $summary = (isset($data['summary']) && is_string($data['summary']) && trim($data['summary']) !== '') ? trim($data['summary']) : null;
  return array('summary' => $summary, 'items' => $items);
}
/* AIの応答テキストから「聞き返し」（質問が曖昧なときの確認質問と選択肢）を取り出す。
   {"ask":{"question":"...","options":["...","..."]}} を想定。項目一覧の抽出（cbc_json_extract_list）とは
   独立して行うため、聞き返しの解釈に失敗しても候補一覧の表示には影響しない。
   戻り値: null、または array('question' => string, 'options' => array(string)) */
function cbc_json_extract_ask($text) {
  $text = trim((string)$text);
  if (preg_match('/```(?:json)?\s*(.*?)```/is', $text, $m)) $text = trim($m[1]);
  $data = json_decode($text, true);
  if (!is_array($data) || !isset($data['ask']) || !is_array($data['ask'])) return null;
  $a = $data['ask'];
  $question = isset($a['question']) ? trim((string)$a['question']) : '';
  if ($question === '') return null;
  $options = array();
  if (isset($a['options']) && is_array($a['options'])) {
    foreach ($a['options'] as $o) {
      if (!is_string($o) && !is_numeric($o)) continue;
      $o = trim((string)$o);
      if ($o === '' || in_array($o, $options, true)) continue;
      $options[] = mb_substr($o, 0, 40);
      if (count($options) >= 4) break;
    }
  }
  // 選択肢が無い（または1つしかない）聞き返しは、利用者が次に進む手がかりにならないため出さない
  if (count($options) < 2) return null;
  return array('question' => mb_substr($question, 0, 120), 'options' => $options);
}
/* 日本語は空白区切りが無いため、英数字の並び（製品名・型番など）と、それ以外（漢字・かな等）の
   連続部分を、それぞれ別の単語として切り出す（簡易的な部分一致・関連度スコア用の単語分割）。 */
function cbc_search_terms($query) {
  preg_match_all('/[A-Za-z0-9]+|[^\x00-\x7F\s、。，,　]+/u', $query, $tm);
  return $tm[0];
}
/* $haystack（小文字化済み）の中に $terms がどれだけ含まれるかを点数にする（簡易的な関連度スコア）。
   日本語は「紙が詰まった」のように助詞・活用が付いた形で入力されるため、語がそのままの形で
   本文に現れないことが多い（本文は「紙詰まりの直し方」など）。そのままの一致が無いときは
   2文字ずつに割った部分一致も見て、部分点を与える（重みは小さくし、拾いすぎないようにする）。 */
function cbc_keyword_score($terms, $haystackLower) {
  $score = 0.0;
  foreach ($terms as $t) {
    if ($t === '') continue;
    $tl = mb_strtolower($t);
    if (mb_strpos($haystackLower, $tl) !== false) { $score += 1.0; continue; }
    $len = mb_strlen($tl);
    // 英数字（型番など）は部分一致させると誤爆しやすいので、日本語などの長い語だけを対象にする
    if ($len >= 3 && !preg_match('/^[a-z0-9]+$/', $tl)) {
      $hit = 0; $tot = 0;
      for ($i = 0; $i + 2 <= $len; $i++) {
        $tot++;
        if (mb_strpos($haystackLower, mb_substr($tl, $i, 2)) !== false) $hit++;
      }
      if ($tot > 0 && $hit > 0) $score += 0.6 * ($hit / $tot);
    }
  }
  return $score;
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

/* ---------- 完全バックアップ（手動エクスポート／自動バックアップ 共通） ---------- */
// 色・文字サイズ・入力欄（本文HTML）・ユーザー権限（パスワードハッシュ含む）・
// 在庫・交通費・設定を、無加工でそのまま書き出す。
function cbc_build_backup_array($pdo) {
  $nodes    = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
  $users    = $pdo->query('SELECT username, display_name, pass_hash, is_admin, allowed, created_at, updated_at FROM users ORDER BY username')->fetchAll();
  $invItems = $pdo->query('SELECT id, name, model, qty, note, sort_order, created_at, updated_at FROM inv_items ORDER BY sort_order, created_at')->fetchAll();
  $invLogs  = $pdo->query('SELECT id, item_id, action, qty, balance, person, note, created_at FROM inv_logs ORDER BY created_at, id')->fetchAll();
  $trips    = array();
  try {
    $trips = $pdo->query('SELECT id, username, display_name, trip_date, case_name, mode, origin, destination, one_way_km, round_trip, gas_rate, gas_cost, fare_cost, toll_cost, parking_cost, other_cost, total, cost_details, note, created_at, updated_at FROM trips ORDER BY trip_date, created_at')->fetchAll();
  } catch (Throwable $e) { /* trips 未作成でも致命ではない */ }
  $settings = $pdo->query('SELECT k, v FROM app_settings')->fetchAll();
  return array(
    'app'         => 'case-by-case',
    'version'     => 1,
    'exported_at' => now_ms(),
    'nodes'       => $nodes,
    'users'       => $users,
    'inv_items'   => $invItems,
    'inv_logs'    => $invLogs,
    'trips'       => $trips,
    'settings'    => $settings,
  );
}

/* ---------- 自動バックアップ（毎日正午をすぎた最初のアクセスで、完全バックアップを backups/ に保存） ---------- */
function cbc_backup_filename($ymd) { return 'backup-' . $ymd . '.json'; }
function cbc_backup_path($ymd) { return rtrim(BACKUP_DIR, '/') . '/' . cbc_backup_filename($ymd); }
// 今日ぶんの自動バックアップがまだ無く、正午（サーバー時刻）を過ぎていれば作成する。
// アクセスのたびに呼ばれる想定のため、通常は file_exists のチェックのみで即戻る（軽量）。
// 失敗してもアプリの動作に影響させない（ベストエフォート）。
function cbc_maybe_auto_backup($pdo) {
  try {
    if ((int)date('G') < 12) return; // 正午（12時）より前は対象外
    $ymd = date('Y-m-d');
    $path = cbc_backup_path($ymd);
    if (file_exists($path)) return;
    if (!is_dir(BACKUP_DIR)) @mkdir(BACKUP_DIR, 0775, true);
    // 複数リクエストがほぼ同時に条件を満たしても二重作成しないよう、簡易ロックする
    $lockPath = rtrim(BACKUP_DIR, '/') . '/.lock';
    $fh = @fopen($lockPath, 'c');
    if (!$fh) return;
    if (!flock($fh, LOCK_EX | LOCK_NB)) { fclose($fh); return; }
    try {
      if (!file_exists($path)) {
        $data = cbc_build_backup_array($pdo);
        $data['auto'] = true;
        @file_put_contents($path, json_encode($data, JSON_UNESCAPED_UNICODE), LOCK_EX);
      }
    } catch (Throwable $e) {
    }
    flock($fh, LOCK_UN);
    fclose($fh);
  } catch (Throwable $e) { /* 自動バックアップの失敗はアプリ動作に影響させない */ }
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
      CURLOPT_CONNECTTIMEOUT => 10,
      CURLOPT_TIMEOUT => 40, // レンタルサーバーからの回線がやや遅くても、生成完了まで待てるように余裕を持たせる
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
    'content' => $bodyJson, 'timeout' => 40, 'ignore_errors' => true,
  )));
  $resp = @file_get_contents($url, false, $ctx);
  if ($resp === false) return array(0, '', 'サーバーの外部通信設定をご確認ください');
  $code = 200;
  if (isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m)) $code = (int)$m[1];
  return array($code, $resp, '');
}

/* Google側が一時的に混んでいる（順番待ち）状態か。
   この場合はしばらく待てば通ることが多いので、エラーにせず数回だけ試し直す。
   例：503 UNAVAILABLE / "The model is overloaded" / "currently experiencing high demand" */
function cbc_gemini_is_busy($code, $resp) {
  if ((int)$code === 503 || (int)$code === 500) return true;
  return (bool)preg_match('/high demand|overloaded|UNAVAILABLE|try again later|temporarily/i', (string)$resp);
}

/* Google Gemini 呼び出しの本体（失敗しても exit しない版）。$jsonMode=true でJSON応答を要求。
   モデル名の違い（廃止・改名）を吸収するため、候補モデルを順に試し、成功したモデルを記憶する。
   戻り値: array($text, $errMsg, $httpCodeForFail)
     成功時: $text=応答文字列, $errMsg=null
     失敗時: $text=null, $errMsg=エラー文言（ヒント込み）, $httpCodeForFail=呼び出し側がfail()に渡すべきHTTPコード */
function cbc_gemini_soft($pdo, $prompt, $jsonMode = false, $maxTokens = 1024) {
  if (GEMINI_API_KEY === '') return array(null, 'AI機能が未設定です（config.php に GEMINI_API_KEY を設定してください）', 400);
  $payload = array(
    'contents' => array(array('parts' => array(array('text' => $prompt)))),
    'generationConfig' => array('temperature' => 0.2, 'maxOutputTokens' => (int)$maxTokens),
  );
  if ($jsonMode) $payload['generationConfig']['responseMimeType'] = 'application/json';
  $bodyJson = json_encode($payload, JSON_UNESCAPED_UNICODE);

  // 候補モデル: 前回成功したもの → config指定 → 現行の既定候補（重複除去）。
  // 「latest」系のエイリアスも入れておくと、個別モデルが廃止されても自動で追従しやすい。
  $candidates = array();
  foreach (array(cbc_setting_get($pdo, 'gemini_model_ok', ''), GEMINI_MODEL,
    'gemini-2.5-flash', 'gemini-flash-latest', 'gemini-2.0-flash', 'gemini-1.5-flash') as $m) {
    $m = trim((string)$m);
    if ($m !== '' && !in_array($m, $candidates, true)) $candidates[] = $m;
  }

  $lastMsg = '';
  foreach ($candidates as $model) {
    // 一時的な失敗（通信断、Google側の混雑）は待てば直ることが多いので、
    // 同じモデルで少し待ちながら数回試してから諦める
    // （モデル名を変えても同じ通信経路・同じ混雑に当たるため、次の候補に移っても解決しないことが多い）。
    $neterr = '';
    $code = 0; $resp = '';
    for ($attempt = 0; $attempt < 3; $attempt++) {
      if ($attempt > 0) usleep(700000 * $attempt); // 0.7秒 → 1.4秒 と待ち time を延ばす
      list($code, $resp, $neterr) = cbc_gemini_call($model, $bodyJson);
      if ($neterr !== '') continue;                       // 通信断：もう一度
      if (cbc_gemini_is_busy($code, $resp)) continue;     // Google側が混雑：もう一度
      break;
    }
    if ($neterr !== '') {
      // 2回試しても通信できなかった場合。一時的な混雑のこともあるが、繰り返す場合はサーバー側の
      // 外部通信（サーバーからGoogleのAPIへのHTTPS通信）が制限されている可能性がある。
      $hint = "\n【対処】再試行しても解消しない場合は、サーバーから外部（generativelanguage.googleapis.com）への"
        . "HTTPS通信がファイアウォール等でブロックされていないか、レンタルサーバーの管理画面や"
        . "サポートにご確認ください。";
      return array(null, 'AIサーバーに接続できません: ' . $neterr . $hint, 502);
    }
    $data = json_decode($resp, true);
    if ($code >= 200 && $code < 300 && is_array($data)) {
      cbc_setting_set($pdo, 'gemini_model_ok', $model); // 使えたモデルを記憶（次回から直接）
      $text = '';
      if (isset($data['candidates'][0]['content']['parts']) && is_array($data['candidates'][0]['content']['parts'])) {
        foreach ($data['candidates'][0]['content']['parts'] as $p) { if (isset($p['text'])) $text .= $p['text']; }
      }
      return array($text, null, 200);
    }
    $lastMsg = (is_array($data) && isset($data['error']['message'])) ? $data['error']['message'] : ('HTTP ' . $code);
    // 無料枠は「モデルごと」に別々の上限を持つ（例: gemini-2.5-flash と gemini-flash-latest が
    // 実際に解決される先のモデルとでは、1日の上限がまったく違うことがある）。そのため、
    // あるモデルで無料枠を使い切っていても、次の候補モデルはまだ余裕があることが多い。
    // ここで諦めずに次の候補へ進む（キー不正・権限エラーはモデルによらず失敗するので、そちらは即終了）。
    $isQuota = preg_match('/quota|billing|free_tier|limit:\s*0|RESOURCE_EXHAUSTED/i', $lastMsg);
    if ($isQuota) { continue; }
    // モデルが無い/未対応/廃止のときも次の候補へ。それ以外（キー不正・権限等）は即エラー。
    // Googleのモデル廃止メッセージは "not found" 系だけでなく "is no longer available" 系も来るため、両方を拾う。
    if (!preg_match('/not found|not supported|unknown|does not exist|unsupported|no longer available|deprecated|has been removed|is retired/i', $lastMsg)) {
      // 数回試しても混雑が解消しなかった場合は、利用者に分かる言葉で伝える（設定の問題ではないため）
      if (cbc_gemini_is_busy($code, $resp)) {
        return array(null, 'Google側のAIが混み合っています。少し時間をおいて、もう一度お試しください。', 503);
      }
      $hint = '';
      if (preg_match('/API key not valid|API_KEY_INVALID|PERMISSION_DENIED|permission/i', $lastMsg)) {
        $hint = "\n【対処】APIキーが正しくないか権限がありません。config.php の GEMINI_API_KEY を再確認してください。";
      }
      return array(null, 'AI呼び出しに失敗しました: ' . $lastMsg . $hint, ($code === 429 ? 429 : 502));
    }
  }
  // 全ての候補モデルで無料枠を使い切っていた場合だけ、ここに来る（各モデルとも上限に達した）。
  if (preg_match('/quota|billing|free_tier|limit:\s*0|RESOURCE_EXHAUSTED/i', $lastMsg)) {
    $hint = "\n【対処】お試しいただいたモデルはすべて本日の無料枠の上限に達しています。"
      . "しばらく（日付が変わる頃まで）お待ちいただくか、Google AI Studio の「課金」で"
      . "プロジェクトにお支払い情報を設定すると解消します（Flashは低額）。";
    return array(null, 'AI呼び出しに失敗しました: ' . $lastMsg . $hint, 429);
  }
  return array(null, '利用可能なGeminiモデルが見つかりませんでした。config.php の GEMINI_MODEL をご確認ください（例: gemini-2.5-flash）。詳細: ' . $lastMsg, 502);
}
/* ===== 埋め込み（Embedding）による意味検索 =====
   検索のたびに全項目をAIへ送って選ばせる方式は、応答が遅く（生成は秒単位）、
   生成APIの少ない無料枠も消費してしまう。そこで、
     1) 各項目の文章を「数値ベクトル」に変換して1度だけDBに保存しておく（索引）
     2) 検索時は「質問文」だけをベクトル化し、サーバー内の計算（内積）で近い項目を選ぶ
   という方式にする。2)のAI呼び出しは短文1件だけなので速く、埋め込みAPIは生成APIより
   利用枠がはるかに大きい。文章が変わった項目だけ作り直せばよい。 */

/* 埋め込みAPIを1回呼ぶ。戻り値: array(httpCode, responseBody, networkError) */
function cbc_embed_call($model, $method, $bodyJson) {
  $url = 'https://generativelanguage.googleapis.com/v1beta/models/' . rawurlencode($model) . ':' . $method . '?key=' . urlencode(GEMINI_API_KEY);
  if (function_exists('curl_init')) {
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
      CURLOPT_POST => true,
      CURLOPT_HTTPHEADER => array('Content-Type: application/json'),
      CURLOPT_POSTFIELDS => $bodyJson,
      CURLOPT_RETURNTRANSFER => true,
      CURLOPT_CONNECTTIMEOUT => 10,
      CURLOPT_TIMEOUT => 40,
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
    'content' => $bodyJson, 'timeout' => 40, 'ignore_errors' => true,
  )));
  $resp = @file_get_contents($url, false, $ctx);
  if ($resp === false) return array(0, '', 'サーバーの外部通信設定をご確認ください');
  $code = 200;
  if (isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m)) $code = (int)$m[1];
  return array($code, $resp, '');
}

/* 複数のテキストをまとめてベクトル化する（batchEmbedContents は1回で最大100件）。
   $taskType: 'RETRIEVAL_DOCUMENT'（登録側）/ 'RETRIEVAL_QUERY'（検索する質問側）。
   埋め込みは用途を伝えると精度が上がるため、登録時と検索時で使い分ける。
   戻り値: array($vectors, $errMsg) — $vectors は $texts と同じ並びの float配列の配列 */
function cbc_embed_texts($pdo, $texts, $taskType) {
  if (GEMINI_API_KEY === '') return array(null, 'AI機能が未設定です（config.php に GEMINI_API_KEY を設定してください）');
  $texts = array_values($texts);
  if (!$texts) return array(array(), null);
  // 前回成功したモデルを優先。モデル名が変わっても次の候補で自動的に追従する。
  $candidates = array();
  foreach (array(cbc_setting_get($pdo, 'gemini_embed_ok', ''), 'gemini-embedding-001', 'text-embedding-004', 'embedding-001') as $m) {
    $m = trim((string)$m);
    if ($m !== '' && !in_array($m, $candidates, true)) $candidates[] = $m;
  }
  $lastMsg = '';
  foreach ($candidates as $model) {
    $reqs = array();
    foreach ($texts as $t) {
      $reqs[] = array(
        'model' => 'models/' . $model,
        'content' => array('parts' => array(array('text' => $t))),
        'taskType' => $taskType,
      );
    }
    $bodyJson = json_encode(array('requests' => $reqs), JSON_UNESCAPED_UNICODE);
    $neterr = '';
    $code = 0; $resp = '';
    // 通信断・Google側の混雑は待てば直ることが多いので、少し待ちながら数回試す
    for ($attempt = 0; $attempt < 3; $attempt++) {
      if ($attempt > 0) usleep(700000 * $attempt);
      list($code, $resp, $neterr) = cbc_embed_call($model, 'batchEmbedContents', $bodyJson);
      if ($neterr !== '') continue;
      if (cbc_gemini_is_busy($code, $resp)) continue;
      break;
    }
    if ($neterr !== '') return array(null, 'AIサーバーに接続できません: ' . $neterr);
    if (cbc_gemini_is_busy($code, $resp)) return array(null, 'Google側のAIが混み合っています。少し時間をおいて、もう一度お試しください。');
    $data = json_decode($resp, true);
    if ($code >= 200 && $code < 300 && isset($data['embeddings']) && is_array($data['embeddings'])) {
      $out = array();
      foreach ($data['embeddings'] as $e) {
        $out[] = (isset($e['values']) && is_array($e['values'])) ? $e['values'] : array();
      }
      if (count($out) !== count($texts)) return array(null, '埋め込みの件数が一致しませんでした');
      cbc_setting_set($pdo, 'gemini_embed_ok', $model);
      return array($out, null);
    }
    $lastMsg = (is_array($data) && isset($data['error']['message'])) ? $data['error']['message'] : ('HTTP ' . $code);
    // 無料枠はモデルごとに別々の上限を持つため、あるモデルで使い切っていても次の候補は
    // まだ余裕があることが多い。生成側（cbc_gemini_soft）と同じ考え方で、諦めずに次へ進む。
    if (preg_match('/quota|billing|free_tier|limit:\s*0|RESOURCE_EXHAUSTED/i', $lastMsg)) { continue; }
    // モデルが無い/廃止のときも次の候補へ。それ以外（キー不正・権限）は即エラー。
    if (!preg_match('/not found|not supported|unknown|does not exist|unsupported|no longer available|deprecated|has been removed|is retired/i', $lastMsg)) {
      $hint = '';
      if (preg_match('/API key not valid|API_KEY_INVALID|PERMISSION_DENIED|permission/i', $lastMsg)) {
        $hint = "\n【対処】APIキーが正しくないか権限がありません。config.php の GEMINI_API_KEY を再確認してください。";
      }
      return array(null, '索引の作成に失敗しました: ' . $lastMsg . $hint);
    }
  }
  if (preg_match('/quota|billing|free_tier|limit:\s*0|RESOURCE_EXHAUSTED/i', $lastMsg)) {
    return array(null, '索引の作成に失敗しました: ' . $lastMsg
      . "\n【対処】お試しいただいたモデルはすべて本日の無料枠の上限に達しています。しばらく待つか、"
      . "Google AI Studio の「課金」でお支払い情報を設定してください。");
  }
  return array(null, '利用可能な埋め込みモデルが見つかりませんでした。詳細: ' . $lastMsg);
}

/* ベクトルを保存用の文字列に変換する。float32でパックしてbase64にすると、
   JSONで持つより小さく、読み書きも速い（768次元で約4KB）。 */
function cbc_vec_pack($vec) {
  $s = '';
  foreach ($vec as $f) $s .= pack('g', (float)$f); // 'g' = little-endian float32
  return base64_encode($s);
}
function cbc_vec_unpack($packed) {
  $bin = base64_decode((string)$packed, true);
  if ($bin === false || $bin === '') return array();
  $a = unpack('g*', $bin);
  return $a === false ? array() : array_values($a);
}
/* あらかじめ長さ1に正規化しておくと、類似度が単なる内積で求まり、検索時の計算が軽くなる */
function cbc_vec_normalize($vec) {
  $sum = 0.0;
  foreach ($vec as $f) $sum += $f * $f;
  if ($sum <= 0) return $vec;
  $inv = 1.0 / sqrt($sum);
  $out = array();
  foreach ($vec as $f) $out[] = $f * $inv;
  return $out;
}
/* 正規化済みベクトル同士の内積（＝コサイン類似度。-1〜1、大きいほど内容が近い） */
function cbc_vec_dot($a, $b) {
  $n = min(count($a), count($b));
  $s = 0.0;
  for ($i = 0; $i < $n; $i++) $s += $a[$i] * $b[$i];
  return $s;
}

/* 質問文のベクトルを使い回す。同じ質問（前後の空白と大文字小文字の違いは無視）で再検索したときは
   Googleへの問い合わせを丸ごと省けるので、その1回分の通信時間が無くなる。
   戻り値: 正規化済みベクトル、または null */
function cbc_qvec_get($pdo, $query) {
  try {
    $st = $pdo->prepare('SELECT vec FROM query_vectors WHERE q_hash = ?');
    $st->execute(array(hash('sha256', mb_strtolower(trim($query)))));
    $v = $st->fetchColumn();
    if ($v === false || $v === null || $v === '') return null;
    $arr = cbc_vec_unpack($v);
    return $arr ? $arr : null;
  } catch (Throwable $e) { return null; }
}
function cbc_qvec_put($pdo, $query, $vec) {
  try {
    $h = hash('sha256', mb_strtolower(trim($query)));
    $pdo->prepare('DELETE FROM query_vectors WHERE q_hash = ?')->execute(array($h));
    $pdo->prepare('INSERT INTO query_vectors (q_hash, vec, created_at) VALUES (?,?,?)')
        ->execute(array($h, cbc_vec_pack($vec), now_ms()));
    // 増えすぎないよう、古いものから間引く（たまにだけ実行して普段は負荷をかけない）
    if (mt_rand(1, 20) === 1) {
      $cnt = (int)$pdo->query('SELECT COUNT(*) FROM query_vectors')->fetchColumn();
      if ($cnt > 500) {
        $keep = $pdo->query('SELECT created_at FROM query_vectors ORDER BY created_at DESC LIMIT 1 OFFSET 300')->fetchColumn();
        if ($keep !== false) $pdo->prepare('DELETE FROM query_vectors WHERE created_at < ?')->execute(array((int)$keep));
      }
    }
  } catch (Throwable $e) { /* 使い回しは高速化のためのものなので、失敗しても検索は続行する */ }
}

/* 索引に入れる文章。見出しの階層も含めると「どの文脈の項目か」が反映され、精度が上がる。 */
function cbc_index_text($pathTitles, $body) {
  $head = implode(' > ', $pathTitles);
  $plain = cbc_html_to_plain($body);
  $plain = trim(preg_replace('/\s+/u', ' ', $plain));
  return mb_substr($head . "\n" . $plain, 0, 3000);
}
function cbc_index_hash($text) { return hash('sha256', $text); }

/* 見出しパス（TOPからの階層）を組み立てる。本文は使わないので、検索時は本文を読み込まなくてよい。 */
function cbc_path_titles($rows) {
  $byId = array();
  foreach ($rows as $r) $byId[$r['id']] = $r;
  $pathTitles = array();
  foreach ($rows as $r) {
    $path = array(); $cur = $r; $guard = 0;
    while ($cur && $guard++ < 20) {
      array_unshift($path, $cur['title']);
      $pid = $cur['parent_id'];
      $cur = ($pid !== null && $pid !== '' && isset($byId[$pid])) ? $byId[$pid] : null;
    }
    $pathTitles[$r['id']] = $path;
  }
  return $pathTitles;
}

/* 索引の作り直しが必要な項目のID一覧。
   項目を保存・削除・復元したときに、その項目の索引を捨てる作りにしてあるため、
   「索引が無い＝作り直しが必要」と単純に判定できる（本文の読み込みもハッシュ計算も不要）。
   検索のたびに全項目の本文を処理する必要がなくなり、項目数が増えても検索が重くならない。 */
function cbc_index_stale_ids($pdo) {
  try {
    $st = $pdo->query('SELECT n.id FROM nodes n LEFT JOIN node_vectors v ON v.node_id = n.id WHERE v.node_id IS NULL');
    $out = array();
    foreach ($st as $r) $out[] = $r['id'];
    return $out;
  } catch (Throwable $e) {
    // 索引テーブルがまだ無い等。全件を対象として扱う。
    $out = array();
    foreach ($pdo->query('SELECT id FROM nodes') as $r) $out[] = $r['id'];
    return $out;
  }
}

/* 指定した項目の索引を作り直す（まとめて最大 $limit 件）。戻り値: array($done, $errMsg) */
function cbc_index_build($pdo, $rows, $pathTitles, $ids, $limit = 50) {
  $ids = array_slice(array_values($ids), 0, $limit);
  if (!$ids) return array(0, null);
  $byId = array();
  foreach ($rows as $r) $byId[$r['id']] = $r;
  $texts = array(); $useIds = array();
  foreach ($ids as $id) {
    if (!isset($byId[$id])) continue;
    $texts[] = cbc_index_text($pathTitles[$id], $byId[$id]['body']);
    $useIds[] = $id;
  }
  if (!$texts) return array(0, null);
  list($vecs, $err) = cbc_embed_texts($pdo, $texts, 'RETRIEVAL_DOCUMENT');
  if ($err !== null) return array(0, $err);
  $now = now_ms();
  $del = $pdo->prepare('DELETE FROM node_vectors WHERE node_id = ?');
  // 整形済みテキストも一緒に保存しておく。検索時の語句一致はこれを使うので、
  // 毎回 本文のHTML除去をやり直さずに済む。
  $ins = $pdo->prepare('INSERT INTO node_vectors (node_id, text_hash, dim, vec, text, updated_at) VALUES (?,?,?,?,?,?)');
  $done = 0;
  foreach ($useIds as $i => $id) {
    if (!isset($vecs[$i]) || !$vecs[$i]) continue;
    $v = cbc_vec_normalize($vecs[$i]); // 保存時に正規化しておき、検索時は内積だけで済ませる
    $del->execute(array($id));
    $ins->execute(array($id, cbc_index_hash($texts[$i]), count($v), cbc_vec_pack($v), $texts[$i], $now));
    $done++;
  }
  return array($done, null);
}

/* 質問文に関連する項目を探す（意味の近さ＋語句一致のハイブリッド）。
   「AIで探す」と「AIに相談（会話）」の両方から使う共通処理。
   戻り値: array('results'=>..., 'mode'=>'semantic|keyword|empty', 'need_index'=>bool, 'embed_error'=>?string) */
function cbc_retrieve($pdo, $s, $query, $maxOut = null) {
  // 本文（body）はここでは読み込まない。項目数が多いと本文の総量が数百KBになり、
  // 取得とHTML除去だけで時間を使ってしまうため。見出しの組み立てに必要な列だけを読む。
  $rows = $pdo->query('SELECT id, parent_id, title, lock_hash FROM nodes')->fetchAll();
  if (!$rows) return array('results' => array(), 'mode' => 'empty', 'need_index' => false, 'embed_error' => null);
  $pathTitles = cbc_path_titles($rows);
  // 権限で見えない項目は最初から除外する
  $visible = $s['is_admin'] ? $rows : cbc_filter_allowed($rows, cbc_user_allowed($pdo, $s['username']));
  if (!$visible) return array('results' => array(), 'mode' => 'empty', 'need_index' => false, 'embed_error' => null);
  $lockById = array();
  foreach ($rows as $r) $lockById[$r['id']] = $r['lock_hash'];

  $stale = cbc_index_stale_ids($pdo);
  // 編集直後など、古くなった索引がわずかならこの場で作り直す（通常は0〜1件なので体感に影響しない）。
  // 大量に未作成のとき（初回導入時など）はここでは作らず、キーワード検索で結果を出しつつ
  // 「索引を作ってください」と伝える。検索を待たせないための割り切り。
  $needIndex = false;
  if ($stale) {
    if (count($stale) <= 20) {
      $full = $pdo->query('SELECT id, parent_id, title, body FROM nodes')->fetchAll();
      list($bd, $berr) = cbc_index_build($pdo, $full, cbc_path_titles($full), $stale, 20);
      if ($berr !== null) $needIndex = true;
    } else {
      $needIndex = true;
    }
  }

  // 照合に使う本文は、索引を作ったときの整形済みテキストを使い回す（HTML除去をやり直さない）。
  // 索引がまだ無い項目のぶんだけ、本文を読んで補う。
  $plainById = array();
  try {
    foreach ($pdo->query('SELECT node_id, text FROM node_vectors') as $tv) $plainById[$tv['node_id']] = $tv['text'];
  } catch (Throwable $e) { $plainById = array(); }
  $missing = array();
  foreach ($visible as $r) { if (!isset($plainById[$r['id']])) $missing[] = $r['id']; }
  if ($missing) {
    $chunk = array_slice($missing, 0, 500);
    $ph = implode(',', array_fill(0, count($chunk), '?'));
    $q2 = $pdo->prepare("SELECT id, body FROM nodes WHERE id IN ($ph)");
    $q2->execute($chunk);
    $fetched = array();
    foreach ($q2 as $mr) { $plainById[$mr['id']] = cbc_html_to_plain($mr['body']); $fetched[] = $mr['id']; }
    // 以前のバージョンで作られた索引には整形済みテキストが入っていない。作り直し（再ベクトル化）は
    // 不要なので、ここで読んだ内容だけを書き戻しておく。次回以降は本文を読まずに済む。
    try {
      $bf = $pdo->prepare('UPDATE node_vectors SET text = ? WHERE node_id = ? AND text IS NULL');
      foreach ($fetched as $fid) $bf->execute(array($plainById[$fid], $fid));
    } catch (Throwable $e) { /* 補完できなくても検索は成立する */ }
  }

  // キーワードのスコア（0〜1に正規化）。ここはAI不要で一瞬。
  $terms = cbc_search_terms($query);
  $kw = array();
  $kwMax = 0;
  foreach ($visible as $r) {
    $head = implode(' ', $pathTitles[$r['id']]);
    $headLow = mb_strtolower($head);
    $body = isset($plainById[$r['id']]) ? $plainById[$r['id']] : '';
    $sc = cbc_keyword_score($terms, mb_strtolower($head . ' ' . $body));
    // 見出しに含まれる語は本文より重みを大きくする（タイトル一致は意図に近いことが多い）
    $sc += cbc_keyword_score($terms, $headLow);
    $kw[$r['id']] = $sc;
    if ($sc > $kwMax) $kwMax = $sc;
  }

  // 意味の近さ（ベクトルの内積）。索引と質問の両方がそろったときだけ使う。
  $sim = array();
  $mode = 'keyword';
  $embedErr = null;
  if (!$needIndex) {
    // 同じ質問を一度でも検索していれば、AIへの問い合わせを省いてそのぶん速くなる
    $qv = cbc_qvec_get($pdo, $query);
    $qerr = null;
    if ($qv === null) {
      list($qvecs, $qerr) = cbc_embed_texts($pdo, array($query), 'RETRIEVAL_QUERY');
      if ($qerr === null && !empty($qvecs[0])) {
        $qv = cbc_vec_normalize($qvecs[0]);
        cbc_qvec_put($pdo, $query, $qv);
      }
    }
    if ($qv !== null) {
      $st = $pdo->query('SELECT node_id, vec FROM node_vectors');
      foreach ($st as $v) {
        if (!isset($kw[$v['node_id']])) continue; // 権限外・削除済み
        $sim[$v['node_id']] = cbc_vec_dot($qv, cbc_vec_unpack($v['vec']));
      }
      if ($sim) $mode = 'semantic';
    } else {
      $embedErr = $qerr;
    }
  }

  // 類似度の絶対値はモデルによって尺度が違う（0.8前後に固まるモデルもあれば、0.3台のモデルもある）。
  // 固定のしきい値だと「全部通る」か「全部落ちる」になりやすいため、
  // 一番近い項目を基準にした相対的な近さで足切りする（最低ラインだけ絶対値で押さえる）。
  // ここは「一番近い項目から SIM_MARGIN 以内なら関連あり」と見なす、という1つの定数で決まる。
  // 実際の項目で緩い／厳しいと感じたら、この値だけを調整すればよい（大きくすると候補が増える）。
  $SIM_MARGIN = 0.10;
  $bestSim = null;
  foreach ($sim as $v) { if ($bestSim === null || $v > $bestSim) $bestSim = $v; }
  $simCut = ($bestSim === null) ? null : max(0.15, $bestSim - $SIM_MARGIN);

  // ハイブリッドの合成スコア。意味の近さを主、キーワード一致を補助にする。
  // 意味検索が使えないときはキーワードだけで並べる（＝必ず何かしら結果が出る）。
  $scored = array();
  foreach ($visible as $r) {
    $id = $r['id'];
    $sv = isset($sim[$id]) ? $sim[$id] : null;
    $kvn = $kwMax > 0 ? ($kw[$id] / $kwMax) : 0;
    $semOk = ($sv !== null && $simCut !== null && $sv >= $simCut);
    if ($mode === 'semantic') {
      // 意味的にも語句的にも当たらないものは落とす（無関係な項目を並べない）
      if (!$semOk && $kw[$id] <= 0) continue;
      $score = ($sv === null ? 0 : $sv) + 0.25 * $kvn;
    } else {
      if ($kw[$id] <= 0) continue;
      $score = $kvn;
    }
    $scored[] = array('id' => $id, 'score' => $score, 'sim' => $sv, 'kw' => $kw[$id], 'sem' => $semOk);
  }
  usort($scored, function ($a, $b) { return ($b['score'] < $a['score']) ? -1 : (($b['score'] > $a['score']) ? 1 : 0); });

  // 意味検索が効いているときは、近いものだけを絞って出す（多く並べるほど、どれを見ればよいか迷うため）。
  // 語句一致だけのときは取りこぼしを避けたいので、やや多めに出す。
  $cap = ($maxOut !== null) ? $maxOut : (($mode === 'semantic') ? 5 : 8);
  $results = array();
  foreach (array_slice($scored, 0, $cap) as $sc) {
    $id = $sc['id'];
    // 何で当たったのかが分かるよう、理由を短く添える（AIに書かせないので追加コストは無い）
    if ($sc['sem'] && $sc['kw'] > 0) $reason = '内容が近く、語句も一致しています';
    elseif ($sc['sem']) $reason = '質問と内容が意味的に近い項目です';
    elseif ($sc['kw'] > 0) $reason = '語句が一致しています';
    else $reason = '';
    $results[] = array(
      'id' => $id,
      'reason' => $reason,
      'title' => $pathTitles[$id][count($pathTitles[$id]) - 1],
      'path' => $pathTitles[$id],
      'locked' => !empty($lockById[$id]),
      'source' => $sc['sem'] ? 'ai' : 'keyword',
      'score' => round($sc['score'], 4),
    );
  }
  return array('results' => $results, 'mode' => $mode, 'need_index' => $needIndex, 'embed_error' => $embedErr);
}

/* cbc_gemini_soft のラッパー。失敗したら即エラー応答して終了する（要約・検索の主機能で使用）。 */
function cbc_gemini($pdo, $prompt, $jsonMode = false, $maxTokens = 1024) {
  list($text, $err, $code) = cbc_gemini_soft($pdo, $prompt, $jsonMode, $maxTokens);
  if ($err !== null) fail($err, $code);
  return $text;
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
    // 毎日正午をすぎた最初のアクセスで、完全バックアップを自動作成する（通常は file_exists のみで即戻る軽量チェック）
    try { cbc_maybe_auto_backup(cbc_pdo()); } catch (Throwable $e) {}
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
    // 本文が変わった可能性があるので、この項目のAI検索用の索引を捨てる。
    // 「索引が無い＝作り直しが必要」と扱えるようになり、検索のたびに全項目の本文を
    // 読み直してハッシュ比較する必要がなくなる（項目数が増えても検索が重くならない）。
    try { $pdo->prepare('DELETE FROM node_vectors WHERE node_id = ?')->execute(array($d['id'])); } catch (Throwable $e) {}
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
    // AI検索の索引も一緒に片付ける（消した項目が検索に残らないように）
    try {
      $dv = $pdo->prepare('DELETE FROM node_vectors WHERE node_id = ?');
      foreach ($toDelete as $id) $dv->execute(array($id));
    } catch (Throwable $e) { /* 索引の掃除に失敗しても削除自体は成功扱い */ }
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
    // 中身が総入れ替えになるので、AI検索の索引も破棄する（作り直しの対象になる）
    try { $pdo->exec('DELETE FROM node_vectors'); } catch (Throwable $e) {}
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
    // 指定項目の本文を AI で要約。通信の再試行を待てるよう、PHP側の実行時間上限も一時的に緩める。
    if (function_exists('set_time_limit')) @set_time_limit(120);
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

  case 'ai_index_status': {
    // 索引（意味検索用のベクトル）の作成状況。修正画面に進捗を出すために使う。
    $s = require_login($pdo);
    if (!$s['is_admin']) fail('管理者のみ実行できます', 403);
    $total = (int)$pdo->query('SELECT COUNT(*) FROM nodes')->fetchColumn();
    $stale = cbc_index_stale_ids($pdo);
    ok(array('total' => $total, 'stale' => count($stale), 'indexed' => $total - count($stale)));
  }

  case 'ai_index_build': {
    // 索引をまとめて作る。1回のリクエストで作りすぎるとタイムアウトするため、少しずつ進めて
    // 残り件数を返し、クライアント側が終わるまで繰り返し呼ぶ（進捗バーを出せる）。
    if (function_exists('set_time_limit')) @set_time_limit(120);
    $s = require_login($pdo);
    if (!$s['is_admin']) fail('管理者のみ実行できます', 403);
    $d = body_json();
    $limit = isset($d['limit']) ? max(1, min(100, (int)$d['limit'])) : 50;
    $stale = cbc_index_stale_ids($pdo);
    $total = (int)$pdo->query('SELECT COUNT(*) FROM nodes')->fetchColumn();
    if (!$stale) ok(array('done' => 0, 'remaining' => 0, 'total' => $total));
    // 作り直す項目の本文だけを読み込む（全件の本文を読む必要はない）
    $rows = $pdo->query('SELECT id, parent_id, title, body FROM nodes')->fetchAll();
    $pathTitles = cbc_path_titles($rows);
    list($done, $err) = cbc_index_build($pdo, $rows, $pathTitles, $stale, $limit);
    if ($err !== null) fail($err, 502);
    ok(array('done' => $done, 'remaining' => max(0, count($stale) - $done), 'total' => $total));
  }

  case 'ai_search': {
    // 意味で探す検索。実際の探索は cbc_retrieve() が行う（「AIに相談」と共通）。
    // 生成AIは使わないので、索引さえできていれば一瞬で返る。
    if (function_exists('set_time_limit')) @set_time_limit(60);
    $s = require_login($pdo);
    $d = body_json();
    $query = isset($d['q']) ? trim($d['q']) : '';
    if ($query === '') fail('検索語が必要です');
    $r = cbc_retrieve($pdo, $s, $query);
    ok(array(
      'results' => $r['results'],
      'mode' => $r['mode'],             // semantic = 意味検索が効いている / keyword = 語句一致のみ
      'need_index' => $r['need_index'], // true なら「索引を作ってください」の案内を出す
      'embed_error' => $r['embed_error'],
    ));
  }

  case 'ai_chat': {
    // 「AIに相談」：会話でやりたいことを伝えると、登録済みの項目の内容だけを根拠に
    // 簡易手順にまとめて返す。会話の流れを保つため、直前までのやり取りも一緒に受け取る。
    //   1) 会話から「今、何を探すべきか」を組み立てて関連項目を取り出す（cbc_retrieve・生成AIは使わない）
    //   2) 取り出した項目の本文と会話履歴を渡して、手順を書いてもらう（生成AIはここだけ）
    // 対象を絞れないときは、手順の代わりに選択肢つきで聞き返す。
    if (function_exists('set_time_limit')) @set_time_limit(90);
    $s = require_login($pdo);
    $d = body_json();
    $msgs = (isset($d['messages']) && is_array($d['messages'])) ? $d['messages'] : array();
    // 直近のやり取りだけを使う（長くなるほど遅く・高くなるため）
    $msgs = array_slice($msgs, -8);
    $clean = array();
    foreach ($msgs as $m) {
      if (!is_array($m)) continue;
      $role = (isset($m['role']) && $m['role'] === 'assistant') ? 'assistant' : 'user';
      $text = isset($m['text']) ? trim((string)$m['text']) : '';
      if ($text === '') continue;
      $clean[] = array('role' => $role, 'text' => mb_substr($text, 0, 2000));
    }
    if (!$clean) fail('相談内容を入力してください');
    $lastUser = '';
    for ($i = count($clean) - 1; $i >= 0; $i--) { if ($clean[$i]['role'] === 'user') { $lastUser = $clean[$i]['text']; break; } }
    if ($lastUser === '') fail('相談内容を入力してください');

    // 検索に使う文章：最新の要望を主にしつつ、それまでの要望も足して文脈を保つ
    // （「じゃあ次は？」のような短い追加質問でも、話の流れから探せるようにする）。
    $userTexts = array();
    foreach ($clean as $m) { if ($m['role'] === 'user') $userTexts[] = $m['text']; }
    $prevUsers = array_slice($userTexts, 0, -1);
    $retrieveQuery = $lastUser;
    if ($prevUsers) $retrieveQuery = implode(' ', array_slice($prevUsers, -2)) . ' ' . $lastUser;
    $retrieveQuery = mb_substr($retrieveQuery, 0, 500);

    $r = cbc_retrieve($pdo, $s, $retrieveQuery, 4);
    $results = $r['results'];
    if (!$results) {
      ok(array(
        'answer' => null, 'ask' => null, 'sources' => array(),
        'mode' => $r['mode'], 'need_index' => $r['need_index'],
        'empty' => true,
      ));
    }

    // 見つかった項目の本文を読み込む（会話の根拠として渡す）
    $ids = array();
    foreach ($results as $x) $ids[] = $x['id'];
    $ph = implode(',', array_fill(0, count($ids), '?'));
    $st = $pdo->prepare("SELECT id, body FROM nodes WHERE id IN ($ph)");
    $st->execute($ids);
    $bodyById = array();
    foreach ($st as $b) $bodyById[$b['id']] = $b['body'];

    $secLines = array();
    foreach ($results as $x) {
      $plain = isset($bodyById[$x['id']]) ? cbc_html_to_plain($bodyById[$x['id']]) : '';
      $plain = mb_substr(trim($plain), 0, 2200);
      if ($plain === '') continue;
      $secLines[] = '【項目名】' . implode(' > ', $x['path']) . "\n【内容】\n" . $plain;
    }
    if (!$secLines) {
      ok(array('answer' => null, 'ask' => null, 'sources' => array(), 'mode' => $r['mode'], 'need_index' => $r['need_index'], 'empty' => true));
    }

    // 会話の流れをそのまま文章にして渡す（AIが「さっきの話」を踏まえて答えられるように）
    $convo = '';
    foreach ($clean as $m) $convo .= ($m['role'] === 'user' ? '利用者: ' : 'アシスタント: ') . $m['text'] . "\n";

    $prompt = "あなたは社内作業マニュアルのアシスタントです。利用者が「こうしたい」と相談してくるので、"
      . "登録済みのマニュアル（下記【関連項目】）の内容だけを根拠に、実際にやるべきことを簡易手順にまとめて答えてください。\n"
      . "出力の書式（プレーンテキスト。JSONやコードブロックにはしないこと）:\n"
      . "・最初に1〜2文で「何をすることになるか」を書く（見出し不要）。重要な語句は **太字** にしてよい。\n"
      . "・続けて「## 手順」という見出しを付け、その下に「1. 」「2. 」…と番号を振った手順を、"
      . "実際に手を動かす順番で、1行1手順で簡潔に書く（各行は80字以内。全体で3〜8手順を目安）。\n"
      . "・注意点がある場合のみ、最後に「## 注意」の見出しを付けて「- 」の箇条書きで書く（多くても3つ）。\n"
      . "・【関連項目】に書かれていないことは、絶対に推測や一般論で補わないでください。"
      . "手順が足りない場合は、分かる範囲だけを書き、足りない部分は「マニュアルに記載がありません」と正直に書いてください。\n"
      . "・すでに会話で答えた内容を繰り返さず、直前の利用者の発言に答えてください。\n"
      . "・（聞き返しではなく）手順を回答する場合は、最後に改行してから必ず次の1行だけを追加してください"
      . "（本文には含めない。他の書式は付けない）：\n"
      . "REPORT: b または REPORT: haku\n"
      . "  【判断基準】作業を行うと、現場で「作業報告書・B」（セイコーソリューションズ）と"
      . "「白伝票（保守サービス報告書・日本リテールシステム）」のどちらか一方を書きます。"
      . "【関連項目】の内容から、てんや・とんでん等のPOS交換のように白伝票を使う案件だと判断できる場合は"
      . "「REPORT: haku」、それ以外・判断できない場合は「REPORT: b」としてください。\n\n"
      . "【例外：聞き返し】対象（機種・状況・作業の種類など）が分からないと手順が変わってしまい、"
      . "どれを案内すべきか決められない場合に限り、手順の代わりに次の形式だけを出力してください（この場合REPORT行は不要）。\n"
      . "ASK: 利用者への確認の質問（50字以内）\n"
      . "- 選択肢1\n"
      . "- 選択肢2\n"
      . "選択肢は2〜4個で、必ず【関連項目】に実在する内容から作ってください。対象が明らかなときは聞き返さないこと。\n\n"
      . "【これまでの会話】\n" . $convo . "\n"
      . "【関連項目】\n" . implode("\n\n", $secLines);

    list($text, $err) = cbc_gemini_soft($pdo, $prompt, false, 1600);
    if ($err !== null) fail($err, 502);
    $text = trim((string)$text);

    // 聞き返しは行頭の「ASK:」で見分ける（JSONに包むと、長くなって途中で切れたとき全部消えるため）
    $ask = null; $answer = null;
    if ($text !== '' && stripos(ltrim($text), 'ASK') === 0 && preg_match('/^ASK[:：]\s*(.+)$/mu', $text, $m)) {
      $question = trim($m[1]);
      $options = array();
      foreach (preg_split('/\R/u', $text) as $line) {
        if (preg_match('/^\s*[-・*]\s*(.+?)\s*$/u', $line, $om)) {
          $o = mb_substr(trim($om[1]), 0, 40);
          if ($o !== '' && !in_array($o, $options, true)) $options[] = $o;
        }
        if (count($options) >= 4) break;
      }
      if ($question !== '' && count($options) >= 2) $ask = array('question' => mb_substr($question, 0, 120), 'options' => $options);
    }
    // どちらの報告書を使う案件か、AIが【関連項目】の内容から判断した結果を取り出す（行末の「REPORT: 」）。
    // 手書きで書く実際の伝票と対応させるだけの軽い判定なので、聞き返し（ASK）のときは無し。
    $reportForm = 'b';
    if ($ask === null && $text !== '' && preg_match('/^\s*REPORT[:：]\s*(b|haku)\s*$/mi', $text, $rm)) {
      $reportForm = (strtolower($rm[1]) === 'haku') ? 'haku' : 'b';
      $text = trim(preg_replace('/^\s*REPORT[:：]\s*(b|haku)\s*$/mi', '', $text));
    }
    if ($ask === null && $text !== '') $answer = $text;

    // 参照した項目（クリックでその項目を開けるようにする）
    $sources = array();
    foreach ($results as $x) {
      $sources[] = array('id' => $x['id'], 'title' => $x['title'], 'path' => $x['path'], 'locked' => $x['locked']);
    }
    ok(array(
      'answer' => $answer,
      'ask' => $ask,
      'sources' => $sources,
      'mode' => $r['mode'],
      'need_index' => $r['need_index'],
      'empty' => false,
      'report_form' => $reportForm, // 'b' または 'haku'（AIが判断した、この案件で使う報告書）
    ));
  }
  case 'ai_search_summary': {
    // 「AIで探す」で見つかった項目（上位いくつか）の内容をもとに、質問への回答をAIがまとめる。
    // ai_search とは別リクエストにすることで、一覧はすぐ表示しつつ、まとめは追いかけて表示できる
    // （体感速度優先）。また、まとめはJSONに埋め込まず素のテキストで生成するため、出力が長くなっても
    // JSON構文が壊れて丸ごと消える（以前の一体型で起きていた不具合）ことがない。失敗しても null を返すだけ。
    if (function_exists('set_time_limit')) @set_time_limit(90);
    $s = require_login($pdo);
    $d = body_json();
    $query = isset($d['q']) ? trim($d['q']) : '';
    $ids = (isset($d['ids']) && is_array($d['ids'])) ? array_values(array_map('strval', $d['ids'])) : array();
    if ($query === '' || !$ids) ok(array('summary' => null));
    $ids = array_slice($ids, 0, 3);
    $ph = implode(',', array_fill(0, count($ids), '?'));
    $st = $pdo->prepare("SELECT id, parent_id, title, body FROM nodes WHERE id IN ($ph)");
    $st->execute($ids);
    $found = $st->fetchAll();
    if (!$s['is_admin']) {
      $all = $pdo->query('SELECT id, parent_id FROM nodes')->fetchAll();
      $vis = cbc_filter_allowed($all, cbc_user_allowed($pdo, $s['username']));
      $visIds = array();
      foreach ($vis as $v) $visIds[$v['id']] = true;
      $found = array_values(array_filter($found, function ($r) use ($visIds) { return isset($visIds[$r['id']]); }));
    }
    if (!$found) ok(array('summary' => null));
    // 見出しパス（TOPからの階層）をたどるための材料
    $allForPath = $pdo->query('SELECT id, parent_id, title FROM nodes')->fetchAll();
    $byIdAll = array();
    foreach ($allForPath as $r) $byIdAll[$r['id']] = $r;
    $byFoundId = array();
    foreach ($found as $r) $byFoundId[$r['id']] = $r;
    $secLines = array();
    foreach ($ids as $id) {
      if (!isset($byFoundId[$id])) continue; // 権限外・削除済みはスキップ
      $r = $byFoundId[$id];
      $path = array(); $cur = isset($byIdAll[$id]) ? $byIdAll[$id] : null; $guard = 0;
      while ($cur && $guard++ < 20) {
        array_unshift($path, $cur['title']);
        $pid = $cur['parent_id'];
        $cur = ($pid !== null && $pid !== '' && isset($byIdAll[$pid])) ? $byIdAll[$pid] : null;
      }
      $full = mb_substr(cbc_html_to_plain($r['body']), 0, 2500);
      $secLines[] = '【項目名】' . implode(' > ', $path) . "\n【内容】\n" . $full;
    }
    if (!$secLines) ok(array('summary' => null));
    $prompt = "あなたは社内作業マニュアルのアシスタントです。利用者は今まさに作業で困っており、今すぐ実際の手順を知りたいと考えています。\n"
      . "下記【関連項目】の内容だけを根拠に、質問に対する実際の手順・対処法を、省略せずできるだけ具体的に日本語でまとめて回答してください"
      . "（400〜700字程度）。\n"
      . "出力は次の書式ルールに従ってください（該当しない項目は無理に使わなくてよい）:\n"
      . "・最初に1〜2文で結論・要点を書く（見出し不要）。重要な語句は **太字** にしてよい。\n"
      . "・手順や注意点が複数ある場合は、1行空けてから「## 見出し」の形で短い見出しを付け、続けて「- 」で始まる箇条書きで1行1項目にまとめる"
      . "（番号は付けない。上から順に読めば手順どおりになるようにする）。\n"
      . "・複数の項目にまたがる場合は、項目ごとに「## 項目名」を見出しにして分けてよい。\n"
      . "・見出しの先頭に内容に合う絵文字を1つだけ添えてもよい（任意）。\n"
      . "・関連項目に書かれていないことは、絶対に推測や一般論で補わないでください。関連項目だけでは質問に答えられない場合は、"
      . "その旨とどの項目に何が書かれているかを正直に述べてください。\n"
      . "・出力はこの書式のプレーンテキストのみとし、JSON化やコードブロック（```）にはしないでください。\n\n"
      . "【例外：聞き返し】関連項目に複数の異なる手順があり、対象（機種・状況・作業の種類など）が分からないと"
      . "どれを案内すべきか決められない場合に限り、回答の代わりに次の形式だけを出力してください。\n"
      . "ASK: 利用者への確認の質問（50字以内）\n"
      . "- 選択肢1\n"
      . "- 選択肢2\n"
      . "選択肢は2〜4個。必ず【関連項目】に実在する内容から作り、選べば実際の手順にたどり着けるようにしてください。"
      . "対象が明らかなときは聞き返さず、普通に回答してください。\n\n"
      . "【質問】" . $query . "\n\n【関連項目】\n" . implode("\n\n", $secLines);
    list($sumText, $sumErr) = cbc_gemini_soft($pdo, $prompt, false, 1600);
    $sumText = ($sumErr === null) ? trim((string)$sumText) : '';
    // 聞き返しは JSON ではなく行頭の「ASK:」で見分ける。JSONに包むと、文章が長引いて途中で切れたときに
    // 構文ごと壊れて全部消えてしまうため（以前それで「文章が出ない」不具合が起きた）。
    // 行頭マーカーなら、たとえ後半が切れても先頭の判定は必ず成立する。
    $ask = null; $summary = null;
    if ($sumText !== '' && preg_match('/^ASK[:：]\s*(.+)$/mu', $sumText, $m) && stripos(ltrim($sumText), 'ASK') === 0) {
      $question = trim($m[1]);
      $options = array();
      foreach (preg_split('/\R/u', $sumText) as $line) {
        if (preg_match('/^\s*[-・*]\s*(.+?)\s*$/u', $line, $om)) {
          $o = mb_substr(trim($om[1]), 0, 40);
          if ($o !== '' && !in_array($o, $options, true)) $options[] = $o;
        }
        if (count($options) >= 4) break;
      }
      // 選べる選択肢が2つ未満なら聞き返しとして成立しないので、通常の回答として扱う
      if ($question !== '' && count($options) >= 2) $ask = array('question' => mb_substr($question, 0, 120), 'options' => $options);
    }
    if ($ask === null && $sumText !== '') $summary = $sumText;
    ok(array('summary' => $summary, 'ask' => $ask));
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
    // ユーザー権限（パスワードハッシュ含む）・在庫・交通費・設定を、無加工でそのまま書き出す。
    require_admin_session($pdo);
    ok(array('backup' => cbc_build_backup_array($pdo)));
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
      // 中身が総入れ替えになるので、AI検索の索引も破棄する（作り直しの対象になる）
      try { $pdo->exec('DELETE FROM node_vectors'); } catch (Throwable $e) {}
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
      // ---- trips（交通費）----
      if (isset($b['trips']) && is_array($b['trips'])) {
        $pdo->exec('DELETE FROM trips');
        $it2 = $pdo->prepare('INSERT INTO trips (id, username, display_name, trip_date, case_name, mode, origin, destination, one_way_km, round_trip, gas_rate, gas_cost, fare_cost, toll_cost, parking_cost, other_cost, total, cost_details, note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)');
        foreach ($b['trips'] as $t) {
          if (empty($t['id'])) continue;
          $costDetails = isset($t['cost_details'])
            ? (is_array($t['cost_details']) ? json_encode($t['cost_details'], JSON_UNESCAPED_UNICODE) : $t['cost_details'])
            : null;
          $it2->execute(array(
            $t['id'],
            isset($t['username']) ? $t['username'] : null,
            isset($t['display_name']) ? $t['display_name'] : null,
            isset($t['trip_date']) ? $t['trip_date'] : null,
            isset($t['case_name']) ? $t['case_name'] : null,
            (isset($t['mode']) && $t['mode'] === 'train') ? 'train' : 'car',
            isset($t['origin']) ? $t['origin'] : null,
            isset($t['destination']) ? $t['destination'] : null,
            isset($t['one_way_km']) ? (float)$t['one_way_km'] : 0,
            !empty($t['round_trip']) ? 1 : 0,
            isset($t['gas_rate']) ? (int)$t['gas_rate'] : 18,
            isset($t['gas_cost']) ? (int)$t['gas_cost'] : 0,
            isset($t['fare_cost']) ? (int)$t['fare_cost'] : 0,
            isset($t['toll_cost']) ? (int)$t['toll_cost'] : 0,
            isset($t['parking_cost']) ? (int)$t['parking_cost'] : 0,
            isset($t['other_cost']) ? (int)$t['other_cost'] : 0,
            isset($t['total']) ? (int)$t['total'] : 0,
            $costDetails,
            isset($t['note']) ? $t['note'] : null,
            isset($t['created_at']) ? (int)$t['created_at'] : $ts,
            isset($t['updated_at']) ? (int)$t['updated_at'] : $ts,
          ));
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

  case 'backup_list': {
    // 自動バックアップの一覧（管理者のみ）。修正画面の「バックアップの状況」に表示する。
    require_admin_session($pdo);
    $out = array();
    if (is_dir(BACKUP_DIR)) {
      foreach (scandir(BACKUP_DIR) as $f) {
        if (!preg_match('/^backup-(\d{4}-\d{2}-\d{2})\.json$/', $f, $m)) continue;
        $path = rtrim(BACKUP_DIR, '/') . '/' . $f;
        $out[] = array(
          'name'       => $f,
          'date'       => $m[1],
          'size'       => (int) @filesize($path),
          'created_at' => ((int) @filemtime($path)) * 1000,
        );
      }
    }
    usort($out, function ($a, $b) { return strcmp($b['date'], $a['date']); }); // 新しい順
    ok(array('items' => $out));
  }

  case 'backup_get': {
    // 保存済みの自動バックアップを1件、内容ごと取得（ダウンロード用。管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $name = trim((string)(isset($d['name']) ? $d['name'] : ''));
    if (!preg_match('/^backup-\d{4}-\d{2}-\d{2}\.json$/', $name)) fail('不正なファイル名です');
    $path = rtrim(BACKUP_DIR, '/') . '/' . $name;
    if (!is_file($path)) fail('ファイルが見つかりません', 404);
    $json = @file_get_contents($path);
    $data = ($json !== false) ? json_decode($json, true) : null;
    if (!is_array($data)) fail('バックアップの読み込みに失敗しました', 500);
    ok(array('backup' => $data));
  }

  case 'backup_delete': {
    // 保存済みの自動バックアップを1件削除（管理者のみ）
    require_admin_session($pdo);
    $d = body_json();
    $name = trim((string)(isset($d['name']) ? $d['name'] : ''));
    if (!preg_match('/^backup-\d{4}-\d{2}-\d{2}\.json$/', $name)) fail('不正なファイル名です');
    $path = rtrim(BACKUP_DIR, '/') . '/' . $name;
    if (is_file($path)) @unlink($path);
    ok();
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

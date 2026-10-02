<?php
/**
 * 領収書AI読み取り API（Gemini 中継）
 *
 *   POST  JSON {"files": [{"mimeType": "image/jpeg", "data": "<base64>"}, ...]}   （最大 max_files_per_request 枚。既定4）
 *         → 複数の画像（またはPDF）をまとめて1回で読み取り、ファイルごとの結果をJSONで返す
 *         （旧形式 {"mimeType": ..., "data": ...} の1枚送信にも対応）
 *   GET   ?action=status
 *         → APIキー・モデル設定の接続テスト（キーは末尾4桁のみ返す）と、本日の使用回数
 *
 * 利用上限（無料枠の回数制限）に達したときは、config.php の models に書いた次のモデルへ自動で切り替える。
 * 上限に達したモデルは、その日のうちは使わない（api/data/usage.json に記録）。
 *
 * APIキーは同じフォルダの config.php（無ければ環境変数 GEMINI_API_KEY）にのみ置き、ブラウザには渡さない。
 * キーを変更するときは config.php の gemini_api_key を書き換えるだけでよい（このファイルや index.html の変更は不要）。
 * 指示文とスキーマはこのファイルに固定しているため、汎用のGemini中継として悪用されることはない。
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const APP_VERSION = '1.7';   // index.html の APP_VERSION と揃えて上げる
const DEFAULT_MODEL = 'gemini-3.8-flash';
// 主モデルが上限に達したときに自動で使う予備モデル（無料枠の上限はモデルごとに別枠）。config.php の fallback_models で変更・無効化できる。
// 存在しないモデル名は自動で飛ばされる。モデル名は Google AI Studio のモデル一覧で確認できる。
const DEFAULT_FALLBACK_MODELS = ['gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash-lite'];
const DEFAULT_ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta';
const MAX_FILE_BYTES = 6 * 1024 * 1024;
const DEFAULT_FILES_PER_REQUEST = 4;             // 1回のAI呼び出しにまとめる枚数の既定値（config.php の max_files_per_request で変更。1〜MAX_FILES_LIMIT）
const MAX_FILES_LIMIT = 6;
const MAX_REQUEST_BYTES = 14 * 1024 * 1024;      // 1回の呼び出しで送る画像の合計（Geminiのインライン送信の上限20MBに余裕を持たせる）
const USAGE_FILE = __DIR__ . '/data/usage.json';
const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif', 'application/pdf'];

const RECEIPT_PROMPT = <<<'TXT'
あなたは日本の経費精算の担当者です。添付の画像（またはPDF）に写っている領収書を読み取り、高速道路・有料道路の通行料金と、駐車場の料金を抽出してください。

## 添付ファイルについて
- 添付は複数ある場合があります。各ファイルの直前に「[ファイル1]」「[ファイル2]」のように番号を付けています。
- 各領収書には、それが写っているファイルの番号を image に必ず入れてください（1始まりの整数）。別のファイルの内容を混ぜてはいけません。
- ファイルが1つだけでも image は 1 を入れてください。
- 領収書が読み取れないファイルは、そのファイルの番号で category を "other"、amount を null、note に理由を書いた要素を1件入れてください。

## ルール
1. 領収書が複数写っている場合（ETC利用明細の複数行を含む）は、1件ずつ receipts 配列の別の要素にしてください。
2. category
   - "toll": 高速道路・有料道路・首都高速・NEXCO・ETC利用明細・通行料金の領収書
   - "parking": コインパーキング・時間貸し駐車場・駐車料金の領収書
   - "other": 上記以外（ガソリン代・飲食・物品購入など）
3. amount: 実際に支払った税込の金額を整数（円）で。
   - 「通行料金」「駐車料金」「合計」「領収金額」「ご利用金額」を優先する。
   - 「お預り」「お預かり」「現金」「お釣り」「釣銭」「ポイント」「割引額」「消費税額」「小計（別に合計がある場合）」は絶対に採用しない。
   - ETC割引・深夜割引などがある場合は割引後の支払額。
   - カンマ・「¥」「円」は除き数字のみ。読めない・判別できない場合は null（推測しない）。
4. date: 利用日（駐車場は出庫日、高速は出口の通過日）を YYYY-MM-DD 形式で。和暦（令和7年など）は西暦に変換。不明なら null。
5. facility:
   - parking: 駐車場名（例: タイムズ上野駅前、三井のリパーク台東2丁目）。店舗名・番号があれば含める。
   - toll: 道路名（例: 首都高速、東北自動車道、常磐自動車道、東京外環自動車道）。道路名が無ければ運営会社名（例: NEXCO東日本）。
6. entry / exit: 高速・有料道路の入口・出口の料金所名またはIC名（領収書の表記どおり）。駐車場の場合は null。
7. confidence: 金額の読み取りの確かさを 0〜1 で。文字のかすれ・折れ・影・一部が写っていない・手書きなどがあれば低くする。
8. note: 読み取りで気になった点（例:「金額の一部がかすれている」「2枚が重なっている」）を短い日本語で。無ければ null。
9. 画像に書かれていない情報を作らないでください。
TXT;

function respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function load_config(): array
{
    $file = __DIR__ . '/config.php';
    // OPcache が有効なサーバーでも、APIキーを書き換えたら次のリクエストから確実に反映されるようにする
    if (is_file($file) && function_exists('opcache_invalidate')) {
        @opcache_invalidate($file, true);
    }
    $config = is_file($file) ? require $file : [];
    if (!is_array($config)) {
        $config = [];
    }

    $key = trim((string)($config['gemini_api_key'] ?? ''));
    if ($key === '') {
        $key = trim((string)getenv('GEMINI_API_KEY'));
    }
    if ($key === 'YOUR_GEMINI_API_KEY') {
        $key = '';
    }

    // 使うモデルの一覧（先頭が最優先。上限に達したら次のモデルへ自動で切り替える）
    //   models           … 全体を直接指定（指定した順に使う）
    //   model            … 主モデル。fallback_models … 予備モデル（省略時は DEFAULT_FALLBACK_MODELS。[] で予備なし）
    $models = [];
    if (isset($config['models']) && is_array($config['models'])) {
        $models = $config['models'];
    } else {
        $fallbacks = (isset($config['fallback_models']) && is_array($config['fallback_models']))
            ? $config['fallback_models'] : DEFAULT_FALLBACK_MODELS;
        $models = array_merge([$config['model'] ?? DEFAULT_MODEL], $fallbacks);
    }
    $models = array_values(array_unique(array_filter(array_map(fn($m) => trim((string)$m), $models), fn($m) => $m !== '')));
    if (!$models) {
        $models = [DEFAULT_MODEL];
    }
    foreach ($models as $m) {
        if (!preg_match('/^[A-Za-z0-9._-]+$/', $m)) {
            respond(500, ['ok' => false, 'error' => 'config.php のモデル名の書式が正しくありません（' . $m . '）。']);
        }
    }

    $endpoint = rtrim(trim((string)($config['endpoint'] ?? '')), '/');
    if ($endpoint === '') {
        $endpoint = DEFAULT_ENDPOINT;
    }

    $maxFiles = (int)($config['max_files_per_request'] ?? DEFAULT_FILES_PER_REQUEST);
    $maxFiles = max(1, min(MAX_FILES_LIMIT, $maxFiles));

    return ['key' => $key, 'models' => $models, 'model' => $models[0], 'endpoint' => $endpoint, 'maxFiles' => $maxFiles];
}

/**
 * 使用回数の記録（api/data/usage.json）。
 * Gemini の「1日あたりの上限」は太平洋時間の0時（日本時間の16時／夏時間が終わると17時）にリセットされるため、
 * 日付の区切りも太平洋時間に合わせる。
 */
function usage_day(): string
{
    return (new DateTimeImmutable('now', new DateTimeZone('America/Los_Angeles')))->format('Y-m-d');
}

function usage_load(): array
{
    $day = usage_day();
    $u = null;
    if (is_file(USAGE_FILE)) {
        $u = json_decode((string)@file_get_contents(USAGE_FILE), true);
    }
    if (!is_array($u) || ($u['day'] ?? '') !== $day) {
        return ['day' => $day, 'models' => []];
    }
    $u['models'] = is_array($u['models'] ?? null) ? $u['models'] : [];
    return $u;
}

/** 回数を加算し、必要なら「上限到達」の印を付けて保存する。保存できない環境でも本来の処理は止めない */
function usage_update(string $model, array $changes): void
{
    $dir = dirname(USAGE_FILE);
    if (!is_dir($dir) && !@mkdir($dir, 0755, true)) {
        return;
    }
    $fp = @fopen(USAGE_FILE, 'c+');
    if (!$fp) {
        return;
    }
    if (flock($fp, LOCK_EX)) {
        $u = json_decode((string)stream_get_contents($fp), true);
        if (!is_array($u) || ($u['day'] ?? '') !== usage_day()) {
            $u = ['day' => usage_day(), 'models' => []];
        }
        $m = $u['models'][$model] ?? ['requests' => 0, 'exhausted' => false, 'missing' => false];
        $m['requests'] = (int)($m['requests'] ?? 0) + (int)($changes['requests'] ?? 0);
        foreach (['exhausted', 'missing'] as $flag) {
            if (array_key_exists($flag, $changes)) {
                $m[$flag] = (bool)$changes[$flag];
            }
        }
        $u['models'][$model] = $m;
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, json_encode($u, JSON_UNESCAPED_UNICODE));
        fflush($fp);
        flock($fp, LOCK_UN);
    }
    fclose($fp);
}

function key_tail(string $key): string
{
    return '…' . substr($key, -4);
}

function gemini_request(array $cfg, string $method, string $path, ?array $body = null): array
{
    $ch = curl_init($cfg['endpoint'] . $path);
    $headers = ['x-goog-api-key: ' . $cfg['key']];
    $opts = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => 90,
        CURLOPT_CUSTOMREQUEST => $method,
    ];
    if ($body !== null) {
        $headers[] = 'Content-Type: application/json';
        $opts[CURLOPT_POSTFIELDS] = json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }
    $opts[CURLOPT_HTTPHEADER] = $headers;
    curl_setopt_array($ch, $opts);

    $raw = curl_exec($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $error = curl_error($ch);
    $json = is_string($raw) ? json_decode($raw, true) : null;

    return [
        'status' => $raw === false ? 0 : $status,
        'json' => is_array($json) ? $json : null,
        'error' => $error,
    ];
}

/**
 * Geminiの 429 を「1分あたりの上限」か「1日あたりの上限」かに分類する。
 * 戻り値: ['kind' => 'minute'|'day'|'unknown', 'retryAfter' => 待つ秒数（不明は null）]
 */
function classify_quota_error(array $r): array
{
    $text = json_encode($r['json']['error'] ?? [], JSON_UNESCAPED_UNICODE);
    $kind = 'unknown';
    if (preg_match('/PerDay|per day|daily/i', (string)$text)) {
        $kind = 'day';
    } elseif (preg_match('/PerMinute|per minute/i', (string)$text)) {
        $kind = 'minute';
    }
    $retry = null;
    foreach (($r['json']['error']['details'] ?? []) as $d) {
        if (isset($d['retryDelay']) && preg_match('/^([\d.]+)s$/', (string)$d['retryDelay'], $m)) {
            $retry = (int)ceil((float)$m[1]);
        }
    }
    return ['kind' => $kind, 'retryAfter' => $retry];
}

function gemini_error_message(array $r): string
{
    if ($r['status'] === 0) {
        return 'Gemini API に接続できませんでした（' . $r['error'] . '）。';
    }
    $msg = (string)($r['json']['error']['message'] ?? '');
    if ($r['status'] === 400 && stripos($msg, 'API key') !== false) {
        return 'APIキーが無効か期限切れです。api/config.php の gemini_api_key を新しいキーに更新してください。';
    }
    if ($r['status'] === 401 || $r['status'] === 403) {
        return 'APIキーにこのAPIの利用権限がありません。Google AI Studio でキーを確認し、api/config.php を更新してください。';
    }
    if ($r['status'] === 404) {
        return 'モデルが見つかりません。api/config.php の model を確認してください。' . ($msg !== '' ? '（' . $msg . '）' : '');
    }
    if ($r['status'] === 429) {
        return 'Gemini API の利用上限に達しました（1日の回数制限の可能性があります）。日本時間の午後4時（11月以降は午後5時）に回数がリセットされます。回数を増やすには、予備のモデルを追加するか、有料枠にしてください（同梱の README.txt の「3-2b」を参照）。';
    }
    return 'Gemini API エラー（HTTP ' . $r['status'] . '）' . ($msg !== '' ? '：' . $msg : '');
}

function receipt_schema(): array
{
    $nullableString = ['type' => 'STRING', 'nullable' => true];
    return [
        'type' => 'OBJECT',
        'properties' => [
            'receipts' => [
                'type' => 'ARRAY',
                'items' => [
                    'type' => 'OBJECT',
                    'properties' => [
                        'image' => ['type' => 'INTEGER'],
                        'category' => ['type' => 'STRING', 'enum' => ['toll', 'parking', 'other']],
                        'facility' => $nullableString,
                        'entry' => $nullableString,
                        'exit' => $nullableString,
                        'date' => $nullableString,
                        'amount' => ['type' => 'INTEGER', 'nullable' => true],
                        'confidence' => ['type' => 'NUMBER'],
                        'note' => $nullableString,
                    ],
                    'required' => ['image', 'category', 'facility', 'entry', 'exit', 'date', 'amount', 'confidence', 'note'],
                ],
            ],
        ],
        'required' => ['receipts'],
    ];
}

function clean_str($v, int $max): ?string
{
    if (!is_string($v)) {
        return null;
    }
    $s = preg_replace('/\s+/u', ' ', $v);
    $s = trim($s === null ? $v : $s);
    if ($s === '' || strcasecmp($s, 'null') === 0) {
        return null;
    }
    return function_exists('mb_substr') ? mb_substr($s, 0, $max) : $s;
}

function normalize_receipt($r): ?array
{
    if (!is_array($r)) {
        return null;
    }
    $category = (string)($r['category'] ?? 'other');
    if (!in_array($category, ['toll', 'parking', 'other'], true)) {
        $category = 'other';
    }

    $amount = $r['amount'] ?? null;
    if (is_string($amount)) {
        $digits = preg_replace('/[^0-9]/', '', $amount);
        $amount = ($digits === '' || $digits === null) ? null : (int)$digits;
    } elseif (is_int($amount) || is_float($amount)) {
        $amount = (int)round($amount);
    } else {
        $amount = null;
    }
    if ($amount !== null && ($amount < 0 || $amount > 1000000)) {
        $amount = null;
    }

    $confidence = $r['confidence'] ?? null;
    $confidence = is_numeric($confidence) ? max(0.0, min(1.0, (float)$confidence)) : null;

    $image = $r['image'] ?? null;
    $image = (is_int($image) || (is_string($image) && ctype_digit($image))) ? (int)$image : null;

    return [
        'image' => $image,
        'category' => $category,
        'amount' => $amount,
        'date' => clean_str($r['date'] ?? null, 20),
        'facility' => clean_str($r['facility'] ?? null, 80),
        'entry' => clean_str($r['entry'] ?? null, 40),
        'exit' => clean_str($r['exit'] ?? null, 40),
        'confidence' => $confidence,
        'note' => clean_str($r['note'] ?? null, 200),
    ];
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

// ---- 接続テスト（APIキー変更後の確認用）。本日の使用回数も返す ----
if ($method === 'GET' && ($_GET['action'] ?? '') === 'status') {
    $cfg = load_config();
    if ($cfg['key'] === '') {
        respond(200, ['ok' => false, 'model' => $cfg['model'], 'error' => 'APIキーが未設定です。api/config.sample.php を config.php にコピーしてキーを設定してください。']);
    }
    $r = gemini_request($cfg, 'GET', '/models/' . rawurlencode($cfg['model']));
    if ($r['status'] !== 200) {
        respond(200, ['ok' => false, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key']), 'error' => gemini_error_message($r)]);
    }
    $usage = usage_load();
    $models = [];
    foreach ($cfg['models'] as $m) {
        $models[] = [
            'name' => $m,
            'requests' => (int)($usage['models'][$m]['requests'] ?? 0),
            'exhausted' => !empty($usage['models'][$m]['exhausted']),
            'missing' => !empty($usage['models'][$m]['missing']),
        ];
    }
    respond(200, [
        'ok' => true, 'version' => APP_VERSION, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key']),
        'maxFiles' => $cfg['maxFiles'], 'usageDay' => $usage['day'], 'models' => $models,
        'usageWritable' => is_dir(dirname(USAGE_FILE)) ? is_writable(dirname(USAGE_FILE)) : is_writable(__DIR__),
    ]);
}

if ($method !== 'POST') {
    respond(405, ['ok' => false, 'error' => 'POST で送信してください。']);
}

@set_time_limit(240);
$cfg = load_config();
if ($cfg['key'] === '') {
    respond(500, ['ok' => false, 'error' => 'APIキーが未設定です。api/config.sample.php を config.php にコピーしてキーを設定してください。']);
}

// ---- 入力チェック（複数枚・旧形式の1枚送信のどちらも受け付ける） ----
$raw = file_get_contents('php://input');
if ($raw === false || $raw === '') {
    respond(400, ['ok' => false, 'error' => '送信データが空です（サーバーの post_max_size を超えた可能性があります）。']);
}
$in = json_decode($raw, true);
if (!is_array($in)) {
    respond(400, ['ok' => false, 'error' => 'リクエストの形式が正しくありません。']);
}
$files = isset($in['files']) && is_array($in['files']) ? array_values($in['files']) : [['mimeType' => $in['mimeType'] ?? '', 'data' => $in['data'] ?? '']];
if (!$files || count($files) > $cfg['maxFiles']) {
    respond(400, ['ok' => false, 'error' => '一度に読み取れるのは1〜' . $cfg['maxFiles'] . '枚です。']);
}
$total = 0;
foreach ($files as $i => $f) {
    $mime = strtolower(trim((string)($f['mimeType'] ?? '')));
    if (!in_array($mime, ALLOWED_MIME, true)) {
        respond(400, ['ok' => false, 'error' => '対応していないファイル形式です（' . $mime . '）。']);
    }
    $data = (string)($f['data'] ?? '');
    $binary = base64_decode($data, true);
    if ($binary === false || $binary === '') {
        respond(400, ['ok' => false, 'error' => 'ファイルのデータが正しくありません。']);
    }
    if (strlen($binary) > MAX_FILE_BYTES) {
        respond(413, ['ok' => false, 'error' => 'ファイルが大きすぎます（6MBまで）。']);
    }
    $total += strlen($binary);
    $files[$i] = ['mimeType' => $mime, 'data' => $data];
}
unset($binary);
if ($total > MAX_REQUEST_BYTES) {
    respond(413, ['ok' => false, 'error' => '一度に送る画像の合計が大きすぎます。枚数を減らしてください。']);
}

// ---- Gemini に送る内容：各ファイルの直前に「[ファイルN]」を付けて、どの領収書がどのファイルのものか分かるようにする ----
$parts = [];
foreach ($files as $i => $f) {
    $parts[] = ['text' => '[ファイル' . ($i + 1) . ']'];
    $parts[] = ['inlineData' => ['mimeType' => $f['mimeType'], 'data' => $f['data']]];
}
$parts[] = ['text' => RECEIPT_PROMPT . "\n\n今回の添付ファイルは全部で " . count($files) . " 個です。"];
$payload = [
    'contents' => [['role' => 'user', 'parts' => $parts]],
    'generationConfig' => [
        'responseMimeType' => 'application/json',
        'responseSchema' => receipt_schema(),
    ],
];

// ---- モデルを順に試す。上限に達したモデルは飛ばし、その日のうちは使わない ----
$usage = usage_load();
$result = null;
$usedModel = null;
$skipped = [];
$lastError = null;
foreach ($cfg['models'] as $model) {
    if (!empty($usage['models'][$model]['exhausted']) || !empty($usage['models'][$model]['missing'])) {
        $skipped[] = $model;
        continue;
    }
    $path = '/models/' . rawurlencode($model) . ':generateContent';
    for ($attempt = 0; ; $attempt++) {
        $r = gemini_request($cfg, 'POST', $path, $payload);
        usage_update($model, ['requests' => 1]);
        if ($r['status'] === 200 && $r['json'] !== null) {
            break;
        }
        $retry = false;
        if ($r['status'] === 404) {
            usage_update($model, ['missing' => true]);
        } elseif ($r['status'] === 429) {
            $q = classify_quota_error($r);
            // 1日の上限（または種類が分からない上限）は待っても回復しないので、再試行せず次のモデルへ
            if ($q['kind'] !== 'minute') {
                usage_update($model, ['exhausted' => true]);
            } elseif ($attempt < 1 && ($q['retryAfter'] === null || $q['retryAfter'] <= 20)) {
                sleep(max(2, (int)$q['retryAfter']));   // 1分あたりの上限は、指示された秒数だけ待って1回だけ再試行
                $retry = true;
            }
        } elseif (in_array($r['status'], [500, 503], true) && $attempt < 1) {
            sleep(2);
            $retry = true;
        }
        if (!$retry) {
            break;
        }
    }
    if ($r['status'] === 200 && $r['json'] !== null) {
        $result = $r;
        $usedModel = $model;
        break;
    }
    $lastError = $r;
    // 上限（429）・モデルが無い（404）なら次のモデルを試す。キー不正など他のエラーは、モデルを変えても同じなので打ち切る
    if (!in_array($r['status'], [429, 404], true)) {
        break;
    }
}
if ($result === null) {
    if ($lastError === null) {
        respond(502, ['ok' => false, 'code' => 'quota_day', 'error' => '使えるモデルがすべて本日は使えません（上限に達したか、モデルが見つかりません：' . implode('、', $skipped) . '）。日本時間の午後4時（11月以降は午後5時）に回数がリセットされます。回数を増やすには、有料枠にするか、api/config.php に別のモデルを追加してください。']);
    }
    $code = $lastError['status'] === 429 ? 'quota' : 'error';
    respond(502, ['ok' => false, 'code' => $code, 'error' => gemini_error_message($lastError)]);
}

// ---- 応答の解析 ----
$candidate = $result['json']['candidates'][0] ?? null;
$text = '';
foreach (($candidate['content']['parts'] ?? []) as $part) {
    if (!empty($part['thought'])) {
        continue;
    }
    if (isset($part['text']) && is_string($part['text'])) {
        $text .= $part['text'];
    }
}
if ($text === '') {
    $reason = $candidate['finishReason'] ?? ($result['json']['promptFeedback']['blockReason'] ?? '不明');
    respond(502, ['ok' => false, 'error' => 'AIから読み取り結果が返りませんでした（' . $reason . '）。']);
}
$parsed = json_decode($text, true);
if (!is_array($parsed) && preg_match('/\{[\s\S]*\}/', $text, $m)) {
    $parsed = json_decode($m[0], true);
}
if (!is_array($parsed) || !isset($parsed['receipts']) || !is_array($parsed['receipts'])) {
    respond(502, ['ok' => false, 'error' => 'AIの応答を解析できませんでした。もう一度お試しください。']);
}

// ファイルごとに振り分ける。番号が不正・欠落した領収書は unassigned に分け、画面側で要確認にする
$byFile = array_fill(0, count($files), []);
$unassigned = [];
foreach (array_values(array_filter(array_map('normalize_receipt', $parsed['receipts']))) as $rc) {
    $idx = isset($rc['image']) ? $rc['image'] - 1 : -1;
    unset($rc['image']);
    if ($idx >= 0 && $idx < count($files)) {
        $byFile[$idx][] = $rc;
    } else {
        $unassigned[] = $rc;
    }
}
respond(200, [
    'ok' => true, 'model' => $usedModel, 'fallback' => $usedModel !== $cfg['models'][0],
    'files' => array_map(fn($list) => ['receipts' => $list], $byFile),
    'unassigned' => $unassigned,
]);

<?php
/**
 * 領収書AI読み取り API（Gemini 中継）
 *
 *   POST  JSON {"mimeType": "image/jpeg", "data": "<base64>"}
 *         → 画像（またはPDF）から高速代・駐車場代を読み取り、JSONで返す
 *   GET   ?action=status
 *         → APIキー・モデル設定の接続テスト（キーは末尾4桁のみ返す）
 *
 * APIキーは同じフォルダの config.php（無ければ環境変数 GEMINI_API_KEY）にのみ置き、ブラウザには渡さない。
 * キーを変更するときは config.php の gemini_api_key を書き換えるだけでよい（このファイルや index.html の変更は不要）。
 * 指示文とスキーマはこのファイルに固定しているため、汎用のGemini中継として悪用されることはない。
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const APP_VERSION = '1.5';   // index.html の APP_VERSION と揃えて上げる
const DEFAULT_MODEL = 'gemini-3.8-flash';
const DEFAULT_ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta';
const MAX_FILE_BYTES = 6 * 1024 * 1024;
const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif', 'application/pdf'];

const RECEIPT_PROMPT = <<<'TXT'
あなたは日本の経費精算の担当者です。添付の画像（またはPDF）に写っている領収書を読み取り、高速道路・有料道路の通行料金と、駐車場の料金を抽出してください。

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

    $model = trim((string)($config['model'] ?? ''));
    if ($model === '') {
        $model = DEFAULT_MODEL;
    }
    if (!preg_match('/^[A-Za-z0-9._-]+$/', $model)) {
        respond(500, ['ok' => false, 'error' => 'config.php の model の書式が正しくありません。']);
    }

    $endpoint = rtrim(trim((string)($config['endpoint'] ?? '')), '/');
    if ($endpoint === '') {
        $endpoint = DEFAULT_ENDPOINT;
    }

    return ['key' => $key, 'model' => $model, 'endpoint' => $endpoint];
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
        return 'Gemini API の利用上限に達しました。しばらく待ってから再度お試しください（無料枠の場合は有料枠への切替もご検討ください）。';
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
                        'category' => ['type' => 'STRING', 'enum' => ['toll', 'parking', 'other']],
                        'facility' => $nullableString,
                        'entry' => $nullableString,
                        'exit' => $nullableString,
                        'date' => $nullableString,
                        'amount' => ['type' => 'INTEGER', 'nullable' => true],
                        'confidence' => ['type' => 'NUMBER'],
                        'note' => $nullableString,
                    ],
                    'required' => ['category', 'facility', 'entry', 'exit', 'date', 'amount', 'confidence', 'note'],
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

    return [
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

// ---- 接続テスト（APIキー変更後の確認用） ----
if ($method === 'GET' && ($_GET['action'] ?? '') === 'status') {
    $cfg = load_config();
    if ($cfg['key'] === '') {
        respond(200, ['ok' => false, 'model' => $cfg['model'], 'error' => 'APIキーが未設定です。api/config.sample.php を config.php にコピーしてキーを設定してください。']);
    }
    $r = gemini_request($cfg, 'GET', '/models/' . rawurlencode($cfg['model']));
    if ($r['status'] !== 200) {
        respond(200, ['ok' => false, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key']), 'error' => gemini_error_message($r)]);
    }
    respond(200, ['ok' => true, 'version' => APP_VERSION, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key'])]);
}

if ($method !== 'POST') {
    respond(405, ['ok' => false, 'error' => 'POST で送信してください。']);
}

@set_time_limit(180);
$cfg = load_config();
if ($cfg['key'] === '') {
    respond(500, ['ok' => false, 'error' => 'APIキーが未設定です。api/config.sample.php を config.php にコピーしてキーを設定してください。']);
}

// ---- 入力チェック ----
$raw = file_get_contents('php://input');
if ($raw === false || $raw === '') {
    respond(400, ['ok' => false, 'error' => '送信データが空です（サーバーの post_max_size を超えた可能性があります）。']);
}
$in = json_decode($raw, true);
if (!is_array($in)) {
    respond(400, ['ok' => false, 'error' => 'リクエストの形式が正しくありません。']);
}
$mime = strtolower(trim((string)($in['mimeType'] ?? '')));
if (!in_array($mime, ALLOWED_MIME, true)) {
    respond(400, ['ok' => false, 'error' => '対応していないファイル形式です（' . $mime . '）。']);
}
$data = (string)($in['data'] ?? '');
$binary = base64_decode($data, true);
if ($binary === false || $binary === '') {
    respond(400, ['ok' => false, 'error' => 'ファイルのデータが正しくありません。']);
}
if (strlen($binary) > MAX_FILE_BYTES) {
    respond(413, ['ok' => false, 'error' => 'ファイルが大きすぎます（6MBまで）。']);
}
unset($binary);

// ---- Gemini 呼び出し（画像→指示文の順。一時的なエラーは最大2回まで再試行） ----
$payload = [
    'contents' => [[
        'role' => 'user',
        'parts' => [
            ['inlineData' => ['mimeType' => $mime, 'data' => $data]],
            ['text' => RECEIPT_PROMPT],
        ],
    ]],
    'generationConfig' => [
        'responseMimeType' => 'application/json',
        'responseSchema' => receipt_schema(),
    ],
];
$path = '/models/' . rawurlencode($cfg['model']) . ':generateContent';
for ($attempt = 0; ; $attempt++) {
    $r = gemini_request($cfg, 'POST', $path, $payload);
    if ($attempt >= 2 || !in_array($r['status'], [429, 500, 503], true)) {
        break;
    }
    sleep($attempt === 0 ? 2 : 5);
}
if ($r['status'] !== 200 || $r['json'] === null) {
    respond(502, ['ok' => false, 'error' => gemini_error_message($r)]);
}

// ---- 応答の解析 ----
$candidate = $r['json']['candidates'][0] ?? null;
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
    $reason = $candidate['finishReason'] ?? ($r['json']['promptFeedback']['blockReason'] ?? '不明');
    respond(502, ['ok' => false, 'error' => 'AIから読み取り結果が返りませんでした（' . $reason . '）。']);
}
$parsed = json_decode($text, true);
if (!is_array($parsed) && preg_match('/\{[\s\S]*\}/', $text, $m)) {
    $parsed = json_decode($m[0], true);
}
if (!is_array($parsed) || !isset($parsed['receipts']) || !is_array($parsed['receipts'])) {
    respond(502, ['ok' => false, 'error' => 'AIの応答を解析できませんでした。もう一度お試しください。']);
}

$receipts = array_values(array_filter(array_map('normalize_receipt', $parsed['receipts'])));
respond(200, ['ok' => true, 'model' => $cfg['model'], 'receipts' => $receipts]);

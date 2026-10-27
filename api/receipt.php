<?php
/**
 * 領収書AI読み取り API（Gemini 中継）
 *
 *   POST  JSON {"files": [{"mimeType": "image/jpeg", "data": "<base64>"}, ...], "pass": 1}
 *         → 画像（またはPDF）から高速代・駐車場代を読み取り、ファイルごとの結果をJSONで返す
 *         （旧形式 {"mimeType": ..., "data": ...} の1枚送信にも対応）
 *         pass … 何回目の読み取りか（1〜3）。回ごとに乱数の種（seed）を固定し、同じ画像なら同じ結果が出やすくする
 *   GET   ?action=status
 *         → APIキー・モデル設定の接続テスト（キーは末尾4桁のみ返す）と、本日の使用回数
 *
 * 精度と安定性のための設計（Ver 1.8〜）
 *   - 1回の呼び出しで送る画像は既定で1枚（Google の資料で「文字の読み取りは1枚ずつの方が精度が良い」とされているため）
 *   - 画像の解像度を「高」に固定（mediaResolution）。毎回同じ細かさで読ませる
 *   - 金額を読み取った箇所の文字（amount_text）を書き写させ、数字と金額が一致するかをサーバーで照合（evidence）
 *   - 一時的なエラー（混雑・応答の崩れ）は自動で再試行
 *   - 上記の追加設定をGemini側が受け付けない場合は、追加設定なしで自動的にやり直す
 *   - 原因調査のため、読み取り結果の要約を api/data/log.jsonl に記録（画像・APIキーは記録しない）
 *
 * 応答が返ってこない問題への対策（Ver 1.9）
 *   Gemini の新しい Flash 系モデルは、JSON の出力中に同じ数字・文字を延々と繰り返し、出力の上限（約6.5万トークン）まで
 *   止まらなくなることがある（開発者フォーラムで報告あり。約3回に1回）。その間は応答が返らず、タイムアウトになっていた。
 *   - 出力の長さに上限（maxOutputTokens）を付け、繰り返しが起きても十数秒で打ち切られるようにする
 *   - 打ち切られた・崩れた応答は、乱数の種（seed）を変えてやり直す（同じ種だと同じ崩れ方をしやすい）
 *   - AIの考える量（thinkingLevel）を「低」にして応答を速くする（config.php の thinking_level で変更可）
 *   - 1回の呼び出しの待ち時間は60秒まで、再試行・予備モデルを含めた1回の読み取りは約100秒までに抑える
 *   - 時間切れになったモデルは5分間後回しにし、予備のモデルを先に使う
 *
 * 利用上限（無料枠の回数制限）に達したときは、予備のモデルへ自動で切り替える。
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

const APP_VERSION = '1.9';   // index.html の APP_VERSION と揃えて上げる
const DEFAULT_MODEL = 'gemini-3.8-flash';
// 主モデルが上限に達したときに自動で使う予備モデル（無料枠の上限はモデルごとに別枠）。config.php の fallback_models で変更・無効化できる。
// 精度を揃えるため、既定では Flash 系だけにしている（Flash-Lite 系は精度が下がりやすいので、使う場合は config.php で追加する）。
// 存在しないモデル名は自動で飛ばされる。モデル名は Google AI Studio のモデル一覧で確認できる。
const DEFAULT_FALLBACK_MODELS = ['gemini-3.7-flash', 'gemini-3.6-flash'];
const DEFAULT_ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta';
const MAX_FILE_BYTES = 6 * 1024 * 1024;
const DEFAULT_FILES_PER_REQUEST = 1;             // 1回のAI呼び出しで送る枚数の既定値（精度優先で1枚ずつ。config.php の max_files_per_request で変更）
const MAX_FILES_LIMIT = 6;
const MAX_REQUEST_BYTES = 14 * 1024 * 1024;      // 1回の呼び出しで送る画像の合計（Geminiのインライン送信の上限20MBに余裕を持たせる）
const DATA_DIR = __DIR__ . '/data';
const USAGE_FILE = DATA_DIR . '/usage.json';
const LOG_FILE = DATA_DIR . '/log.jsonl';
const LOG_KEEP_LINES = 300;                      // 記録は直近この件数だけ残す
const PASS_SEEDS = [1 => 1101, 2 => 2203, 3 => 3307];   // 何回目の読み取りかごとの乱数の種
const DEFAULT_TIMEOUT = 60;                      // 1回の呼び出しで応答を待つ上限（秒）。config.php の request_timeout で変更
const TIME_BUDGET = 100;                         // 1回の読み取りで、再試行・予備モデルへの切替を新しく始めてよい時間（秒）
const SLOW_COOLDOWN = 300;                       // 時間切れになったモデルを後回しにする時間（秒）
const DEFAULT_THINKING_LEVEL = 'low';            // AIの考える量（minimal / low / medium / high。'' でGeminiの既定＝high）
const THINKING_LEVELS = ['', 'minimal', 'low', 'medium', 'high'];
const MAX_OUTPUT_TOKENS = 4096;                  // 出力の上限（画像1枚）。繰り返しが起きても早く打ち切るため
const MAX_OUTPUT_TOKENS_LARGE = 8192;            // 出力の上限（PDF・複数枚。ETC利用明細など行数が多い場合）
const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif', 'application/pdf'];

const RECEIPT_PROMPT = <<<'TXT'
あなたは日本の経費精算の担当者です。添付の画像（またはPDF）に写っている領収書を読み取り、高速道路・有料道路の通行料金と、駐車場の料金を抽出してください。

## 読み取りの手順
1. まず領収書全体を上から下まで読み、店名・日付・各金額の行を確認する。
2. 実際に支払った金額の行（「合計」「通行料金」「駐車料金」「領収金額」「ご利用金額」など）を特定する。
3. その行の文字を amount_text にそのまま書き写し、そこに書かれた数字を amount にする。

## ルール
1. 領収書が複数写っている場合（ETC利用明細の複数行を含む）は、1件ずつ receipts 配列の別の要素にしてください。
2. category
   - "toll": 高速道路・有料道路・首都高速・NEXCO・ETC利用明細・通行料金の領収書
   - "parking": コインパーキング・時間貸し駐車場・駐車料金の領収書
   - "other": 上記以外（ガソリン代・飲食・物品購入など）
3. amount_text: 金額を読み取った箇所の文字を、項目名ごと領収書の表記どおりに書き写す（例:「合計 ¥1,320」「駐車料金 800円」「通行料金 930」）。読めない場合は null。
4. amount: 実際に支払った税込の金額を整数（円）で。amount_text に書いた数字と必ず一致させる。
   - 領収書に書かれている桁数のとおりに書く（駐車場・高速の料金は通常2〜5桁）。同じ数字を続けて書き足さない。
   - 「お預り」「お預かり」「現金」「お釣り」「釣銭」「ポイント」「割引額」「消費税額」「小計（別に合計がある場合）」は絶対に採用しない。
   - ETC割引・深夜割引などがある場合は割引後の支払額。
   - カンマ・「¥」「円」は除き数字のみ。読めない・判別できない場合は null（推測しない）。
5. date: 利用日（駐車場は出庫日、高速は出口の通過日）を YYYY-MM-DD 形式で。和暦（令和7年など）は西暦に変換。不明なら null。
6. facility:
   - parking: 駐車場名（例: タイムズ上野駅前、三井のリパーク台東2丁目）。店舗名・番号があれば含める。
   - toll: 道路名（例: 首都高速、東北自動車道、常磐自動車道、東京外環自動車道）。道路名が無ければ運営会社名（例: NEXCO東日本）。
7. entry / exit: 高速・有料道路の入口・出口の料金所名またはIC名（領収書の表記どおり）。駐車場の場合は null。
8. confidence: 金額の読み取りの確かさを 0〜1 で。文字のかすれ・折れ・影・一部が写っていない・手書きなどがあれば低くする。
9. note: 読み取りで気になった点（例:「金額の一部がかすれている」「2枚が重なっている」）を短い日本語で。無ければ null。
10. 画像に書かれていない情報を作らないでください。
TXT;

const MULTI_FILE_PROMPT = <<<'TXT'
## 添付ファイルについて
- 添付は複数あります。各ファイルの直前に「[ファイル1]」「[ファイル2]」のように番号を付けています。
- 各領収書には、それが写っているファイルの番号を image に必ず入れてください（1始まりの整数）。別のファイルの内容を混ぜてはいけません。
- 領収書が読み取れないファイルは、そのファイルの番号で category を "other"、amount を null、note に理由を書いた要素を1件入れてください。
TXT;

const SINGLE_FILE_PROMPT = <<<'TXT'
## 添付ファイルについて
- 添付は1つです。
- 領収書が読み取れない場合は、category を "other"、amount を null、note に理由を書いた要素を1件入れてください。
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

    $timeout = max(5, min(300, (int)($config['request_timeout'] ?? DEFAULT_TIMEOUT)));
    $thinking = strtolower(trim((string)($config['thinking_level'] ?? DEFAULT_THINKING_LEVEL)));
    if (!in_array($thinking, THINKING_LEVELS, true)) {
        $thinking = DEFAULT_THINKING_LEVEL;
    }

    return [
        'key' => $key, 'models' => $models, 'model' => $models[0], 'endpoint' => $endpoint, 'maxFiles' => $maxFiles,
        'log' => !array_key_exists('log_requests', $config) || (bool)$config['log_requests'],
        'timeout' => $timeout, 'thinking' => $thinking,
        // 再試行・予備モデルへの切替を始めてよい時間（秒）。通常は変更不要（動作確認用に短くできる）
        'budget' => max(5, min(600, (int)($config['time_budget'] ?? TIME_BUDGET))),
    ];
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

/** 回数を加算し、必要なら印（上限到達・モデルなし・追加設定非対応・後回しの期限）を付けて保存する。保存できない環境でも本来の処理は止めない */
function usage_update(string $model, array $changes): void
{
    if (!is_dir(DATA_DIR) && !@mkdir(DATA_DIR, 0755, true)) {
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
        $m = $u['models'][$model] ?? ['requests' => 0, 'exhausted' => false, 'missing' => false, 'basic' => false];
        $m['requests'] = (int)($m['requests'] ?? 0) + (int)($changes['requests'] ?? 0);
        foreach (['exhausted', 'missing', 'basic'] as $flag) {
            if (array_key_exists($flag, $changes)) {
                $m[$flag] = (bool)$changes[$flag];
            }
        }
        if (array_key_exists('slowUntil', $changes)) {
            $m['slowUntil'] = (int)$changes['slowUntil'];
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

/** 読み取りの記録を1行追記する（画像・APIキーは含めない）。直近 LOG_KEEP_LINES 件だけ残す */
function log_request(array $cfg, array $entry): void
{
    if (!$cfg['log'] || (!is_dir(DATA_DIR) && !@mkdir(DATA_DIR, 0755, true))) {
        return;
    }
    $entry = ['time' => (new DateTimeImmutable('now', new DateTimeZone('Asia/Tokyo')))->format('Y-m-d H:i:s'), 'version' => APP_VERSION] + $entry;
    $fp = @fopen(LOG_FILE, 'c+');
    if (!$fp) {
        return;
    }
    if (flock($fp, LOCK_EX)) {
        $lines = array_values(array_filter(explode("\n", (string)stream_get_contents($fp)), fn($l) => trim($l) !== ''));
        $lines[] = json_encode($entry, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $lines = array_slice($lines, -LOG_KEEP_LINES);
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, implode("\n", $lines) . "\n");
        fflush($fp);
        flock($fp, LOCK_UN);
    }
    fclose($fp);
}

function key_tail(string $key): string
{
    return '…' . substr($key, -4);
}

function gemini_request(array $cfg, string $method, string $path, ?array $body = null, ?int $timeout = null): array
{
    $ch = curl_init($cfg['endpoint'] . $path);
    $headers = ['x-goog-api-key: ' . $cfg['key']];
    $opts = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => $timeout ?? $cfg['timeout'],
        CURLOPT_CUSTOMREQUEST => $method,
    ];
    if ($body !== null) {
        $headers[] = 'Content-Type: application/json';
        $opts[CURLOPT_POSTFIELDS] = json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }
    $opts[CURLOPT_HTTPHEADER] = $headers;
    curl_setopt_array($ch, $opts);

    $started = microtime(true);
    $raw = curl_exec($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $error = curl_error($ch);
    $errno = curl_errno($ch);
    $json = is_string($raw) ? json_decode($raw, true) : null;

    return [
        'status' => $raw === false ? 0 : $status,
        'json' => is_array($json) ? $json : null,
        'error' => $error,
        'timedOut' => $raw === false && $errno === 28,   // CURLE_OPERATION_TIMEDOUT
        'timeout' => $opts[CURLOPT_TIMEOUT],
        'ms' => (int)round((microtime(true) - $started) * 1000),
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

/** APIキーが原因の 400 か */
function is_key_error(array $r): bool
{
    return $r['status'] === 400 && preg_match('/API[ _]?key/i', (string)($r['json']['error']['message'] ?? '')) === 1;
}

/** 追加設定（解像度・seed・項目の順番・考える量）をGemini側が受け付けなかったときの 400 か */
function is_compat_error(array $r): bool
{
    if ($r['status'] !== 400 || is_key_error($r)) {
        return false;
    }
    $msg = (string)($r['json']['error']['message'] ?? '');
    return (bool)preg_match('/media_?resolution|seed|property_?ordering|thinking|Unknown name|Cannot find field|Invalid JSON payload|not supported/i', $msg);
}

function gemini_error_message(array $r): string
{
    if (!empty($r['timedOut'])) {
        return 'AIの応答に時間がかかりすぎたため中断しました（' . $r['timeout'] . '秒）。Gemini が混雑している可能性があります。少し時間をおいて「もう一度読み取る」を押してください。';
    }
    if ($r['status'] === 0) {
        return 'Gemini API に接続できませんでした（' . $r['error'] . '）。時間をおいてもう一度お試しください。';
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
    if (in_array($r['status'], [500, 502, 503, 504], true)) {
        return 'Gemini 側が混雑しているか一時的な不具合です（HTTP ' . $r['status'] . '）。時間をおいてもう一度お試しください。';
    }
    return 'Gemini API エラー（HTTP ' . $r['status'] . '）' . ($msg !== '' ? '：' . $msg : '');
}

/** $multi … 複数枚をまとめて送るときだけ、どの画像の領収書かを表す image を求める（1枚のときは不要な数字の項目を減らす） */
function receipt_schema(bool $basic, bool $multi): array
{
    $nullableString = ['type' => 'STRING', 'nullable' => true];
    $order = ['image', 'category', 'facility', 'entry', 'exit', 'date', 'amount_text', 'amount', 'confidence', 'note'];
    if (!$multi) {
        $order = array_values(array_diff($order, ['image']));
    }
    $item = [
        'type' => 'OBJECT',
        'properties' => [
            'image' => ['type' => 'INTEGER'],
            'category' => ['type' => 'STRING', 'enum' => ['toll', 'parking', 'other']],
            'facility' => $nullableString,
            'entry' => $nullableString,
            'exit' => $nullableString,
            'date' => $nullableString,
            'amount_text' => $nullableString,
            'amount' => ['type' => 'INTEGER', 'nullable' => true],
            'confidence' => ['type' => 'NUMBER'],
            'note' => $nullableString,
        ],
        'required' => $order,
    ];
    if (!$multi) {
        unset($item['properties']['image']);
    }
    if (!$basic) {
        $item['propertyOrdering'] = $order;   // 根拠の文字（amount_text）を書いてから金額（amount）を出させる
    }
    return [
        'type' => 'OBJECT',
        'properties' => ['receipts' => ['type' => 'ARRAY', 'items' => $item]],
        'required' => ['receipts'],
    ];
}

/**
 * Geminiに送る内容を組み立てる。
 * $basic = true のときは追加設定（解像度・seed・項目の順番・考える量）を付けない（Gemini側が受け付けないときの予備）。
 * 出力の上限（maxOutputTokens）は標準の設定なので常に付ける
 */
function build_payload(array $files, int $seed, bool $basic, array $cfg): array
{
    $parts = [];
    $multi = count($files) > 1;
    foreach ($files as $i => $f) {
        if ($multi) {
            $parts[] = ['text' => '[ファイル' . ($i + 1) . ']'];
        }
        $parts[] = ['inlineData' => ['mimeType' => $f['mimeType'], 'data' => $f['data']]];
    }
    $parts[] = ['text' => RECEIPT_PROMPT . "\n\n" . ($multi ? MULTI_FILE_PROMPT . "\n- 今回の添付ファイルは全部で " . count($files) . ' 個です。' : SINGLE_FILE_PROMPT)];
    // PDF・複数枚は行数が多くなりやすく、追加設定なし（考える量がGeminiの既定＝多め）のときは考える分も出力に含まれるため、上限を大きくする
    $large = $multi || $basic || in_array('application/pdf', array_column($files, 'mimeType'), true);
    $config = [
        'responseMimeType' => 'application/json',
        'responseSchema' => receipt_schema($basic, $multi),
        'maxOutputTokens' => $large ? MAX_OUTPUT_TOKENS_LARGE : MAX_OUTPUT_TOKENS,   // 同じ文字の繰り返しが起きても早く打ち切る
    ];
    if (!$basic) {
        $config['mediaResolution'] = 'MEDIA_RESOLUTION_HIGH';   // 細かい文字まで読めるよう、画像の解像度を「高」に固定
        $config['seed'] = $seed;                               // 同じ画像・同じ回なら、同じ結果が出やすくする
        if ($cfg['thinking'] !== '') {
            $config['thinkingConfig'] = ['thinkingLevel' => $cfg['thinking']];   // 考える量を抑えて応答を速くする
        }
    }
    return ['contents' => [['role' => 'user', 'parts' => $parts]], 'generationConfig' => $config];
}

/** 応答から領収書の配列を取り出す。取り出せなければ [null, 理由] */
function parse_receipts(array $json): array
{
    $candidate = $json['candidates'][0] ?? null;
    $text = '';
    foreach (($candidate['content']['parts'] ?? []) as $part) {
        if (!empty($part['thought'])) {
            continue;
        }
        if (isset($part['text']) && is_string($part['text'])) {
            $text .= $part['text'];
        }
    }
    $finish = (string)($candidate['finishReason'] ?? '');
    if ($finish === 'MAX_TOKENS') {
        // 同じ数字・文字を繰り返して出力の上限で打ち切られた（JSON が途中で切れている）
        return [null, 'AIの応答が崩れたため途中で打ち切りました（同じ文字の繰り返し）'];
    }
    if ($text === '') {
        return [null, 'AIから読み取り結果が返りませんでした（' . ($finish !== '' ? $finish : ($json['promptFeedback']['blockReason'] ?? '不明')) . '）'];
    }
    $parsed = json_decode($text, true);
    if (!is_array($parsed) && preg_match('/\{[\s\S]*\}/', $text, $m)) {
        $parsed = json_decode($m[0], true);
    }
    if (!is_array($parsed) || !isset($parsed['receipts']) || !is_array($parsed['receipts'])) {
        return [null, 'AIの応答を解析できませんでした'];
    }
    return [$parsed['receipts'], null];
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

/**
 * 根拠の文字（amount_text）の中に、金額（amount）と同じ数字があるか。
 *   'ok' … 一致 / 'mismatch' … 根拠の文字に金額の数字が無い / 'missing' … 根拠の文字が無い / null … 金額が無い
 */
function check_evidence(?int $amount, ?string $text): ?string
{
    if ($amount === null) {
        return null;
    }
    if ($text === null) {
        return 'missing';
    }
    $t = strtr($text, ['０' => '0', '１' => '1', '２' => '2', '３' => '3', '４' => '4', '５' => '5', '６' => '6', '７' => '7', '８' => '8', '９' => '9', '，' => ',']);
    preg_match_all('/\d[\d,]*/', $t, $m);
    foreach ($m[0] as $num) {
        if ((int)str_replace(',', '', $num) === $amount) {
            return 'ok';
        }
    }
    return 'mismatch';
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

    $amountText = clean_str($r['amount_text'] ?? null, 60);

    return [
        'image' => $image,
        'category' => $category,
        'amount' => $amount,
        'amount_text' => $amountText,
        'evidence' => check_evidence($amount, $amountText),
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
    $r = gemini_request($cfg, 'GET', '/models/' . rawurlencode($cfg['model']), null, 20);
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
            'slow' => (int)($usage['models'][$m]['slowUntil'] ?? 0) > time(),
        ];
    }
    respond(200, [
        'ok' => true, 'version' => APP_VERSION, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key']),
        'maxFiles' => $cfg['maxFiles'], 'usageDay' => $usage['day'], 'models' => $models,
        'usageWritable' => is_dir(DATA_DIR) ? is_writable(DATA_DIR) : is_writable(__DIR__),
        'logEnabled' => $cfg['log'], 'thinkingLevel' => $cfg['thinking'], 'timeout' => $cfg['timeout'],
    ]);
}

if ($method !== 'POST') {
    respond(405, ['ok' => false, 'error' => 'POST で送信してください。']);
}

@set_time_limit(600);
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
$pass = (int)($in['pass'] ?? 1);
if (!isset(PASS_SEEDS[$pass])) {
    $pass = 1;
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

// ---- モデルを順に試す。上限に達したモデルは飛ばし、その日のうちは使わない ----
//   1つのモデルの中では、一時的なエラーを自動で再試行する
//     応答の崩れ（繰り返しで打ち切り・JSONが壊れている・結果が空）… 乱数の種を変えて1回
//     混雑（0/500/502/503/504）… 2秒待って1回
//     1分あたりの上限 … 指示された秒数（30秒まで）待って1回
//     受け付けられない（400）… 追加設定なしで1回（追加設定が原因と分かったら、その日は以後も追加設定なしで送る）
//   時間切れ（request_timeout 秒）になったモデルは再試行せず、5分間後回しにして予備のモデルへ
//   再試行・予備モデルへの切替は、読み取り開始から TIME_BUDGET 秒までに始めるものに限る
$usage = usage_load();
$now = time();
$order = [];
$later = [];
$skipped = [];
foreach ($cfg['models'] as $m) {
    $mu = $usage['models'][$m] ?? [];
    if (!empty($mu['exhausted']) || !empty($mu['missing'])) {
        $skipped[] = $m;
    } elseif ((int)($mu['slowUntil'] ?? 0) > $now) {
        $later[] = $m;
    } else {
        $order[] = $m;
    }
}
$order = array_merge($order, $later);

$started = microtime(true);
$remaining = fn(): float => $cfg['budget'] - (microtime(true) - $started);
$receipts = null;
$usedModel = null;
$lastError = null;
$lastParseError = null;
$attempts = [];
foreach ($order as $model) {
    if ($lastError !== null && $remaining() < 10) {
        break;
    }
    $basic = !empty($usage['models'][$model]['basic']);
    $persistBasic = false;
    $seed = PASS_SEEDS[$pass];
    $path = '/models/' . rawurlencode($model) . ':generateContent';
    $retry = ['parse' => 0, 'transient' => 0, 'minute' => 0, 'bad' => 0];
    $r = null;
    while (true) {
        // 残り時間が少ないときは待ち時間も短くする（15秒〜request_timeout）
        $timeout = (int)min($cfg['timeout'], max(15, ceil($remaining())));
        $r = gemini_request($cfg, 'POST', $path, build_payload($files, $seed, $basic, $cfg), $timeout);
        usage_update($model, ['requests' => 1]);
        $attempt = ['model' => $model, 'status' => $r['status'], 'ms' => $r['ms'], 'basic' => $basic, 'seed' => $basic ? null : $seed];

        if ($r['status'] === 200) {
            [$list, $err] = $r['json'] !== null ? parse_receipts($r['json']) : [null, 'AIの応答を解析できませんでした'];
            $attempt['finish'] = $r['json']['candidates'][0]['finishReason'] ?? null;
            $attempt['tokens'] = array_intersect_key($r['json']['usageMetadata'] ?? [], array_flip(['promptTokenCount', 'candidatesTokenCount', 'thoughtsTokenCount', 'totalTokenCount']));
            if ($list !== null) {
                $attempts[] = $attempt;
                if ($persistBasic) {
                    usage_update($model, ['basic' => true]);
                }
                $receipts = $list;
                $usedModel = $model;
                break 2;
            }
            $attempt['error'] = $err;
            $attempts[] = $attempt;
            $lastParseError = $err;
            // 同じ種だと同じ崩れ方をしやすいので、種を変えてやり直す
            if ($retry['parse']++ < 1 && $remaining() >= 10) {
                $seed += 100;
                continue;
            }
            break;
        }
        $attempt['error'] = $r['timedOut'] ? 'timeout ' . $r['timeout'] . 's' : (string)($r['json']['error']['message'] ?? $r['error']);
        $attempts[] = $attempt;

        if ($r['timedOut']) {
            // 応答が返らないモデルは、しばらく後回しにして予備のモデルを先に使う
            usage_update($model, ['slowUntil' => time() + SLOW_COOLDOWN]);
            break;
        }
        if ($r['status'] === 404) {
            usage_update($model, ['missing' => true]);
            break;
        }
        if ($r['status'] === 429) {
            $q = classify_quota_error($r);
            // 1日の上限（または種類が分からない上限）は待っても回復しないので、再試行せず次のモデルへ
            if ($q['kind'] !== 'minute') {
                usage_update($model, ['exhausted' => true]);
                break;
            }
            $wait = max(2, (int)$q['retryAfter']);
            if ($retry['minute']++ < 1 && $wait <= 30 && $remaining() >= $wait + 10) {
                sleep($wait);
                continue;
            }
            break;
        }
        if ($r['status'] === 400 && !is_key_error($r) && $retry['bad']++ < 1) {
            // 追加設定を受け付けない（または原因不明の一時的な 400）。追加設定なしで1回だけやり直す
            if (!$basic) {
                $persistBasic = is_compat_error($r);
                $basic = true;
            }
            continue;
        }
        if (in_array($r['status'], [0, 500, 502, 503, 504], true) && $retry['transient']++ < 1 && $remaining() >= 10) {
            sleep(2);
            continue;
        }
        break;
    }
    $lastError = $r;
    // 上限（429）・モデルが無い（404）・時間切れや混雑（0/5xx）・応答の崩れ（200）なら次のモデルを試す。
    // キー不正・受け付けられない画像など他の理由は、モデルを変えても改善しないので打ち切る
    if (!in_array($r['status'], [200, 429, 404, 0, 500, 502, 503, 504], true)) {
        break;
    }
}

if ($receipts === null) {
    if ($lastError === null) {
        $error = '使えるモデルがすべて本日は使えません（上限に達したか、モデルが見つかりません：' . implode('、', $skipped) . '）。日本時間の午後4時（11月以降は午後5時）に回数がリセットされます。回数を増やすには、有料枠にするか、api/config.php に別のモデルを追加してください。';
        $code = 'quota_day';
    } elseif ($lastError['status'] === 200) {
        $error = $lastParseError . '。「もう一度読み取る」を押してください。';
        $code = 'parse';
    } else {
        $error = gemini_error_message($lastError);
        $code = $lastError['status'] === 429 ? 'quota' : ($lastError['timedOut'] ? 'timeout' : 'error');
    }
    log_request($cfg, ['pass' => $pass, 'files' => count($files), 'ok' => false, 'error' => $error,
        'elapsedMs' => (int)round((microtime(true) - $started) * 1000), 'attempts' => $attempts]);
    respond(502, ['ok' => false, 'code' => $code, 'error' => $error]);
}

// ファイルごとに振り分ける。1枚だけならすべてその画像のもの。
// 複数枚で番号が不正・欠落した領収書は unassigned に分け、画面側で要確認にする
$byFile = array_fill(0, count($files), []);
$unassigned = [];
foreach (array_values(array_filter(array_map('normalize_receipt', $receipts))) as $rc) {
    $idx = count($files) === 1 ? 0 : (isset($rc['image']) ? $rc['image'] - 1 : -1);
    unset($rc['image']);
    if ($idx >= 0 && $idx < count($files)) {
        $byFile[$idx][] = $rc;
    } else {
        $unassigned[] = $rc;
    }
}

$summary = fn($list) => array_map(fn($rc) => [$rc['category'], $rc['amount'], $rc['amount_text'], $rc['evidence'], $rc['confidence']], $list);
log_request($cfg, [
    'pass' => $pass, 'files' => count($files), 'ok' => true, 'model' => $usedModel,
    'elapsedMs' => (int)round((microtime(true) - $started) * 1000), 'attempts' => $attempts,
    'result' => array_map($summary, $byFile), 'unassigned' => $summary($unassigned),
]);
respond(200, [
    'ok' => true, 'pass' => $pass, 'model' => $usedModel, 'fallback' => $usedModel !== $cfg['models'][0],
    'files' => array_map(fn($list) => ['receipts' => $list], $byFile),
    'unassigned' => $unassigned,
]);

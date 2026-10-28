<?php
/**
 * 領収書AI読み取り API（Gemini 中継）
 *
 *   POST  JSON {"files": [{"mimeType": "image/jpeg", "data": "<base64>"}, ...], "pass": 1}
 *         → 画像（またはPDF）から高速代・駐車場代を読み取り、ファイルごとの結果をJSONで返す
 *         （旧形式 {"mimeType": ..., "data": ...} の1枚送信にも対応）
 *         pass … 何回目の読み取りか（1〜3）。回ごとに乱数の種（seed）を固定し、同じ画像なら同じ結果が出やすくする
 *   GET   ?action=status    → 接続テスト（キーは末尾4桁のみ返す）と、本日の使用回数
 *   GET   ?action=diagnose  → 本日の読み取りの状況の診断（上限・時間切れ・応答の崩れなど。usage.json と log.jsonl から集計）
 *   POST  ?action=reset     → 本日のモデルの状態（上限・後回し・設定の互換）の印を消す（回数の記録は残す）
 *
 * 精度と安定性のための設計
 *   - 1回の呼び出しで送る画像は既定で1枚（Google の資料で「文字の読み取りは1枚ずつの方が精度が良い」とされているため）
 *   - 画像の解像度を「高」に固定（mediaResolution）。金額を読み取った箇所の文字（amount_text）を書き写させ、金額と照合（evidence）
 *   - AIの考える量（thinkingLevel）は、1回目「低」・ダブルチェック「中」（config.php の thinking_level / thinking_level_recheck で変更可）
 *
 * Ver 2.1 で見直した点（「まとめて読むとほとんどエラー」「1枚でもときどきエラー」への対策）
 *   - 考える量は、1回目は「低」（速い）・ダブルチェック（2回目以降）は「中」。考える時間が長いと、時間切れになりやすかった
 *   - 待ち時間は、1枚あたり70秒＋まとめ読みは1枚増えるごとに25秒。時間切れの後は、考える量を下げて短い待ち時間でやり直す
 *   - 予備のモデルまで必ず進めるよう、時間の枠を広げた（200秒）
 *   - まとめ読み（2枚以上）は、失敗したら早めに諦めて画面側に知らせる（画面側が1枚ずつに切り替えて読み直す）
 *   - 失敗したときは、各回の結果（モデル・考える量・何秒で・何が起きたか）を画面に返し、原因が分かるようにした
 *
 * Ver 2.0 で見直した点（「ほとんど読み取れない」への対策）
 *   - Ver 1.9 の出力の上限（4,096トークン）は、Gemini 3 系では「考える分」も含めて数えられるため、考えている途中で
 *     打ち切られ、空の応答になることがあった → 上限を 16,384 に戻し、暴走は下の方法で止める
 *   - 応答を少しずつ受け取り（ストリーミング）、同じ文字の繰り返し・長すぎる出力を検知したら、その場で打ち切ってやり直す
 *   - 時間切れは「考え込んで止まらない」ことが多いため、まず種を変えて同じモデル（主モデル）でやり直す。2回続いたら予備のモデルへ
 *   - 種類の分からない 429 を「その日は使えない」と扱っていたため、一時的な混雑で主モデルが1日使えなくなることがあった
 *     → 1日の上限と明示されたときだけ、その日は使わない。それ以外は10分だけ後回し
 *   - 1分あたりの上限は、指示された秒数（60秒まで）待って同じモデルでやり直す（予備のモデルに逃げない）
 *   - 追加設定を受け付けないときは、考える量 → 全部 の順に外して試す
 *   - 上限に達したときは、Google の応答に含まれる「1日の上限回数」を記録し、診断で表示する
 *
 * APIキーは同じフォルダの config.php（無ければ環境変数 GEMINI_API_KEY）にのみ置き、ブラウザには渡さない。
 * キーを変更するときは config.php の gemini_api_key を書き換えるだけでよい（このファイルや index.html の変更は不要）。
 * 指示文とスキーマはこのファイルに固定しているため、汎用のGemini中継として悪用されることはない。
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const APP_VERSION = '2.2';   // index.html の APP_VERSION と揃えて上げる
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
const DEFAULT_TIMEOUT = 60;                      // 画像1枚の呼び出しで応答を待つ上限（秒）。config.php の request_timeout で変更
const DEFAULT_HEDGE_AFTER = 15;                  // 主モデルから15秒たっても答えが返らないとき、別のモデルでも同時に読み始める（秒）。config.php の hedge_after で変更
const COOLDOWNS = [60, 240, 720];                // 応答なし・混雑が続くモデルを後回しにする時間（秒）。1回目・2回目・3回目以降
const SLOW_RACE_COOLDOWN = 90;                   // 他のモデルに先を越されたモデルを、念のため後回しにする時間（秒）
const DEFAULT_MIN_INTERVAL = 12;                 // 画面側が、AI呼び出しの開始を空ける間隔（秒）。無料枠は1分に5回までのモデルが多いため。config.php の min_call_interval で変更
const BATCH_EXTRA_TIMEOUT = 25;                  // まとめ読みで、2枚目以降の1枚ごとに増やす待ち時間（秒）
const MAX_TIMEOUT = 240;                         // 待ち時間の最大（秒）
const TIME_BUDGET = 150;                         // 1回の読み取りで、再試行・別のモデルの起動を新しく始めてよい時間（秒）
const RATE_COOLDOWN = 600;                       // 種類の分からない 429（混雑など）のモデルを後回しにする時間（秒）
const DEFAULT_THINKING_LEVEL = 'low';            // AIの考える量・1回目（minimal / low / medium / high。'' でGeminiの既定＝high）
const DEFAULT_THINKING_RECHECK = 'medium';       // ダブルチェック（2回目以降）の考える量。1回目より低くはしない。'' なら1回目と同じ
const THINK_ORDER = ['minimal', 'low', 'medium', 'high'];
const THINKING_LEVELS = ['', 'minimal', 'low', 'medium', 'high'];
const MAX_OUTPUT_TOKENS = 16384;                 // 出力の上限（考える分を含む）。暴走したときの費用の歯止め。考える途中で切れないよう大きめ
const MAX_ANSWER_BYTES = 24000;                  // 画像1枚の答え（JSON）の長さの上限。超えたら暴走とみなして打ち切る（行数の多い明細の画像も読めるように大きめ）
const MAX_ANSWER_BYTES_LARGE = 80000;            // PDF・複数枚の答えの長さの上限（ETC利用明細など行数が多い場合）
const COMPAT_FULL = 0;                           // 追加設定をすべて付ける
const COMPAT_NO_THINKING = 1;                    // 考える量の設定だけ外す
const COMPAT_BASIC = 2;                          // 追加設定をすべて外す
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

    $timeout = max(5, min(MAX_TIMEOUT, (int)($config['request_timeout'] ?? DEFAULT_TIMEOUT)));
    $thinking = strtolower(trim((string)($config['thinking_level'] ?? DEFAULT_THINKING_LEVEL)));
    if (!in_array($thinking, THINKING_LEVELS, true)) {
        $thinking = DEFAULT_THINKING_LEVEL;
    }
    $recheck = strtolower(trim((string)($config['thinking_level_recheck'] ?? DEFAULT_THINKING_RECHECK)));
    if (!in_array($recheck, THINKING_LEVELS, true)) {
        $recheck = DEFAULT_THINKING_RECHECK;
    }

    return [
        'key' => $key, 'models' => $models, 'model' => $models[0], 'endpoint' => $endpoint, 'maxFiles' => $maxFiles,
        'log' => !array_key_exists('log_requests', $config) || (bool)$config['log_requests'],
        'timeout' => $timeout, 'thinking' => $thinking, 'recheck' => $recheck,
        // 再試行・予備モデルへの切替を始めてよい時間（秒）。通常は変更不要（動作確認用に短くできる）
        'budget' => max(5, min(600, (int)($config['time_budget'] ?? TIME_BUDGET))),
        'maxTokens' => max(2048, min(65536, (int)($config['max_output_tokens'] ?? MAX_OUTPUT_TOKENS))),
        'hedge' => max(3, min(60, (int)($config['hedge_after'] ?? DEFAULT_HEDGE_AFTER))),
        'minInterval' => max(0, min(60, (int)($config['min_call_interval'] ?? DEFAULT_MIN_INTERVAL))),
        'lite' => !array_key_exists('lite_fallback', $config) || (bool)$config['lite_fallback'],
        'modelsFixed' => isset($config['models']) && is_array($config['models']),
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

/** 次に1日の回数がリセットされる日時（日本時間の表記） */
function reset_time_jst(): string
{
    $next = new DateTimeImmutable('tomorrow', new DateTimeZone('America/Los_Angeles'));
    return '日本時間 ' . $next->setTimezone(new DateTimeZone('Asia/Tokyo'))->format('n月j日 G:i');
}

function jst_now(string $format = 'H:i'): string
{
    return (new DateTimeImmutable('now', new DateTimeZone('Asia/Tokyo')))->format($format);
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

/** モデルの状態（追加設定の互換レベル）。Ver 1.8〜1.9 の basic 印は「すべて外す」として読む */
function model_compat(array $mu): int
{
    if (isset($mu['compat'])) {
        return max(COMPAT_FULL, min(COMPAT_BASIC, (int)$mu['compat']));
    }
    return !empty($mu['basic']) ? COMPAT_BASIC : COMPAT_FULL;
}

/**
 * 使用回数・モデルの状態を保存する。requests は加算、null は削除、それ以外は上書き。
 * $fn を渡すと、ファイル全体（その日の記録）を書き換えられる。保存できない環境でも本来の処理は止めない
 */
function usage_update(?string $model, array $changes, ?callable $fn = null): void
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
        if ($model !== null) {
            $m = $u['models'][$model] ?? ['requests' => 0];
            foreach ($changes as $k => $v) {
                if ($k === 'requests') {
                    $m['requests'] = (int)($m['requests'] ?? 0) + (int)$v;
                } elseif ($v === null) {
                    unset($m[$k]);
                } else {
                    $m[$k] = $v;
                }
            }
            $u['models'][$model] = $m;
        }
        if ($fn !== null) {
            $u = $fn($u);
        }
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
    $entry = ['time' => jst_now('Y-m-d H:i:s'), 'day' => usage_day(), 'version' => APP_VERSION] + $entry;
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

function log_load(): array
{
    if (!is_file(LOG_FILE)) {
        return [];
    }
    $out = [];
    foreach (explode("\n", (string)@file_get_contents(LOG_FILE)) as $line) {
        $j = json_decode(trim($line), true);
        if (is_array($j)) {
            $out[] = $j;
        }
    }
    return $out;
}

/** 文字化けしないように切り出す（mbstring が無いサーバーでも動くように） */
function cut_text(string $s, int $start, ?int $length = null): string
{
    if (function_exists('mb_substr')) {
        return mb_substr($s, $start, $length);
    }
    return (string)substr($s, $start, $length ?? strlen($s));
}

function key_tail(string $key): string
{
    return '…' . substr($key, -4);
}

/** 接続テスト用の通常の呼び出し */
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
    if (is_array($json) && isset($json[0]['error'])) {
        $json = $json[0];
    }

    return [
        'status' => $raw === false ? 0 : $status,
        'json' => is_array($json) ? $json : null,
        'error' => $error,
        'timedOut' => $raw === false && $errno === 28,   // CURLE_OPERATION_TIMEDOUT
        'timeout' => $opts[CURLOPT_TIMEOUT],
        'runaway' => false,
        'ms' => (int)round((microtime(true) - $started) * 1000),
    ];
}

/** 答えの末尾が同じ文字・同じ並びの繰り返しになっているか（または長すぎるか）。なっていれば暴走とみなす */
function answer_runaway(string $text, int $maxBytes): bool
{
    if (strlen($text) > $maxBytes) {
        return true;
    }
    $tail = substr($text, -600);
    return preg_match('/(.{1,40}?)\1{9,}$/s', $tail, $m) === 1 && strlen($m[0]) >= 80;
}

/** SSE のイベント1つ分（data: {...}）を読み、答えの文字・終了理由・使用量を集める */
function sse_consume(array &$st, string $event, float $started): void
{
    $data = '';
    foreach (preg_split('/\r?\n/', $event) as $line) {
        if (strncmp($line, 'data:', 5) === 0) {
            $data .= ltrim(substr($line, 5));
        }
    }
    if ($data === '' || $data === '[DONE]') {
        return;
    }
    $j = json_decode($data, true);
    if (!is_array($j)) {
        $st['badEvents']++;
        return;
    }
    if (isset($j['error'])) {
        $st['streamError'] = $j;
        return;
    }
    $c = $j['candidates'][0] ?? null;
    foreach (($c['content']['parts'] ?? []) as $p) {
        if (!empty($p['thought']) || !isset($p['text']) || !is_string($p['text'])) {
            continue;
        }
        if ($st['ttft'] === null) {
            $st['ttft'] = (int)round((microtime(true) - $started) * 1000);
        }
        $st['text'] .= $p['text'];
    }
    if (!empty($c['finishReason'])) {
        $st['finish'] = (string)$c['finishReason'];
    }
    if (isset($j['usageMetadata']) && is_array($j['usageMetadata'])) {
        $st['usage'] = $j['usageMetadata'];
    }
    if (isset($j['promptFeedback']['blockReason'])) {
        $st['blocked'] = (string)$j['promptFeedback']['blockReason'];
    }
}

/**
 * 読み取りの呼び出し（ストリーミング）を1つ用意する。複数のモデルを同時に走らせて、先に終わった方を使うために、
 * curl_multi に登録できる形（stdClass）で返す。答えを少しずつ受け取り、同じ文字の繰り返し・長すぎる答えを検知したらその場で打ち切る。
 * $meta … 記録用の付属情報（seed・compat・thinking など）
 */
function attempt_start(array $cfg, string $model, array $body, int $timeout, int $maxBytes, array $meta = []): stdClass
{
    $a = new stdClass();
    foreach ($meta as $k => $v) {
        $a->$k = $v;
    }
    $a->model = $model;
    $a->timeout = $timeout;
    $a->started = microtime(true);
    $a->st = ['buf' => '', 'raw' => '', 'text' => '', 'finish' => null, 'usage' => null, 'blocked' => null,
        'ttft' => null, 'runaway' => false, 'streamError' => null, 'badEvents' => 0];
    $a->ch = curl_init($cfg['endpoint'] . '/models/' . rawurlencode($model) . ':streamGenerateContent?alt=sse');
    curl_setopt_array($a->ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        CURLOPT_HTTPHEADER => ['x-goog-api-key: ' . $cfg['key'], 'Content-Type: application/json', 'Accept: text/event-stream'],
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_WRITEFUNCTION => function ($ch, string $chunk) use ($a, $maxBytes): int {
            if ((int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE) !== 200) {
                if (strlen($a->st['raw']) < 1000000) {
                    $a->st['raw'] .= $chunk;
                }
                return strlen($chunk);
            }
            $a->st['buf'] .= $chunk;
            while (preg_match('/\r?\n\r?\n/', $a->st['buf'], $m, PREG_OFFSET_CAPTURE)) {
                $pos = $m[0][1];
                sse_consume($a->st, substr($a->st['buf'], 0, $pos), $a->started);
                $a->st['buf'] = substr($a->st['buf'], $pos + strlen($m[0][0]));
            }
            if (answer_runaway($a->st['text'], $maxBytes)) {
                $a->st['runaway'] = true;
                return 0;   // 受け取りを中止（curl はここで打ち切る）
            }
            return strlen($chunk);
        },
    ]);
    return $a;
}

/**
 * 終わった呼び出しの結果を、gemini_request と同じ形にまとめる（正常時の json は通常の応答と同じ形に組み立て直す）。
 * runaway … 暴走を検知して打ち切った
 */
function attempt_result(stdClass $a, int $errno, string $error): array
{
    $st = $a->st;
    $status = (int)curl_getinfo($a->ch, CURLINFO_RESPONSE_CODE);
    if (trim($st['buf']) !== '' && !$st['runaway'] && $status === 200) {
        sse_consume($st, $st['buf'], $a->started);
    }
    $r = [
        'status' => $status, 'json' => null, 'error' => $error, 'timedOut' => false, 'timeout' => $a->timeout,
        'runaway' => $st['runaway'], 'ttft' => $st['ttft'], 'text' => $st['text'],
        'ms' => (int)round((microtime(true) - $a->started) * 1000),
    ];
    if ($st['runaway']) {
        $r['status'] = 200;
        $r['json'] = ['usageMetadata' => $st['usage']];
        return $r;
    }
    if ($errno !== 0) {
        $r['status'] = 0;
        $r['timedOut'] = $errno === 28;   // CURLE_OPERATION_TIMEDOUT
        return $r;
    }
    if ($status !== 200) {
        $j = json_decode($st['raw'], true);
        if (is_array($j) && isset($j[0]['error'])) {
            $j = $j[0];
        }
        $r['json'] = is_array($j) ? $j : null;
        return $r;
    }
    if ($st['streamError'] !== null) {
        // 途中でエラーが届いた（混雑など）。その HTTP コードとして扱う
        $r['status'] = (int)($st['streamError']['error']['code'] ?? 500) ?: 500;
        $r['json'] = $st['streamError'];
        return $r;
    }
    $r['json'] = [
        'candidates' => [['content' => ['parts' => [['text' => $st['text']]]], 'finishReason' => $st['finish']]],
        'usageMetadata' => $st['usage'],
    ] + ($st['blocked'] !== null ? ['promptFeedback' => ['blockReason' => $st['blocked']]] : []);
    return $r;
}

/** curl のハンドルを、配列のキーに使える数にする（PHP 7 はリソース・PHP 8 はオブジェクト） */
function curl_key($ch): int
{
    return is_object($ch) ? spl_object_id($ch) : (int)$ch;
}

/**
 * モデルの「健康状態」を記録する。応答が返らない・混雑（503）が続くモデルは、続くほど長く後回しにして、
 * 混雑しているモデルに毎回何度も当てて、時間と1日の回数（無料枠）を無駄にしないようにする。
 *   $why … 'timeout'（応答なし）／'overload'（503 などの混雑）／'rate'（理由のはっきりしない 429）／'slow'（他のモデルに先を越された）
 */
function health_fail(string $model, string $why): void
{
    $m = usage_load()['models'][$model] ?? [];
    if ($why === 'slow') {
        // 先を越されただけ（失敗ではない）。短く後回しにするが、失敗の回数は増やさない
        $cool = SLOW_RACE_COOLDOWN;
        $fails = (int)($m['fails'] ?? 0);
    } else {
        $fails = (int)($m['fails'] ?? 0) + 1;
        $cool = $why === 'rate' ? RATE_COOLDOWN : COOLDOWNS[min($fails, count(COOLDOWNS)) - 1];
    }
    $until = max((int)($m['slowUntil'] ?? 0), time() + $cool);
    usage_update($model, ['fails' => $fails, 'slowUntil' => $until, 'slowReason' => $why]);
}

/** 成功したモデルの状態を元に戻す */
function health_ok(string $model): void
{
    usage_update($model, ['fails' => null, 'slowUntil' => null, 'slowReason' => null, 'lastOkAt' => jst_now()]);
}

/**
 * Gemini のモデル一覧から、最新の Flash-Lite 系モデル（gemini-数字-flash-lite）の名前を探す（12時間ごとに確認して記録）。
 * Flash 系が混雑・上限のときの「最後の予備」として使う。見つからない・取得できないときは null
 */
function discover_lite(array $cfg): ?string
{
    $file = DATA_DIR . '/models.json';
    $c = is_file($file) ? json_decode((string)@file_get_contents($file), true) : null;
    if (is_array($c) && ($c['endpoint'] ?? '') === $cfg['endpoint']
        && (int)($c['at'] ?? 0) > time() - (!empty($c['ok']) ? 43200 : 3600)) {
        return isset($c['lite']) && is_string($c['lite']) ? $c['lite'] : null;
    }
    $found = [];
    $token = '';
    $ok = true;
    for ($page = 0; $page < 3; $page++) {
        $r = gemini_request($cfg, 'GET', '/models?pageSize=200' . ($token !== '' ? '&pageToken=' . rawurlencode($token) : ''), null, 8);
        if ($r['status'] !== 200 || !is_array($r['json'])) {
            $ok = false;
            break;
        }
        foreach ((array)($r['json']['models'] ?? []) as $m) {
            $methods = (array)($m['supportedGenerationMethods'] ?? []);
            if (preg_match('#^models/(gemini-(\d+(?:\.\d+)?)-flash-lite)$#', (string)($m['name'] ?? ''), $mm)
                && (!$methods || in_array('generateContent', $methods, true))) {
                $found[$mm[2]] = $mm[1];
            }
        }
        $token = (string)($r['json']['nextPageToken'] ?? '');
        if ($token === '') {
            break;
        }
    }
    $lite = null;
    if ($ok && $found) {
        uksort($found, fn($a, $b) => version_compare((string)$b, (string)$a));
        $lite = reset($found);
    }
    if (is_dir(DATA_DIR) || @mkdir(DATA_DIR, 0755, true)) {
        @file_put_contents($file, json_encode(['at' => time(), 'ok' => $ok, 'endpoint' => $cfg['endpoint'], 'lite' => $lite], JSON_UNESCAPED_UNICODE));
    }
    return $lite;
}

/** 実際に使うモデルの一覧。設定したモデルの後ろに、最新の Flash-Lite 系を「最後の予備」として足す（config.php の lite_fallback で無効化。models を直接指定したときは足さない） */
function effective_models(array $cfg): array
{
    $models = $cfg['models'];
    if ($cfg['lite'] && !$cfg['modelsFixed']) {
        $lite = discover_lite($cfg);
        if ($lite !== null && !in_array($lite, $models, true)) {
            $models[] = $lite;
        }
    }
    return $models;
}

/**
 * Geminiの 429 を分類する。
 * 戻り値: ['kind' => 'minute'|'day'|'unknown', 'retryAfter' => 待つ秒数, 'quotaId' => …, 'limit' => 上限回数, 'freeTier' => 無料枠か]
 */
function classify_quota_error(array $r): array
{
    $err = $r['json']['error'] ?? [];
    $kind = 'unknown';
    $retry = null;
    $quotaId = null;
    $limit = null;
    foreach (($err['details'] ?? []) as $d) {
        if (isset($d['retryDelay']) && preg_match('/^([\d.]+)s$/', (string)$d['retryDelay'], $m)) {
            $retry = (int)ceil((float)$m[1]);
        }
        foreach (($d['violations'] ?? []) as $v) {
            $id = (string)($v['quotaId'] ?? '');
            if ($quotaId === null || preg_match('/PerDay/i', $id)) {
                $quotaId = $id;
                $limit = isset($v['quotaValue']) && is_numeric($v['quotaValue']) ? (int)$v['quotaValue'] : $limit;
            }
        }
    }
    $text = $quotaId . ' ' . (string)($err['message'] ?? '');
    if (preg_match('/PerDay|per day|daily/i', $text)) {
        $kind = 'day';
    } elseif (preg_match('/PerMinute|per minute/i', $text)) {
        $kind = 'minute';
    }
    return ['kind' => $kind, 'retryAfter' => $retry, 'quotaId' => $quotaId, 'limit' => $limit,
        'freeTier' => $quotaId !== null && stripos($quotaId, 'FreeTier') !== false];
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
        return 'AIの応答に時間がかかりすぎたため中断しました（' . $r['timeout'] . '秒）。Gemini が混雑しているか、AIが考え込んで止まらなくなった可能性があります。少し時間をおいて「もう一度読み取る」を押してください。';
    }
    if ($r['status'] === 0) {
        return 'Gemini API に接続できませんでした（' . $r['error'] . '）。時間をおいてもう一度お試しください。';
    }
    $msg = (string)($r['json']['error']['message'] ?? '');
    if (is_key_error($r)) {
        return 'APIキーが無効か期限切れです。api/config.php の gemini_api_key を新しいキーに更新してください。';
    }
    if ($r['status'] === 401 || $r['status'] === 403) {
        return 'APIキーにこのAPIの利用権限がありません。Google AI Studio でキーを確認し、api/config.php を更新してください。';
    }
    if ($r['status'] === 404) {
        return 'モデルが見つかりません。api/config.php の model を確認してください。' . ($msg !== '' ? '（' . $msg . '）' : '');
    }
    if ($r['status'] === 429) {
        $q = classify_quota_error($r);
        if ($q['kind'] === 'day') {
            return 'Gemini API の1日の利用上限に達しました' . ($q['limit'] ? '（' . ($q['freeTier'] ? '無料枠' : '') . '1日 ' . $q['limit'] . ' 回まで）' : '')
                . '。' . reset_time_jst() . ' に回数がリセットされます。回数を増やすには有料枠にしてください（同梱の README.txt の「3-2」を参照）。';
        }
        return 'Gemini API が混み合っているか、1分あたりの上限に達しました。少し時間をおいて「もう一度読み取る」を押してください。';
    }
    if (in_array($r['status'], [500, 502, 503, 504], true)) {
        return 'Gemini 側が混雑しているか一時的な不具合です（HTTP ' . $r['status'] . '）。時間をおいてもう一度お試しください。';
    }
    return 'Gemini API エラー（HTTP ' . $r['status'] . '）' . ($msg !== '' ? '：' . $msg : '');
}

/**
 * その読み取りで使う「考える量」を決める。null は設定を付けない（Gemini の既定＝high）
 *   1回目は thinking_level、ダブルチェック（2回目以降）は thinking_level_recheck（1回目より低くはしない）
 *   $shifted … 時間切れになったあとのやり直し。考えすぎて止まらないことがあるため、「低」まで下げる
 */
function thinking_for(array $cfg, int $pass, bool $shifted): ?string
{
    $level = $cfg['thinking'];
    if ($pass >= 2 && $cfg['recheck'] !== '' && $level !== '') {
        $level = THINK_ORDER[max((int)array_search($level, THINK_ORDER, true), (int)array_search($cfg['recheck'], THINK_ORDER, true))];
    } elseif ($pass >= 2 && $cfg['recheck'] !== '' && $level === '') {
        $level = '';   // 1回目が Gemini の既定（高）なら、2回目もそのまま
    }
    if ($shifted && ($level === '' || (int)array_search($level, THINK_ORDER, true) > 1)) {
        $level = 'low';
    }
    return $level === '' ? null : $level;
}

/** 失敗したときに画面へ返す、各回の結果の短い説明（原因の調査用） */
function attempt_text(array $a): string
{
    $err = (string)($a['error'] ?? '');
    $sec = round(((int)($a['ms'] ?? 0)) / 1000, 1);
    if (!empty($a['aborted'])) {
        $why = '他のモデルが先に読み終えたため中止（' . $sec . '秒）';
    } elseif (!empty($a['runaway'])) {
        $why = '答えが崩れた（同じ文字の繰り返し）';
    } elseif ((int)($a['status'] ?? 0) === 0 && strncmp($err, 'timeout', 7) === 0) {
        $why = '応答なし（' . $sec . '秒で時間切れ）';
    } elseif ((int)($a['status'] ?? 0) === 200) {
        $why = $err !== '' ? $err : '成功';
    } else {
        $why = 'HTTP ' . (int)($a['status'] ?? 0) . ($err !== '' ? ' ' . cut_text($err, 0, 80) : '') . '（' . $sec . '秒）';
    }
    return (string)$a['model'] . (!empty($a['thinking']) ? '（考える量 ' . $a['thinking'] . '）' : '') . (!empty($a['hedge']) ? '【同時に開始】' : '') . '：' . $why;
}

/** $multi … 複数枚をまとめて送るときだけ、どの画像の領収書かを表す image を求める（1枚のときは不要な数字の項目を減らす） */
function receipt_schema(bool $ordering, bool $multi): array
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
    if ($ordering) {
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
 * $compat … COMPAT_FULL：追加設定（解像度・seed・項目の順番・考える量）をすべて付ける ／ COMPAT_NO_THINKING：考える量だけ外す ／
 *           COMPAT_BASIC：すべて外す（Gemini側が受け付けないときの予備）。出力の上限は標準の設定なので常に付ける
 */
function build_payload(array $files, int $seed, int $compat, ?string $thinking, array $cfg): array
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
    $config = [
        'responseMimeType' => 'application/json',
        'responseSchema' => receipt_schema($compat !== COMPAT_BASIC, $multi),
        'maxOutputTokens' => $cfg['maxTokens'],
    ];
    if ($compat !== COMPAT_BASIC) {
        $config['mediaResolution'] = 'MEDIA_RESOLUTION_HIGH';   // 細かい文字まで読めるよう、画像の解像度を「高」に固定
        $config['seed'] = $seed;                               // 同じ画像・同じ回なら、同じ結果が出やすくする
    }
    if ($compat === COMPAT_FULL && $thinking !== null) {
        $config['thinkingConfig'] = ['thinkingLevel' => $thinking];
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
    if ($text === '') {
        $why = $finish === 'MAX_TOKENS' ? '考える途中で出力の上限に達しました'
            : ($finish !== '' ? $finish : (string)($json['promptFeedback']['blockReason'] ?? '不明'));
        return [null, 'AIから読み取り結果が返りませんでした（' . $why . '）'];
    }
    $parsed = json_decode($text, true);
    if (!is_array($parsed) && preg_match('/\{[\s\S]*\}/', $text, $m)) {
        $parsed = json_decode($m[0], true);
    }
    if (!is_array($parsed) || !isset($parsed['receipts']) || !is_array($parsed['receipts'])) {
        return [null, $finish === 'MAX_TOKENS' ? 'AIの応答が出力の上限で途中で切れました' : 'AIの応答を解析できませんでした'];
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


/** 本日の読み取りの状況を集計して、原因の見立て（findings）を作る */
function diagnose(array $cfg): array
{
    $usage = usage_load();
    $day = $usage['day'];
    $now = time();
    $primary = $cfg['models'][0];
    $models = [];
    foreach ($cfg['models'] as $i => $name) {
        $mu = $usage['models'][$name] ?? [];
        $slowUntil = (int)($mu['slowUntil'] ?? 0);
        $models[] = [
            'name' => $name, 'primary' => $i === 0, 'auto' => !in_array($name, $cfg['modelsConfigured'] ?? $cfg['models'], true),
            'fails' => (int)($mu['fails'] ?? 0), 'lastOkAt' => $mu['lastOkAt'] ?? null,
            'stat' => ['ok' => 0, 'timeouts' => 0, 'overload' => 0, 'quota' => 0, 'aborted' => 0],
            'requests' => (int)($mu['requests'] ?? 0),
            'exhausted' => !empty($mu['exhausted']),
            'exhaustedAt' => $mu['exhaustedAt'] ?? null,
            'exhaustedAfter' => isset($mu['exhaustedAfter']) ? (int)$mu['exhaustedAfter'] : null,
            'limit' => isset($mu['limit']) ? (int)$mu['limit'] : null,
            'freeTier' => !empty($mu['freeTier']),
            'missing' => !empty($mu['missing']),
            'compat' => model_compat($mu),
            'slow' => $slowUntil > $now,
            'slowUntil' => $slowUntil > $now ? (new DateTimeImmutable('@' . $slowUntil))->setTimezone(new DateTimeZone('Asia/Tokyo'))->format('H:i') : null,
            'slowReason' => $slowUntil > $now ? ($mu['slowReason'] ?? null) : null,
        ];
    }

    // 本日（太平洋時間の日付）の記録だけを集計する
    $entries = array_values(array_filter(log_load(), function ($e) use ($day) {
        if (isset($e['day'])) {
            return $e['day'] === $day;
        }
        $t = DateTimeImmutable::createFromFormat('Y-m-d H:i:s', (string)($e['time'] ?? ''), new DateTimeZone('Asia/Tokyo'));
        return $t && $t->setTimezone(new DateTimeZone('America/Los_Angeles'))->format('Y-m-d') === $day;
    }));
    $st = ['reads' => count($entries), 'ok' => 0, 'failed' => 0, 'fallbackReads' => 0, 'attempts' => 0, 'timeouts' => 0,
        'runaways' => 0, 'emptyAnswers' => 0, 'quotaDay' => 0, 'quotaMinute' => 0, 'quotaOther' => 0, 'serverErrors' => 0,
        'badRequests' => 0, 'evidenceIssues' => 0, 'lowConfidence' => 0, 'receipts' => 0];
    $okSeconds = [];
    $thoughts = [];
    $failures = [];
    $recentErrors = [];
    foreach ($entries as $e) {
        if (!empty($e['ok'])) {
            $st['ok']++;
            if (($e['model'] ?? $primary) !== $primary) {
                $st['fallbackReads']++;
            }
            if (isset($e['elapsedMs'])) {
                $okSeconds[] = $e['elapsedMs'] / 1000;
            }
            foreach (array_merge(...array_values(array_map(fn($x) => is_array($x) ? $x : [], (array)($e['result'] ?? [])))) as $rc) {
                if (!is_array($rc)) {
                    continue;
                }
                $st['receipts']++;
                if (isset($rc[1]) && $rc[1] !== null && ($rc[3] ?? 'ok') !== 'ok') {
                    $st['evidenceIssues']++;
                }
                if (isset($rc[4]) && is_numeric($rc[4]) && $rc[4] < 0.8) {
                    $st['lowConfidence']++;
                }
            }
        } else {
            $st['failed']++;
            $code = (string)($e['code'] ?? 'error');
            $failures[$code] = ($failures[$code] ?? 0) + 1;
            $recentErrors[] = ['time' => $e['time'] ?? '', 'error' => (string)($e['error'] ?? '')];
        }
        foreach ((array)($e['attempts'] ?? []) as $a) {
            $s = (int)($a['status'] ?? 0);
            $err = (string)($a['error'] ?? '');
            $mi = array_search((string)($a['model'] ?? ''), array_column($models, 'name'), true);
            if (!empty($a['aborted'])) {
                $st['aborted'] = ($st['aborted'] ?? 0) + 1;
                if ($mi !== false) {
                    $models[$mi]['stat']['aborted']++;
                }
                continue;   // 他のモデルが先に読み終えたための中止は、失敗として数えない
            }
            $st['attempts']++;
            if ($mi !== false) {
                if ($s === 200 && $err === '') {
                    $models[$mi]['stat']['ok']++;
                } elseif ($s === 0 && strncmp($err, 'timeout', 7) === 0) {
                    $models[$mi]['stat']['timeouts']++;
                } elseif ($s >= 500 || $s === 0) {
                    $models[$mi]['stat']['overload']++;
                } elseif ($s === 429) {
                    $models[$mi]['stat']['quota']++;
                }
            }
            if (!empty($a['runaway'])) {
                $st['runaways']++;
            } elseif ($s === 0 && strncmp($err, 'timeout', 7) === 0) {
                $st['timeouts']++;
            } elseif ($s === 200 && strpos($err, '返りませんでした') !== false) {
                $st['emptyAnswers']++;
            } elseif ($s === 429) {
                $kind = (string)($a['quota'] ?? '');
                $st[$kind === 'day' ? 'quotaDay' : ($kind === 'minute' ? 'quotaMinute' : 'quotaOther')]++;
            } elseif ($s >= 500 || ($s === 0 && strncmp($err, 'timeout', 7) !== 0)) {
                $st['serverErrors']++;
            } elseif ($s === 400) {
                $st['badRequests']++;
            }
            if (isset($a['tokens']['thoughtsTokenCount'])) {
                $thoughts[] = (int)$a['tokens']['thoughtsTokenCount'];
            }
        }
    }
    $st['avgSeconds'] = $okSeconds ? round(array_sum($okSeconds) / count($okSeconds), 1) : null;
    $st['maxSeconds'] = $okSeconds ? round(max($okSeconds), 1) : null;
    $st['avgThoughts'] = $thoughts ? (int)round(array_sum($thoughts) / count($thoughts)) : null;

    // ---- 見立て ----
    $find = [];
    $add = function (string $level, string $text) use (&$find) {
        $find[] = ['level' => $level, 'text' => $text];
    };
    $usable = array_filter($models, fn($m) => !$m['exhausted'] && !$m['missing']);
    $p = $models[0];
    $limitText = fn($m) => $m['limit'] ? '（' . ($m['freeTier'] ? '無料枠：' : '') . '1日 ' . $m['limit'] . ' 回まで）' : '';
    $quotaCause = false;
    if (!$usable) {
        $quotaCause = true;
        $add('bad', '本日は、設定されているすべてのモデルが1日の上限に達しています（または見つかりません）。今日の上限が原因です。' . reset_time_jst() . ' 以降にもう一度お試しください。');
    } elseif ($p['exhausted']) {
        $quotaCause = true;
        $add('warn', '主モデル ' . $p['name'] . ' は ' . ($p['exhaustedAt'] ?? '本日') . ' に1日の上限に達しました' . $limitText($p)
            . '。それ以降は予備のモデルで読み取っているため、精度が下がっています。回数のリセットは ' . reset_time_jst() . '。');
    }
    if ($p['missing']) {
        $add('bad', '主モデル ' . $p['name'] . ' が見つかりません（モデル名の変更・提供終了の可能性）。api/config.php の model を確認してください。');
    }
    if ($p['slow'] && !$p['exhausted']) {
        $reason = ['rate' => '混み合っている（429）', 'timeout' => 'が返らない', 'overload' => '混雑している（503）', 'slow' => '遅かった'][(string)$p['slowReason']] ?? '不調';
        $add('warn', '主モデルの応答が' . $reason . 'ため、' . $p['slowUntil'] . ' まで予備のモデルを先に使っています。');
    }
    // Google 側の混雑：本日、成功が無く、応答なし・混雑ばかりのモデル
    $congested = false;
    foreach ($models as $m) {
        $x = $m['stat'];
        if ($x['ok'] === 0 && $x['timeouts'] + $x['overload'] >= 3) {
            $congested = true;
            $add('warn', $m['name'] . ' は本日、成功 0 回・応答なし ' . $x['timeouts'] . ' 回・混雑（503 など）' . $x['overload'] . ' 回です。Google 側の混雑が原因と思われます'
                . '（Google の開発者フォーラムでも、無料枠の新しい Flash 系モデルで同様の報告があります）。こちらでは直せないため、自動で他のモデルに切り替えて読み取ります。');
        } elseif ($x['ok'] > 0 && $x['timeouts'] + $x['overload'] >= 3 && ($x['timeouts'] + $x['overload']) >= $x['ok']) {
            $congested = true;
            $add('info', $m['name'] . ' は本日、成功 ' . $x['ok'] . ' 回に対し、応答なし ' . $x['timeouts'] . ' 回・混雑 ' . $x['overload'] . ' 回と不安定です（Google 側の混雑）。');
        }
    }
    foreach (array_slice($models, 1) as $m) {
        if ($m['exhausted'] && $m['limit']) {
            $add('info', '予備のモデル ' . $m['name'] . ' も ' . ($m['exhaustedAt'] ?? '本日') . ' に上限に達しました' . $limitText($m) . '。');
        }
    }
    $trouble = $st['timeouts'] + $st['serverErrors'];
    if ($trouble >= 3 && $trouble * 3 >= $st['attempts'] * 1 && $st['ok'] * 2 <= $trouble) {
        $congested = true;
        $add('warn', 'Gemini の応答が返らない・混雑（503 など）が多いです（呼び出し ' . $st['attempts'] . ' 回中、応答なし ' . $st['timeouts'] . ' 回・混雑 ' . $st['serverErrors'] . ' 回）。'
            . '上限ではなく、Google 側の混雑です。混雑しているモデルは自動で後回しにして、他のモデルで読み取ります。');
    } elseif ($st['timeouts'] >= 2 && $st['timeouts'] * 4 >= $st['attempts']) {
        $add('warn', 'Gemini の応答が返らない（時間切れ）ことが多いです（呼び出し ' . $st['attempts'] . ' 回中 ' . $st['timeouts'] . ' 回）。上限ではなく、Gemini 側の混雑か、AIが考え込んで止まらなくなる不具合です。');
    }
    if ($st['runaways'] >= 2) {
        $add('warn', 'AIの応答が崩れる（同じ文字の繰り返し）ことが ' . $st['runaways'] . ' 回ありました。検知してすぐ打ち切り、やり直しています。上限ではなく Gemini 側の不具合です。');
    }
    if ($st['emptyAnswers'] >= 2) {
        $add('warn', 'AIから空の応答が ' . $st['emptyAnswers'] . ' 回返りました（考える途中で止まった可能性）。');
    }
    if ($st['quotaMinute'] >= 3) {
        $add('info', '1分あたりの上限に ' . $st['quotaMinute'] . ' 回達しました（自動で待ってやり直しています）。');
    }
    if ($st['quotaOther'] >= 2) {
        $add('info', 'Gemini から「混み合っています（429）」という応答が ' . $st['quotaOther'] . ' 回ありました。そのモデルは10分間後回しにしています。');
    }
    if ($st['fallbackReads'] > 0) {
        $add('info', '予備のモデルで読み取った回数：' . $st['fallbackReads'] . ' 回（主モデルより精度が下がることがあります）。');
    }
    if ($st['receipts'] >= 3 && ($st['evidenceIssues'] + $st['lowConfidence']) * 3 >= $st['receipts']) {
        $add('info', '読み取った領収書 ' . $st['receipts'] . ' 件のうち、根拠の不一致が ' . $st['evidenceIssues'] . ' 件、確信度が低いものが ' . $st['lowConfidence'] . ' 件ありました。画像が暗い・斜め・一部が写っていない可能性があります（撮影のコツを参照）。');
    }
    if ($p['compat'] !== COMPAT_FULL) {
        $add('info', '主モデルが一部の設定（' . ($p['compat'] === COMPAT_NO_THINKING ? '考える量' : '解像度・考える量など') . '）を受け付けないため、その設定を外して送っています。');
    }
    if (!$cfg['log']) {
        $add('info', '読み取りの記録（log_requests）が無効のため、時間切れ・応答の崩れなどの集計はできません。');
    } elseif ($st['reads'] === 0) {
        $add('info', '本日（Google の日付で ' . $day . '）の読み取りの記録はまだありません。');
    }
    $hasProblem = (bool)array_filter($find, fn($f) => in_array($f['level'], ['bad', 'warn'], true));
    if (!$hasProblem && $st['reads'] > 0 && $st['failed'] === 0) {
        $add('ok', '本日の記録では、上限や応答の問題は見つかりませんでした。');
    }
    $headline = $quotaCause ? '今日の上限が原因です'
        : ($congested ? '上限ではありません（Gemini 側の混雑です）' : ($hasProblem ? '上限ではありません（Gemini 側の応答の問題です）' : ($st['reads'] > 0 ? '上限には達していません' : '本日の記録はまだありません')));

    return [
        'ok' => true, 'version' => APP_VERSION, 'day' => $day, 'resetAt' => reset_time_jst(), 'headline' => $headline,
        'findings' => $find, 'models' => $models, 'stats' => $st, 'failures' => $failures,
        'recentErrors' => array_slice(array_reverse($recentErrors), 0, 5),
        'settings' => ['thinkingLevel' => $cfg['thinking'], 'thinkingRecheck' => $cfg['recheck'], 'timeout' => $cfg['timeout'], 'maxFiles' => $cfg['maxFiles'], 'log' => $cfg['log']],
    ];
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$action = (string)($_GET['action'] ?? '');

// ---- 接続テスト（APIキー変更後の確認用）。本日の使用回数も返す ----
if ($method === 'GET' && $action === 'status') {
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
    $listed = effective_models($cfg);
    foreach ($listed as $m) {
        $mu = $usage['models'][$m] ?? [];
        $slowUntil = (int)($mu['slowUntil'] ?? 0);
        $models[] = [
            'name' => $m,
            'requests' => (int)($mu['requests'] ?? 0),
            'exhausted' => !empty($mu['exhausted']),
            'limit' => isset($mu['limit']) ? (int)$mu['limit'] : null,
            'missing' => !empty($mu['missing']),
            'slow' => $slowUntil > time(),
            'slowUntil' => $slowUntil > time() ? (new DateTimeImmutable('@' . $slowUntil))->setTimezone(new DateTimeZone('Asia/Tokyo'))->format('H:i') : null,
            'slowReason' => $slowUntil > time() ? ($mu['slowReason'] ?? null) : null,
            'auto' => !in_array($m, $cfg['models'], true),
        ];
    }
    respond(200, [
        'ok' => true, 'version' => APP_VERSION, 'model' => $cfg['model'], 'keyTail' => key_tail($cfg['key']),
        'maxFiles' => $cfg['maxFiles'], 'usageDay' => $usage['day'], 'resetAt' => reset_time_jst(), 'models' => $models,
        'usageWritable' => is_dir(DATA_DIR) ? is_writable(DATA_DIR) : is_writable(__DIR__),
        'logEnabled' => $cfg['log'], 'thinkingLevel' => $cfg['thinking'], 'timeout' => $cfg['timeout'],
        'minInterval' => $cfg['minInterval'],
    ]);
}

// ---- 診断（APIは呼び出さない。サーバーの記録だけを集計する） ----
if ($method === 'GET' && $action === 'diagnose') {
    $cfg = load_config();
    $cfg['modelsConfigured'] = $cfg['models'];
    $cfg['models'] = effective_models($cfg);
    respond(200, diagnose($cfg));
}

// ---- モデルの状態の印を消す（回数の記録は残す） ----
if ($method === 'POST' && $action === 'reset') {
    usage_update(null, [], function (array $u): array {
        foreach ($u['models'] as $name => $m) {
            foreach (['exhausted', 'exhaustedAt', 'exhaustedAfter', 'limit', 'freeTier', 'missing', 'basic', 'compat', 'slowUntil', 'slowReason', 'fails'] as $k) {
                unset($m[$k]);
            }
            $u['models'][$name] = $m;
        }
        return $u;
    });
    respond(200, ['ok' => true]);
}

if ($method !== 'POST') {
    respond(405, ['ok' => false, 'error' => 'POST で送信してください。']);
}

$cfg = load_config();
if ($cfg['key'] === '') {
    respond(500, ['ok' => false, 'error' => 'APIキーが未設定です。api/config.sample.php を config.php にコピーしてキーを設定してください。']);
}

@set_time_limit(600);
$cfg['models'] = effective_models($cfg);   // 設定したモデル + 最後の予備（最新の Flash-Lite 系）
$models = $cfg['models'];

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

// ---- 読み取り：複数のモデルを、必要に応じて同時に走らせ、先に正しく読めたものを使う ----
//   Gemini の新しいモデルは、混雑（503）や、応答が返らない状態になることがある（特に無料枠）。
//   そのため、混雑しているモデルを何度も待たずに、次のモデルへ移る。
//     ・主モデルから hedge_after 秒（既定15秒）たっても答えが返らなければ、次のモデルでも同時に読み始める（さらに15秒ごとに次のモデルを追加）
//       → 先に正しく読めた方を使い、残りは打ち切る
//     ・503・応答なし（時間切れ）・理由のはっきりしない 429 は、そのモデルを後回しにして（続くほど長く）、すぐ次のモデルへ
//     ・応答の崩れ（同じ文字の繰り返し・JSONが壊れている・結果が空）… 乱数の種を変えて、同じモデルでもう1回
//     ・1日の上限（429）… その日は使わない（Google の応答にある上限回数を記録）
//     ・1分あたりの上限（429）… 他のモデルがあれば次へ。他に無ければ、指示された秒数（60秒まで）待って最大2回
//     ・受け付けられない（400）… 考える量 → 追加設定すべて の順に外して最大2回
//     ・まとめ読み（2枚以上）は、並行して走らせず、失敗したら早めに諦める（画面側が1枚ずつに切り替える）
//   新しい呼び出し・再試行は、読み取り開始から time_budget 秒までに始めるものに限る
$usage = usage_load();
$now = time();
$order = [];
$later = [];
$skipped = [];
foreach ($models as $m) {
    $mu = $usage['models'][$m] ?? [];
    if (!empty($mu['exhausted']) || !empty($mu['missing'])) {
        $skipped[] = $m;
    } elseif ((int)($mu['slowUntil'] ?? 0) > $now) {
        $later[$m] = (int)$mu['slowUntil'];
    } else {
        $order[] = $m;
    }
}
asort($later);   // 後回し中のモデルは、期限が近い順
$candidates = array_merge($order, array_keys($later));

$isBatch = count($files) > 1;
$maxBytes = ($isBatch || in_array('application/pdf', array_column($files, 'mimeType'), true)) ? MAX_ANSWER_BYTES_LARGE : MAX_ANSWER_BYTES;
// 待ち時間は枚数に応じて延ばす（まとめ読みは考える量も出力も増えるため）
$baseTimeout = min(MAX_TIMEOUT, $cfg['timeout'] + BATCH_EXTRA_TIMEOUT * (count($files) - 1));
$hedgeAfter = $isBatch ? 1e9 : (float)$cfg['hedge'];
$maxParallel = $isBatch ? 1 : 3;

$started = microtime(true);
$elapsed = fn(): float => microtime(true) - $started;
$receipts = null;
$usedModel = null;
$attempts = [];        // 画面・記録に返す、各呼び出しの結果
$failures = [];        // 失敗した呼び出しの結果（最後の失敗が、エラーメッセージの元になる）
$lastParseError = null;
$running = [];         // 実行中の呼び出し（curl のハンドル番号 => stdClass）
$retryQueue = [];      // 同じモデルでの再試行の予約 [['model' =>, 'at' =>]]
$next = 0;             // 次に起動する候補の番号
$lastLaunch = 0.0;
$anyTimeout = false;   // この読み取りで、応答なし（時間切れ）があったか（あれば、その後の呼び出しは考える量を下げる）
$fatal = false;        // キー不正など、モデルを変えても直らない失敗
$state = [];           // モデルごとの再試行の状態
$mh = curl_multi_init();

$launch = function (string $model, bool $hedge) use (&$state, &$running, &$lastLaunch, &$anyTimeout, $mh, $cfg, $files, $pass, $maxBytes, $baseTimeout, $usage, $elapsed): void {
    if (!isset($state[$model])) {
        $state[$model] = ['compat' => model_compat($usage['models'][$model] ?? []), 'persist' => false, 'seed' => PASS_SEEDS[$pass],
            'parse' => 0, 'bad' => 0, 'minute' => 0, 'transient' => 0];
    }
    $ms = &$state[$model];
    $left = $cfg['budget'] - $elapsed();
    $timeout = (int)min($baseTimeout, max(15, ceil($left + 20)));
    $thinking = thinking_for($cfg, $pass, $anyTimeout);
    $a = attempt_start($cfg, $model, build_payload($files, $ms['seed'], $ms['compat'], $thinking, $cfg), $timeout, $maxBytes, [
        'seed' => $ms['compat'] === COMPAT_BASIC ? null : $ms['seed'],
        'compat' => $ms['compat'],
        'thinking' => $ms['compat'] === COMPAT_FULL ? $thinking : null,
        'at' => (int)round($elapsed() * 1000),
        'hedge' => $hedge,
    ]);
    curl_multi_add_handle($mh, $a->ch);
    $running[curl_key($a->ch)] = $a;
    $lastLaunch = microtime(true);
    usage_update($model, ['requests' => 1]);
};

/** 実行中の呼び出しをすべて打ち切る（先に読めたものがあるとき・続けられない失敗のとき） */
$abortAll = function (?string $winner, ?float $winnerStarted = null) use (&$running, &$attempts, $mh, $started): void {
    foreach ($running as $key => $a) {
        curl_multi_remove_handle($mh, $a->ch);
        curl_close($a->ch);
        $ms = (int)round((microtime(true) - $a->started) * 1000);
        $attempts[] = ['model' => $a->model, 'status' => 0, 'ms' => $ms, 'limit' => $a->timeout, 'at' => $a->at, 'hedge' => $a->hedge,
            'compat' => $a->compat, 'seed' => $a->seed, 'thinking' => $a->thinking, 'aborted' => true,
            'error' => $winner !== null ? 'aborted: ' . $winner . ' が先に完了' : 'aborted'];
        // 先に始めたのに、後から始めた方に先を越されたモデルは、念のため少しの間は後回しにする（次の読み取りを、速い方から始められるように）
        if ($winner !== null && $a->model !== $winner && $winnerStarted !== null && $a->started < $winnerStarted) {
            health_fail($a->model, 'slow');
        }
    }
    $running = [];
};

while (true) {
    if ($fatal) {
        break;   // キー不正など、続けても直らない
    }
    $now = microtime(true);
    $canLaunch = $next === 0 || $elapsed() < $cfg['budget'] - 10;   // 最初の1回は必ず起動する

    // 1) 起動：予約済みの再試行 → 動いているものが無ければ次の候補 → hedge 時間を過ぎたら次の候補を追加
    if ($canLaunch) {
        foreach ($retryQueue as $i => $q) {
            if ($q['at'] <= $now) {
                unset($retryQueue[$i]);
                $launch($q['model'], false);
            }
        }
        $retryQueue = array_values($retryQueue);
        $wantNext = (!$running && !$retryQueue)
            || ($running && count($running) < $maxParallel && $now - $lastLaunch >= $hedgeAfter);
        if ($wantNext && $next < count($candidates)) {
            $launch($candidates[$next], (bool)$running);
            $next++;
        }
    }
    if (!$running && (!$retryQueue || !$canLaunch) && ($next >= count($candidates) || !$canLaunch)) {
        break;   // もう起動できるものが無く、動いているものも無い
    }

    // 2) 進める。終わったものを順に処理する
    do {
        $code = curl_multi_exec($mh, $active);
    } while ($code === CURLM_CALL_MULTI_PERFORM);
    if (curl_multi_select($mh, 0.2) === -1) {
        usleep(100000);
    }

    while ($info = curl_multi_info_read($mh)) {
        $key = curl_key($info['handle']);
        if (!isset($running[$key])) {
            continue;
        }
        $a = $running[$key];
        unset($running[$key]);
        $model = $a->model;
        $ms = &$state[$model];
        $r = attempt_result($a, (int)$info['result'], curl_error($a->ch));
        curl_multi_remove_handle($mh, $a->ch);
        curl_close($a->ch);

        $attempt = ['model' => $model, 'status' => $r['status'], 'ms' => $r['ms'], 'limit' => $a->timeout, 'at' => $a->at, 'hedge' => $a->hedge,
            'compat' => $a->compat, 'seed' => $a->seed, 'thinking' => $a->thinking, 'ttft' => $r['ttft'] ?? null];
        $retryAt = $now + 0.0;

        if ($r['status'] === 200) {
            [$list, $err] = $r['runaway']
                ? [null, 'AIの応答が崩れたため打ち切りました（同じ文字の繰り返し）']
                : parse_receipts($r['json'] ?? []);
            $attempt['finish'] = $r['json']['candidates'][0]['finishReason'] ?? null;
            $attempt['tokens'] = array_intersect_key((array)($r['json']['usageMetadata'] ?? []), array_flip(['promptTokenCount', 'candidatesTokenCount', 'thoughtsTokenCount', 'totalTokenCount']));
            if ($r['runaway']) {
                $attempt['runaway'] = true;
            }
            if ($list !== null) {
                $attempts[] = $attempt;
                if ($ms['persist']) {
                    usage_update($model, ['compat' => $ms['compat'], 'basic' => null]);
                }
                health_ok($model);
                $receipts = $list;
                $usedModel = $model;
                $abortAll($model, $a->started);
                break 2;
            }
            $attempt['error'] = $err;
            if (($r['text'] ?? '') !== '') {
                $attempt['sample'] = cut_text((string)$r['text'], -60);   // 崩れ方の確認用（答えの末尾だけ）
            }
            $lastParseError = $err;
            // 同じ種だと同じ崩れ方をしやすいので、種を変えて同じモデルでもう1回
            if ($ms['parse']++ < 1 && $elapsed() < $cfg['budget'] - 10) {
                $ms['seed'] += 100;
                $retryQueue[] = ['model' => $model, 'at' => $retryAt];
            }
        } else {
            $attempt['error'] = $r['timedOut'] ? 'timeout ' . $r['timeout'] . 's' : cut_text((string)($r['json']['error']['message'] ?? $r['error']), 0, 300);
            $q = null;
            if ($r['status'] === 429) {
                $q = classify_quota_error($r);
                $attempt['quota'] = $q['kind'];
            }
            $moreCandidates = $next < count($candidates) || $running || $retryQueue;

            if ($r['timedOut'] || in_array($r['status'], [0, 500, 502, 503, 504], true)) {
                // 応答なし・混雑：そのモデルを後回しにして、次のモデルへ。他に無ければ、考える量を下げて1回だけやり直す
                $anyTimeout = $anyTimeout || $r['timedOut'];
                if (!$isBatch) {
                    // まとめ読みは作業が重く、失敗してもモデルの不調とは限らないので、後回しにはしない
                    health_fail($model, $r['timedOut'] ? 'timeout' : 'overload');
                }
                if (count($candidates) === 1 && $ms['transient']++ < 1 && $elapsed() < $cfg['budget'] - 25) {   // 使えるモデルが1つだけのときだけ、同じモデルでやり直す
                    $retryQueue[] = ['model' => $model, 'at' => $now + 2];
                }
            } elseif ($r['status'] === 404) {
                usage_update($model, ['missing' => true]);
            } elseif ($r['status'] === 429) {
                if ($q['kind'] === 'day') {
                    // 1日の上限は待っても回復しないので、その日は使わない
                    usage_update($model, ['exhausted' => true, 'exhaustedAt' => jst_now(), 'exhaustedAfter' => (int)(usage_load()['models'][$model]['requests'] ?? 0),
                        'limit' => $q['limit'], 'freeTier' => $q['freeTier']]);
                } elseif ($q['kind'] === 'minute') {
                    $wait = max(2, (int)($q['retryAfter'] ?? 10));
                    if (!$moreCandidates && $ms['minute']++ < 2 && $wait <= 60 && $elapsed() < $cfg['budget'] - $wait - 15) {
                        $retryQueue[] = ['model' => $model, 'at' => $now + $wait];   // 他に使えるモデルが無いときだけ、待って同じモデルでやり直す
                    } else {
                        usage_update($model, ['slowUntil' => time() + 60, 'slowReason' => 'rate']);
                    }
                } else {
                    // 種類が分からない 429（混雑など）は、しばらく後回しにするだけで、その日の利用は止めない
                    health_fail($model, 'rate');
                }
            } elseif ($r['status'] === 400 && !is_key_error($r) && $ms['bad']++ < 2) {
                // 追加設定を受け付けない（または原因不明の一時的な 400）。考える量 → すべて の順に外してやり直す
                if ($ms['compat'] < COMPAT_BASIC) {
                    $msg = (string)($r['json']['error']['message'] ?? '');
                    $ms['compat'] = ($ms['compat'] === COMPAT_FULL && (stripos($msg, 'thinking') !== false || !is_compat_error($r))) ? COMPAT_NO_THINKING : COMPAT_BASIC;
                    $ms['persist'] = $ms['persist'] || is_compat_error($r);
                }
                $retryQueue[] = ['model' => $model, 'at' => $retryAt];
            } else {
                // キー不正・受け付けられない画像など、モデルを変えても直らない失敗
                $fatal = true;
            }
        }
        $attempts[] = $attempt;
        $failures[] = $r + ['model' => $model];
        unset($ms);
        // まとめ読みは、モデルが使えない（上限・見つからない）とき以外は、他のモデルを試さずに早めに諦める
        if ($isBatch && !$retryQueue && !in_array($r['status'], [404], true) && !($r['status'] === 429 && ($q['kind'] ?? '') === 'day')) {
            $next = count($candidates);
        }
        unset($q);
    }
}
$abortAll(null);
curl_multi_close($mh);

if ($receipts === null) {
    $lastError = $failures ? $failures[count($failures) - 1] : null;
    $code = 'error';
    if ($lastError === null) {
        $error = '本日は使えるモデルがすべて1日の上限に達しています（または見つかりません：' . implode('、', $skipped) . '）。'
            . reset_time_jst() . ' に回数がリセットされます。回数を増やすには有料枠にしてください。「🩺 診断」で詳しい状況を確認できます。';
        $code = 'quota_day';
    } else {
        // 失敗の内訳。すべて「応答なし・混雑」なら、Gemini 側の混雑として知らせる
        $overload = 0;
        $keyErr = null;
        foreach ($failures as $f) {
            if ($f['timedOut'] || in_array($f['status'], [0, 500, 502, 503, 504], true)) {
                $overload++;
            }
            if (is_key_error($f) || in_array($f['status'], [401, 403], true)) {
                $keyErr = $f;
            }
        }
        if ($keyErr !== null) {
            $error = gemini_error_message($keyErr);
            $code = 'auth';
        } elseif ($overload === count($failures)) {
            $tried = array_values(array_unique(array_map(fn($f) => $f['model'], $failures)));
            $error = 'Gemini（Google）側が混雑していて、' . implode('・', $tried) . ' から答えが返りませんでした。上限ではありません。数分おいてから「もう一度読み取る」を押してください（モデルの状態は「🩺 診断」で確認できます）。';
            $code = 'overload';
        } elseif ($lastError['status'] === 200) {
            $error = $lastParseError . '。「もう一度読み取る」を押してください。';
            $code = 'parse';
        } else {
            $error = gemini_error_message($lastError);
            $code = $lastError['status'] === 429 ? (classify_quota_error($lastError)['kind'] === 'day' ? 'quota' : 'rate')
                : ($lastError['timedOut'] ? 'timeout' : 'error');
        }
    }
    $elapsedMs = (int)round($elapsed() * 1000);
    log_request($cfg, ['pass' => $pass, 'files' => count($files), 'ok' => false, 'code' => $code, 'error' => $error,
        'elapsedMs' => $elapsedMs, 'attempts' => $attempts]);
    respond(502, ['ok' => false, 'code' => $code, 'error' => $error, 'batch' => $isBatch,
        'detail' => array_map('attempt_text', $attempts), 'elapsedMs' => $elapsedMs]);
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
    'ok' => true, 'pass' => $pass, 'model' => $usedModel, 'fallback' => $usedModel !== $models[0],
    'files' => array_map(fn($list) => ['receipts' => $list], $byFile),
    'unassigned' => $unassigned,
]);

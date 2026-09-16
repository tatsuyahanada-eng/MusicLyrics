<?php
/**
 * Task Scheduler — 端末間でデータを共有するための保存先
 *
 * schedule.html と同じフォルダに置いてください。
 * データは同じフォルダ内の schedule-data/ に、合言葉から作った名前のファイルとして保存されます。
 * schedule-data/ には外部から直接読めないよう .htaccess を自動で作成します。
 *
 * 使い方（ツール側の「端末間でデータを共有」に入力）
 *   URL    : このファイルのURL（例 https://example.com/schedule/sync.php）
 *   合言葉 : 12文字以上の好きな文字列。PCとスマホで同じものを入れます。
 *
 * PHP 7.0 以降で動作します。
 */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Robots-Tag: noindex, nofollow');

define('SC_MIN_KEY_LENGTH', 12);
define('SC_MAX_KEY_LENGTH', 200);
define('SC_MAX_BODY_BYTES', 4194304);   // 4MB

$scDataDir = __DIR__ . '/schedule-data';

/** JSONを返して終了する */
function sc_out($status, $body)
{
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_UNICODE);
    exit;
}

/** 保存先フォルダを用意し、外部から直接読めないようにする */
function sc_prepare_dir($dir)
{
    if (!is_dir($dir)) {
        if (!@mkdir($dir, 0700, true) && !is_dir($dir)) {
            sc_out(500, array('ok' => false, 'error' => 'データの保存先フォルダを作成できませんでした。サーバの書き込み権限をご確認ください。'));
        }
    }
    if (!is_writable($dir)) {
        sc_out(500, array('ok' => false, 'error' => 'データの保存先フォルダに書き込みできません。サーバの権限設定をご確認ください。'));
    }

    $htaccess = $dir . '/.htaccess';
    if (!file_exists($htaccess)) {
        $rules = "<IfModule mod_authz_core.c>\n"
               . "  Require all denied\n"
               . "</IfModule>\n"
               . "<IfModule !mod_authz_core.c>\n"
               . "  Order allow,deny\n"
               . "  Deny from all\n"
               . "</IfModule>\n";
        @file_put_contents($htaccess, $rules);
    }
    $index = $dir . '/index.html';
    if (!file_exists($index)) {
        @file_put_contents($index, '');
    }
}

/** 合言葉を検証して、保存ファイルのパスを返す */
function sc_file_for_key($dir, $key)
{
    if (!is_string($key)) {
        sc_out(400, array('ok' => false, 'error' => '合言葉が指定されていません。'));
    }
    $key = trim($key);
    $len = strlen($key);
    if ($len < SC_MIN_KEY_LENGTH) {
        sc_out(400, array('ok' => false, 'error' => '合言葉は12文字以上にしてください。'));
    }
    if ($len > SC_MAX_KEY_LENGTH) {
        sc_out(400, array('ok' => false, 'error' => '合言葉が長すぎます。'));
    }
    // 合言葉そのものは保存せず、ハッシュをファイル名に使う
    return $dir . '/' . hash('sha256', 'task-scheduler|' . $key) . '.json';
}

/** 保存済みの内容を読む */
function sc_read($file)
{
    if (!file_exists($file)) {
        return array('rev' => 0, 'updatedAt' => null, 'data' => null);
    }
    $raw = @file_get_contents($file);
    if ($raw === false || $raw === '') {
        return array('rev' => 0, 'updatedAt' => null, 'data' => null);
    }
    $parsed = json_decode($raw, true);
    if (!is_array($parsed) || !isset($parsed['rev'])) {
        return array('rev' => 0, 'updatedAt' => null, 'data' => null);
    }
    return array(
        'rev'       => (int) $parsed['rev'],
        'updatedAt' => isset($parsed['updatedAt']) ? $parsed['updatedAt'] : null,
        'data'      => isset($parsed['data']) ? $parsed['data'] : null,
    );
}

/** 書き込み途中で壊れないよう、別ファイルに書いてから置き換える */
function sc_write($file, $record)
{
    $tmp = $file . '.tmp' . getmypid();
    $json = json_encode($record, JSON_UNESCAPED_UNICODE);
    if ($json === false) {
        sc_out(500, array('ok' => false, 'error' => 'データを変換できませんでした。'));
    }
    if (@file_put_contents($tmp, $json, LOCK_EX) === false) {
        sc_out(500, array('ok' => false, 'error' => 'データを保存できませんでした。'));
    }
    if (!@rename($tmp, $file)) {
        @unlink($tmp);
        sc_out(500, array('ok' => false, 'error' => 'データを保存できませんでした。'));
    }
    @chmod($file, 0600);
}

/* ------------------------------------------------------------------ */

sc_prepare_dir($scDataDir);

$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';

/* ---- 取り込み（GET）／接続テスト ---- */
if ($method === 'GET') {
    $key  = isset($_GET['key']) ? $_GET['key'] : '';
    $file = sc_file_for_key($scDataDir, $key);
    $rec  = sc_read($file);

    $jobs = 0;
    if (is_array($rec['data']) && isset($rec['data']['jobs']) && is_array($rec['data']['jobs'])) {
        $jobs = count($rec['data']['jobs']);
    }

    // 接続テストのときは中身を返さない
    if (isset($_GET['action']) && $_GET['action'] === 'ping') {
        sc_out(200, array(
            'ok' => true, 'rev' => $rec['rev'], 'updatedAt' => $rec['updatedAt'], 'jobs' => $jobs,
        ));
    }

    sc_out(200, array(
        'ok' => true, 'rev' => $rec['rev'], 'updatedAt' => $rec['updatedAt'],
        'jobs' => $jobs, 'data' => $rec['data'],
    ));
}

/* ---- 保存（POST） ---- */
if ($method === 'POST') {
    $raw = file_get_contents('php://input');
    if ($raw === false) {
        sc_out(400, array('ok' => false, 'error' => '送信内容を読み取れませんでした。'));
    }
    if (strlen($raw) > SC_MAX_BODY_BYTES) {
        sc_out(413, array('ok' => false, 'error' => 'データが大きすぎます。'));
    }

    $body = json_decode($raw, true);
    if (!is_array($body)) {
        sc_out(400, array('ok' => false, 'error' => '送信内容の形式が正しくありません。'));
    }

    $key  = isset($body['key']) ? $body['key'] : '';
    $file = sc_file_for_key($scDataDir, $key);

    if (!isset($body['data']) || !is_array($body['data'])) {
        sc_out(400, array('ok' => false, 'error' => '保存するデータがありません。'));
    }

    $baseRev = isset($body['baseRev']) ? (int) $body['baseRev'] : 0;
    $force   = !empty($body['force']);

    // 読み取りから書き込みまでを1つのロックで囲み、同時保存で片方が消えないようにする
    $lock = @fopen($file . '.lock', 'c');
    if ($lock !== false) {
        flock($lock, LOCK_EX);
    }

    $rec = sc_read($file);

    if (!$force && $rec['rev'] !== $baseRev) {
        $jobs = 0;
        if (is_array($rec['data']) && isset($rec['data']['jobs']) && is_array($rec['data']['jobs'])) {
            $jobs = count($rec['data']['jobs']);
        }
        if ($lock !== false) { flock($lock, LOCK_UN); fclose($lock); }
        sc_out(409, array(
            'ok' => false, 'error' => 'conflict',
            'rev' => $rec['rev'], 'updatedAt' => $rec['updatedAt'],
            'jobs' => $jobs, 'data' => $rec['data'],
        ));
    }

    $next = array(
        'rev'       => $rec['rev'] + 1,
        'updatedAt' => gmdate('c'),
        'data'      => $body['data'],
    );
    sc_write($file, $next);

    if ($lock !== false) { flock($lock, LOCK_UN); fclose($lock); }

    sc_out(200, array('ok' => true, 'rev' => $next['rev'], 'updatedAt' => $next['updatedAt']));
}

/* ---- 削除（DELETE） ---- */
if ($method === 'DELETE') {
    parse_str(isset($_SERVER['QUERY_STRING']) ? $_SERVER['QUERY_STRING'] : '', $q);
    $key  = isset($q['key']) ? $q['key'] : '';
    $file = sc_file_for_key($scDataDir, $key);
    if (file_exists($file)) {
        @unlink($file);
    }
    sc_out(200, array('ok' => true, 'rev' => 0, 'updatedAt' => null));
}

sc_out(405, array('ok' => false, 'error' => '対応していない通信方法です。'));

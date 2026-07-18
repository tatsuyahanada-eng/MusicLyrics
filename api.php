<?php
/* ============================================================
   Case By Case — api.php
   CSV(サーバー保存 / 追記) と FTP(一括更新) のバックエンド。
   レンタルサーバ(PHP)にそのままアップロードして使用できます。
   設定は config.php（config.sample.php をコピーして作成）で行います。
   ============================================================ */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

// ---- 設定読み込み（無ければ既定値） ----
$cfgFile = __DIR__ . '/config.php';
if (is_file($cfgFile)) require_once $cfgFile;

if (!defined('CSV_PATH'))        define('CSV_PATH', __DIR__ . '/data/manual.csv');
if (!defined('API_TOKEN'))       define('API_TOKEN', '');
if (!defined('FTP_HOST'))        define('FTP_HOST', '');
if (!defined('FTP_PORT'))        define('FTP_PORT', 21);
if (!defined('FTP_USER'))        define('FTP_USER', '');
if (!defined('FTP_PASSWORD'))    define('FTP_PASSWORD', '');
if (!defined('FTP_SECURE'))      define('FTP_SECURE', false);
if (!defined('FTP_REMOTE_PATH')) define('FTP_REMOTE_PATH', 'manual.csv');

$CSV_HEADER = array('id', 'parent_id', 'sort_order', 'title', 'body');
$FTP_CONFIGURED = (FTP_HOST !== '' && FTP_USER !== '');

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
  $data = json_decode($raw, true);
  return is_array($data) ? $data : array();
}
function csv_cell($v) {
  $v = (string)$v;
  if (preg_match('/[",\r\n]/', $v)) return '"' . str_replace('"', '""', $v) . '"';
  return $v;
}
function csv_line($fields) {
  $out = array();
  foreach ($fields as $v) $out[] = csv_cell($v);
  return implode(',', $out) . "\r\n";
}
function ensure_dir($path) {
  $dir = dirname($path);
  if (!is_dir($dir)) @mkdir($dir, 0775, true);
}
function ftp_connection() {
  $conn = FTP_SECURE
    ? @ftp_ssl_connect(FTP_HOST, (int)FTP_PORT, 15)
    : @ftp_connect(FTP_HOST, (int)FTP_PORT, 15);
  if (!$conn) fail('FTPサーバーへ接続できませんでした', 502);
  if (!@ftp_login($conn, FTP_USER, FTP_PASSWORD)) { @ftp_close($conn); fail('FTPログインに失敗しました', 502); }
  @ftp_pasv($conn, true);
  return $conn;
}

/* ---------- token auth ---------- */
if (API_TOKEN !== '') {
  $sent = isset($_SERVER['HTTP_X_API_TOKEN']) ? $_SERVER['HTTP_X_API_TOKEN'] : '';
  if (!hash_equals(API_TOKEN, $sent)) fail('認証に失敗しました（APIトークン不一致）', 401);
}

$action = isset($_GET['action']) ? $_GET['action'] : '';

/* ---------- routes ---------- */
switch ($action) {

  case 'config':
    ok(array(
      'ftpConfigured' => $FTP_CONFIGURED,
      'csvPath'       => basename(CSV_PATH),
      'hasToken'      => (API_TOKEN !== ''),
    ));
    break;

  case 'load':
    $csv = is_file(CSV_PATH) ? file_get_contents(CSV_PATH) : '';
    if ($csv === false) fail('CSVの読み込みに失敗しました', 500);
    ok(array('csv' => $csv));
    break;

  case 'save':
    $d = body_json();
    if (!isset($d['csv'])) fail('csv がありません');
    ensure_dir(CSV_PATH);
    if (@file_put_contents(CSV_PATH, $d['csv']) === false) fail('CSVの書き込みに失敗しました（権限をご確認ください）', 500);
    ok();
    break;

  case 'append':
    $d = body_json();
    if (empty($d['rows']) || !is_array($d['rows'])) fail('rows がありません');
    ensure_dir(CSV_PATH);
    $new = !is_file(CSV_PATH) || filesize(CSV_PATH) === 0;
    $fp = @fopen(CSV_PATH, 'a');
    if (!$fp) fail('CSVを開けませんでした（権限をご確認ください）', 500);
    if ($new) fwrite($fp, csv_line($CSV_HEADER));
    foreach ($d['rows'] as $r) {
      fwrite($fp, csv_line(array(
        isset($r['id']) ? $r['id'] : '',
        isset($r['parent_id']) ? $r['parent_id'] : '',
        isset($r['sort_order']) ? $r['sort_order'] : '',
        isset($r['title']) ? $r['title'] : '',
        isset($r['body']) ? $r['body'] : '',
      )));
    }
    fclose($fp);
    ok(array('appended' => count($d['rows'])));
    break;

  case 'ftp_pull':
    if (!$FTP_CONFIGURED) fail('FTPが設定されていません（config.php）');
    $conn = ftp_connection();
    ensure_dir(CSV_PATH);
    $tmp = CSV_PATH . '.tmp';
    if (!@ftp_get($conn, $tmp, FTP_REMOTE_PATH, FTP_BINARY)) { @ftp_close($conn); fail('FTPからの取得に失敗しました', 502); }
    @ftp_close($conn);
    @rename($tmp, CSV_PATH);
    $csv = file_get_contents(CSV_PATH);
    ok(array('csv' => $csv));
    break;

  case 'ftp_push':
    if (!$FTP_CONFIGURED) fail('FTPが設定されていません（config.php）');
    $d = body_json();
    if (isset($d['csv'])) {
      ensure_dir(CSV_PATH);
      if (@file_put_contents(CSV_PATH, $d['csv']) === false) fail('CSVの書き込みに失敗しました', 500);
    }
    if (!is_file(CSV_PATH)) fail('送信するCSVがありません');
    $conn = ftp_connection();
    if (!@ftp_put($conn, FTP_REMOTE_PATH, CSV_PATH, FTP_BINARY)) { @ftp_close($conn); fail('FTPへの送信に失敗しました', 502); }
    @ftp_close($conn);
    ok();
    break;

  default:
    fail('不明なアクションです: ' . $action, 404);
}

<?php
/**
 * OES入替作業APP / 業態ごとの手順書・資料（画像・PDF）のアップロード用エンドポイント（任意・PHPが動くサーバー向け）
 *
 * 置き場所: index.html と同じ場所に "manual-upload.php" という名前で置く。
 *           （このファイルは deploy/ にあるサンプルです。名前を manual-upload.php にしてコピーしてください）
 *
 * 動き:     設定タブの「＋ ファイルを追加」から画像・PDFを受け取り、
 *           このファイルと同じ場所の "manuals/" フォルダに保存する。
 *           保存したファイルへのURL（settings.json に書き込む参照）を返す。
 *
 * ※ パスワードは settings-save.php と同じ config.php から読み込む（このファイルには書かない）。
 * ※ アップロードを使わない場合はこのファイルを置かなくてよい（設定タブに案内が出るだけで他機能に影響しない）。
 */

declare(strict_types=1);

const MANUAL_DIR  = __DIR__ . '/manuals';
const MAX_BYTES    = 15 * 1024 * 1024; // 15MB（index.html 側の上限と合わせる）

// クライアントが名乗るMIMEやファイル名は信用せず、実体を見て判定する
const ALLOWED_MIME = [
    'image/jpeg' => ['ext' => 'jpg',  'type' => 'image'],
    'image/png'  => ['ext' => 'png',  'type' => 'image'],
    'image/gif'  => ['ext' => 'gif',  'type' => 'image'],
    'image/webp' => ['ext' => 'webp', 'type' => 'image'],
    'application/pdf' => ['ext' => 'pdf', 'type' => 'pdf'],
];

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function fail(string $msg, int $code = 400): void {
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

// "10M" のようなini値をバイト数に変換する
function iniToBytes(string $val): int {
    $val = trim($val);
    if ($val === '') return 0;
    $unit = strtolower(substr($val, -1));
    $num  = (int)$val;
    switch ($unit) {
        case 'g': return $num * 1024 * 1024 * 1024;
        case 'm': return $num * 1024 * 1024;
        case 'k': return $num * 1024;
        default:  return (int)$val;
    }
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    fail('POSTで送信してください', 405);
}

// サーバーのpost_max_sizeを超えるリクエストを送ると、PHPは$_POSTと$_FILESを丸ごと空にする
// （エラーにもならない）。これを先に見分けておかないと「パスワードが違います」という
// 誤解を招くメッセージになってしまうため、最初にチェックする。
$postMaxBytes = iniToBytes((string)ini_get('post_max_size'));
$contentLength = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
if (empty($_POST) && empty($_FILES) && $contentLength > 0
    && $postMaxBytes > 0 && $contentLength > $postMaxBytes) {
    fail('ファイルが大きすぎてサーバーの上限（post_max_size = ' . ini_get('post_max_size') . '）を超えています。'
        . 'ファイルを小さくするか、サーバー側の post_max_size / upload_max_filesize を大きくしてください。', 413);
}

$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    fail('config.php がありません（index.html と同じ場所に置いてください）', 500);
}
$config = require $configFile;
$adminPassword = (string)($config['admin_password'] ?? '');
if ($adminPassword === '') {
    fail('config.php に admin_password がありません', 500);
}

$password = (string)($_POST['password'] ?? '');
if ($password === '' || !hash_equals($adminPassword, $password)) {
    fail('パスワードが違います', 403);
}

if (!isset($_FILES['file']) || !is_array($_FILES['file'])) {
    fail('ファイルが送られていません');
}
$file = $_FILES['file'];
$uploadErr = (int)($file['error'] ?? UPLOAD_ERR_NO_FILE);
if ($uploadErr !== UPLOAD_ERR_OK) {
    if ($uploadErr === UPLOAD_ERR_INI_SIZE) {
        fail('ファイルが大きすぎてサーバーの上限（upload_max_filesize = ' . ini_get('upload_max_filesize') . '）を超えています。');
    }
    fail('ファイルのアップロードに失敗しました（エラーコード ' . $uploadErr . '）');
}
if ((int)($file['size'] ?? 0) <= 0 || (int)$file['size'] > MAX_BYTES) {
    fail('ファイルが大きすぎます（15MBまで）');
}
$tmpPath = (string)($file['tmp_name'] ?? '');
if ($tmpPath === '' || !is_uploaded_file($tmpPath)) {
    fail('アップロードされたファイルを確認できませんでした');
}

$finfo = new finfo(FILEINFO_MIME_TYPE);
$mime  = (string)$finfo->file($tmpPath);
if (!isset(ALLOWED_MIME[$mime])) {
    fail('画像（jpg/png/gif/webp）かPDFのファイルを選んでください');
}
$meta = ALLOWED_MIME[$mime];

if (!is_dir(MANUAL_DIR)) {
    if (!mkdir(MANUAL_DIR, 0775, true) && !is_dir(MANUAL_DIR)) {
        fail('manuals フォルダを作成できませんでした（サーバーの書き込み権限をご確認ください）', 500);
    }
}
// アップロードしたファイルの中から実行可能なスクリプトが動いてしまわないようにする（多層防御）
$htaccess = MANUAL_DIR . '/.htaccess';
if (!is_file($htaccess)) {
    @file_put_contents($htaccess, "php_flag engine off\nOptions -ExecCGI -Indexes\n<FilesMatch \"\\.(php|phtml|php3|php4|php5|pht)$\">\n  Require all denied\n</FilesMatch>\n");
}

// 保存名は元のファイル名を使わず、ランダムな名前にする（パス操作・上書き事故を防ぐ）
$fname = bin2hex(random_bytes(10)) . '.' . $meta['ext'];
$dest  = MANUAL_DIR . '/' . $fname;
if (!move_uploaded_file($tmpPath, $dest)) {
    fail('ファイルを保存できませんでした（サーバーの書き込み権限をご確認ください）', 500);
}

// 表示名は元のファイル名から（制御文字などを除いて）作る。実体のパスには使わない
$origName = (string)($file['name'] ?? $fname);
$origName = preg_replace('/[\x00-\x1F\x7F]/u', '', $origName) ?? $fname;
if ($origName === '') { $origName = $fname; }
if (mb_strlen($origName) > 120) { $origName = mb_substr($origName, 0, 120); }

echo json_encode([
    'ok'   => true,
    'url'  => 'manuals/' . $fname,
    'name' => $origName,
    'type' => $meta['type'],
    'size' => (int)$file['size'],
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

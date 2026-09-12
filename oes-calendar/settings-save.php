<?php
/**
 * OES入替作業APP / 共有設定の保存エンドポイント（任意・PHPが動くサーバー向け）
 *
 * 置き場所: index.html と同じ場所（このファイル自身がある場所）に置いたままでよい。
 *           手動でコピー・移動する必要はない（配布ZIPを展開すればそのまま使える）。
 *
 * 動き:     アプリの「共有設定を保存」ボタンから JSON を受け取り、
 *           同じ場所の settings.json に書き込みます。これで全員が同じ設定を見られます。
 *
 * PHPが使えないサーバーの場合はこのファイルを置かなくて構いません。
 * その場合はアプリが settings.json をダウンロードするので、FTPで手動アップロードしてください。
 *
 * ※ パスワードは同じ場所に置く config.php から読み込みます（このファイルには書きません）。
 */

declare(strict_types=1);

const TARGET_FILE = __DIR__ . '/settings.json';
const MAX_BYTES   = 1048576; // 1MB

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function fail(string $msg, int $code = 400): void {
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    fail('POSTで送信してください', 405);
}

// 管理者パスワードは config.php の1か所だけに置く
$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    fail('config.php がありません（index.html と同じ場所に置いてください）', 500);
}
$config = require $configFile;
$adminPassword = (string)($config['admin_password'] ?? '');
if ($adminPassword === '') {
    fail('config.php に admin_password がありません', 500);
}

$raw = file_get_contents('php://input');
if ($raw === false || $raw === '') {
    fail('データが空です');
}
if (strlen($raw) > MAX_BYTES) {
    fail('データが大きすぎます');
}

$body = json_decode($raw, true);
if (!is_array($body)) {
    fail('JSONを読み取れませんでした');
}
if (!isset($body['password']) || !hash_equals($adminPassword, (string)$body['password'])) {
    fail('パスワードが違います', 403);
}

$settings = $body['settings'] ?? null;
if (!is_array($settings) || empty($settings['gyotai'])) {
    fail('設定の形式が違います');
}

$json = json_encode($settings, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
if ($json === false) {
    fail('設定をJSONに変換できませんでした', 500);
}

// 一時ファイルに書いてから差し替える（書き込み途中の内容が読まれないように）
$tmp = TARGET_FILE . '.tmp';
if (file_put_contents($tmp, $json, LOCK_EX) === false || !rename($tmp, TARGET_FILE)) {
    @unlink($tmp);
    fail('settings.json に書き込めませんでした（フォルダの書き込み権限をご確認ください）', 500);
}

echo json_encode(['ok' => true], JSON_UNESCAPED_UNICODE);

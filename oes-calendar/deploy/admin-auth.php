<?php
/**
 * OES入替作業APP / 管理者パスワードの照合エンドポイント（任意・PHPが動くサーバー向け）
 *
 * 置き場所: index.html と同じ場所に "admin-auth.php" という名前で置く。
 *           同じ場所に config.php も必要です。
 *
 * 動き:     アプリの設定タブで入力されたパスワードを受け取り、config.php の値と
 *           照合して {"ok":true} / {"ok":false} を返すだけです。
 *           パスワードそのものはブラウザへ送らないので、利用者からは分かりません。
 *
 * このファイルを置かない場合、アプリは config.json のハッシュで照合します。
 */

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function respond(bool $ok, int $code = 200): void {
    http_response_code($code);
    echo json_encode(['ok' => $ok]);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    respond(false, 405);
}

$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    // 設定ファイルが無い場合は「このエンドポイントは使えない」ことを伝える（アプリはハッシュ照合に切り替わる）
    http_response_code(500);
    echo json_encode(['error' => 'config.php がありません']);
    exit;
}
$config = require $configFile;
$expected = (string)($config['admin_password'] ?? '');
if ($expected === '') {
    http_response_code(500);
    echo json_encode(['error' => 'config.php に admin_password がありません']);
    exit;
}

$raw = file_get_contents('php://input');
$body = is_string($raw) ? json_decode($raw, true) : null;
$given = is_array($body) ? (string)($body['password'] ?? '') : '';

// 総当たりを少しでも遅くするための待ち時間
usleep(300000);

respond(hash_equals($expected, $given));

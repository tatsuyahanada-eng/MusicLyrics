<?php
/**
 * 全エントリポイントの共通初期化。
 *   require __DIR__ . '/../lib/bootstrap.php';
 */
declare(strict_types=1);

if (defined('WELSYS_SSO_BOOTSTRAPPED')) {
    return;
}
define('WELSYS_SSO_BOOTSTRAPPED', true);
define('SSO_ROOT', dirname(__DIR__));

mb_internal_encoding('UTF-8');

// ── 設定読み込み ──────────────────────────────────────────────────
$configFile = SSO_ROOT . '/config.php';
if (!is_file($configFile)) {
    http_response_code(500);
    header('Content-Type: text/plain; charset=UTF-8');
    exit("設定ファイルがありません。config.sample.php を config.php にコピーして編集してください。\n");
}
/** @var array $SSO_CONFIG */
$SSO_CONFIG = require $configFile;

date_default_timezone_set($SSO_CONFIG['timezone'] ?? 'Asia/Tokyo');

// ── クラスの読み込み（このディレクトリの <Class>.php を素直に読む） ──
spl_autoload_register(static function (string $class): void {
    if (preg_match('/\A[A-Za-z_][A-Za-z0-9_]*\z/', $class) !== 1) {
        return;
    }
    $file = __DIR__ . '/' . $class . '.php';
    if (is_file($file)) {
        require_once $file;
    }
});

require_once __DIR__ . '/helpers.php';

Config::init($SSO_CONFIG);
Db::init($SSO_CONFIG['db']);

// ── PHP セッション（CSRF トークンやフラッシュメッセージの置き場） ──
// SSO のログイン状態そのものは sso_sessions テーブル + 専用Cookie で持つ。
if (PHP_SAPI !== 'cli' && session_status() === PHP_SESSION_NONE) {
    session_set_cookie_params([
        'lifetime' => 0,
        'path'     => '/',
        'secure'   => (bool) Config::get('cookie.secure', true),
        'httponly' => true,
        'samesite' => (string) Config::get('cookie.samesite', 'Lax'),
    ]);
    session_name('WELSYS_SSO_UI');
    session_start();
}

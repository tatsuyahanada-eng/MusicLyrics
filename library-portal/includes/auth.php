<?php
/** 認証・権限・CSRF */
declare(strict_types=1);

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

/** セッション開始（クッキー設定込み） */
function lp_session_start(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    $c = lp_config();
    date_default_timezone_set($c['timezone'] ?? 'Asia/Tokyo');

    session_name('LPSESSID');
    session_set_cookie_params([
        'lifetime' => 0,
        'path'     => '/',
        'secure'   => (bool)($c['secure_cookie'] ?? true),
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_start();

    // 無操作タイムアウト
    $limit = (int)($c['session_minutes'] ?? 480) * 60;
    if (isset($_SESSION['last_seen']) && (time() - (int)$_SESSION['last_seen']) > $limit) {
        lp_logout();
    }
    $_SESSION['last_seen'] = time();
}

/** ログイン中の利用者（未ログインなら null） */
function current_user(): ?array
{
    return $_SESSION['user'] ?? null;
}

function is_logged_in(): bool
{
    return current_user() !== null;
}

function is_admin(): bool
{
    $u = current_user();
    return $u !== null && ($u['role'] ?? '') === 'admin';
}

/** 未ログインならログイン画面へ */
function require_login(): array
{
    lp_session_start();
    $u = current_user();
    if ($u === null) {
        $to = $_SERVER['REQUEST_URI'] ?? '';
        header('Location: login.php' . ($to !== '' ? '?to=' . urlencode($to) : ''));
        exit;
    }
    return $u;
}

/** 管理者でなければ 403 */
function require_admin(): array
{
    $u = require_login();
    if (($u['role'] ?? '') !== 'admin') {
        http_response_code(403);
        exit('この画面を表示する権限がありません。');
    }
    return $u;
}

/** API 用：未ログイン / 権限不足は JSON で返す */
function api_require_login(): array
{
    lp_session_start();
    $u = current_user();
    if ($u === null) {
        json_error('ログインが必要です。', 401);
    }
    return $u;
}

function api_require_admin(): array
{
    $u = api_require_login();
    if (($u['role'] ?? '') !== 'admin') {
        json_error('この操作には管理者権限が必要です。', 403);
    }
    return $u;
}

/** ログイン処理。成功なら true、失敗なら理由メッセージを返す */
function lp_login(string $loginId, string $password)
{
    $c = lp_config();
    $st = db()->prepare('SELECT * FROM lp_users WHERE login_id = ? LIMIT 1');
    $st->execute([$loginId]);
    $user = $st->fetch();

    // 利用者が存在しない場合も同じ処理時間になるようダミー検証を行う
    if (!$user) {
        password_verify($password, '$2y$10$usesomesillystringfore7hnbRJHxXVLeakoG8K30M1TDx1v.3fu');
        return 'ログインIDまたはパスワードが正しくありません。';
    }
    if ((int)$user['is_active'] !== 1) {
        return 'このアカウントは停止されています。管理者にお問い合わせください。';
    }
    if ($user['locked_until'] !== null && strtotime((string)$user['locked_until']) > time()) {
        return 'ログイン失敗が続いたため一時的にロックされています。しばらくしてからお試しください。';
    }
    if (!password_verify($password, (string)$user['password_hash'])) {
        $failed = (int)$user['failed_count'] + 1;
        $lockUntil = null;
        if ($failed >= (int)($c['max_failed'] ?? 5)) {
            $lockUntil = date('Y-m-d H:i:s', time() + (int)($c['lock_minutes'] ?? 10) * 60);
            $failed = 0;
        }
        $up = db()->prepare('UPDATE lp_users SET failed_count = ?, locked_until = ? WHERE user_id = ?');
        $up->execute([$failed, $lockUntil, $user['user_id']]);
        return 'ログインIDまたはパスワードが正しくありません。';
    }

    // 成功
    session_regenerate_id(true);
    $_SESSION['user'] = [
        'user_id'      => (int)$user['user_id'],
        'login_id'     => $user['login_id'],
        'display_name' => $user['display_name'],
        'role'         => $user['role'],
        'must_change_pw' => (int)$user['must_change_pw'] === 1,
    ];
    $_SESSION['last_seen'] = time();

    $up = db()->prepare('UPDATE lp_users SET failed_count = 0, locked_until = NULL, last_login_at = NOW() WHERE user_id = ?');
    $up->execute([$user['user_id']]);

    audit('login', $user['login_id']);
    return true;
}

function lp_logout(): void
{
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000, $p['path'], $p['domain'], $p['secure'], $p['httponly']);
    }
    session_destroy();
}

/** セッション上の権限情報を DB の最新値へ更新（自分の権限が変更された場合に反映） */
function refresh_current_user(): void
{
    $u = current_user();
    if ($u === null) {
        return;
    }
    $st = db()->prepare('SELECT role, display_name, is_active FROM lp_users WHERE user_id = ? LIMIT 1');
    $st->execute([$u['user_id']]);
    $row = $st->fetch();
    if (!$row || (int)$row['is_active'] !== 1) {
        lp_logout();
        return;
    }
    $_SESSION['user']['role'] = $row['role'];
    $_SESSION['user']['display_name'] = $row['display_name'];
}

/** CSRF トークン */
function csrf_token(): string
{
    lp_session_start();
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf'];
}

function verify_csrf(?string $token): bool
{
    return is_string($token) && !empty($_SESSION['csrf']) && hash_equals($_SESSION['csrf'], $token);
}

/** API 用：CSRF トークン不一致なら 419 */
function api_verify_csrf(): void
{
    $token = $_SERVER['HTTP_X_CSRF_TOKEN'] ?? null;
    if (!verify_csrf($token)) {
        json_error('セッションの有効期限が切れています。画面を再読み込みしてください。', 419);
    }
}

/** パスワードの強度チェック。問題なければ null、あればメッセージ */
function password_problem(string $pw): ?string
{
    if (mb_strlen($pw) < 8) {
        return 'パスワードは8文字以上で設定してください。';
    }
    if (!preg_match('/[A-Za-z]/', $pw) || !preg_match('/[0-9]/', $pw)) {
        return 'パスワードは英字と数字を両方含めてください。';
    }
    return null;
}

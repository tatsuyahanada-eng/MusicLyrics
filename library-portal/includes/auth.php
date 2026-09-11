<?php
/**
 * 認証・権限・CSRF
 *
 * 本人確認（誰か）は sso/sso_guard.php が行い、結果を $SSO_USER に入れる。
 * このアプリには「管理者／閲覧のみ」の区別を設けていないため、
 * SSOでログインできた人は全員、登録・修正・削除ができる利用者として扱う。
 *
 * このファイルを読み込む前に、ページの一番先頭で
 *   require __DIR__ . '/sso/sso_guard.php';
 * を読み込んでおくこと（$SSO_USER が入っていない場合は未ログイン扱いになる）。
 */
declare(strict_types=1);

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

/**
 * セッションが開始済みであることの確認（保険）。
 *
 * 通常は sso/sso_guard.php が先にセッションを開始しているので、ここで
 * 新たに session_start() が呼ばれることはない。呼ばれる場合でも、
 * SSO側のセッション名を変えてしまわないよう session_name() は指定しない。
 */
function lp_session_start(): void
{
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
}

/** ログイン中の利用者（未ログインなら null） */
function current_user(): ?array
{
    global $SSO_USER;
    if (empty($SSO_USER) || empty($SSO_USER['username'])) {
        return null;
    }
    return [
        // このアプリ独自の利用者IDは持たない（共通ログインのため）。
        // author_user_id など DB 上の該当列は NULL 可にしてある
        'user_id'      => null,
        'login_id'     => (string)$SSO_USER['username'],
        'display_name' => (string)($SSO_USER['display_name'] ?? $SSO_USER['username']),
        'role'         => 'admin',
    ];
}

function is_logged_in(): bool
{
    return current_user() !== null;
}

/** このアプリの利用者は全員管理者（権限の区別が無いため） */
function is_admin(): bool
{
    return is_logged_in();
}

/** 未ログインならログイン画面（SSO）へ */
function require_login(): array
{
    $u = current_user();
    if ($u === null) {
        // sso/sso_guard.php をまだ読み込んでいないページから呼ばれた場合の保険。
        // login.php が改めて SSO へ送る
        $to = $_SERVER['REQUEST_URI'] ?? '';
        header('Location: login.php' . ($to !== '' ? '?to=' . urlencode($to) : ''));
        exit;
    }
    return $u;
}

/** このアプリに管理者／閲覧のみの区別は無いため、ログインしていれば常に許可 */
function require_admin(): array
{
    return require_login();
}

/**
 * API で例外が起きたとき、白紙の 500 ではなく理由の分かる JSON を返すようにする。
 *
 * 詳しい内容はサーバーのエラーログにだけ書き、画面には「何をすれば直るか」だけを出す。
 */
function api_install_error_handler(): void
{
    static $done = false;
    if ($done) { return; }
    $done = true;

    set_exception_handler(static function (Throwable $e): void {
        error_log('[library-portal] API error: ' . $e->getMessage());

        $code = $e instanceof PDOException ? (string)$e->getCode() : '';
        $message = match ($code) {
            // 列が足りない：ファイルだけ新しくして sql/upgrade.sql を流していないとき
            '42S22' => 'データベースの更新がまだ行われていません。'
                     . 'phpMyAdmin で sql/upgrade.sql を実行してください。',
            // テーブルごと無い：まだ sql/schema.sql を流していないとき
            '42S02' => 'データベースのテーブルがありません。'
                     . 'phpMyAdmin で sql/schema.sql を実行してください。',
            default => 'サーバー側でエラーが発生しました。'
                     . 'サーバーのエラーログをご確認ください。',
        };

        if (!headers_sent()) {
            json_error($message, 500);
        }
    });
}

/** API 用：未ログイン / 権限不足は JSON で返す */
function api_require_login(): array
{
    api_install_error_handler();
    $u = current_user();
    if ($u === null) {
        json_error('ログインが必要です。', 401);
    }
    return $u;
}

function api_require_admin(): array
{
    return api_require_login();
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

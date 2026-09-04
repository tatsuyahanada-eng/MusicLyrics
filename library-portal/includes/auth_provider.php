<?php
/**
 * 利用者情報の供給元（ローカル / 共通ユーザーDB）を切り替える層
 *
 *   auth_mode = 'local'   … このアプリの lp_users を使う（既定）
 *   auth_mode = 'central' … 共通ユーザーDB（auth_users / auth_app_roles）を使う
 *
 * 重要な考え方：
 *   ・認証（誰か）は共通化してよい
 *   ・認可（このアプリで何ができるか）はアプリごとに持つ
 *     → 共通DBでも app_key 単位の行として権限を持つため、
 *       「ライブラリでは管理者、別アプリでは閲覧のみ」が成立します。
 */
declare(strict_types=1);

require_once __DIR__ . '/db.php';

function lp_auth_mode(): string
{
    $mode = lp_config()['auth_mode'] ?? 'local';
    return $mode === 'central' ? 'central' : 'local';
}

function lp_app_key(): string
{
    return (string)(lp_config()['app_key'] ?? 'library');
}

/**
 * 権限が付与されていない利用者の扱い
 *   'viewer' … 閲覧のみとしてログインを許可（社内で広く共有する場合）
 *   null     … ログインを拒否（アプリごとに利用者を限定する場合）
 */
function lp_default_role(): ?string
{
    $v = lp_config()['default_role'] ?? 'viewer';
    return ($v === null || $v === '' || $v === 'none') ? null : (string)$v;
}

/** 共通ユーザーDBへの接続（local モードではアプリDBを返す） */
function auth_db(): PDO
{
    static $pdo = null;
    if (lp_auth_mode() === 'local') {
        return db();
    }
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $c = lp_config();
    $dsn = $c['auth_db_dsn'] ?? sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
        $c['auth_db_host'] ?? $c['db_host'],
        (int)($c['auth_db_port'] ?? $c['db_port'] ?? 3306),
        $c['auth_db_name'] ?? ''
    );
    try {
        $pdo = new PDO($dsn, $c['auth_db_user'] ?? $c['db_user'], $c['auth_db_pass'] ?? $c['db_pass'], [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,
        ]);
    } catch (PDOException $e) {
        error_log('[library-portal] auth DB connect failed: ' . $e->getMessage());
        http_response_code(500);
        exit('共通ユーザーデータベースに接続できません。includes/config.php をご確認ください。');
    }
    return $pdo;
}

/** 利用者テーブル名 */
function user_table(): string
{
    return lp_auth_mode() === 'central' ? 'auth_users' : 'lp_users';
}

/** ログインIDで利用者を取得（見つからなければ null） */
function provider_find_user(string $loginId): ?array
{
    $t = user_table();
    $st = auth_db()->prepare("SELECT * FROM {$t} WHERE login_id = ? LIMIT 1");
    $st->execute([$loginId]);
    $row = $st->fetch();
    return $row ?: null;
}

/** 利用者IDで取得 */
function provider_get_user(int $userId): ?array
{
    $t = user_table();
    $st = auth_db()->prepare("SELECT * FROM {$t} WHERE user_id = ? LIMIT 1");
    $st->execute([$userId]);
    $row = $st->fetch();
    return $row ?: null;
}

/** ログイン失敗の記録 */
function provider_record_failure(int $userId, int $failedCount, ?string $lockUntil): void
{
    $t = user_table();
    $st = auth_db()->prepare("UPDATE {$t} SET failed_count = ?, locked_until = ? WHERE user_id = ?");
    $st->execute([$failedCount, $lockUntil, $userId]);
}

/** ログイン成功の記録 */
function provider_record_success(int $userId): void
{
    $t = user_table();
    $st = auth_db()->prepare(
        "UPDATE {$t} SET failed_count = 0, locked_until = NULL, last_login_at = NOW() WHERE user_id = ?"
    );
    $st->execute([$userId]);
}

/** パスワードの更新 */
function provider_set_password(int $userId, string $hash, bool $mustChange): void
{
    $t = user_table();
    $st = auth_db()->prepare(
        "UPDATE {$t} SET password_hash = ?, must_change_pw = ?, failed_count = 0, locked_until = NULL WHERE user_id = ?"
    );
    $st->execute([$hash, $mustChange ? 1 : 0, $userId]);
}

/**
 * このアプリでの権限を求める。
 *   local   … lp_users.role
 *   central … auth_app_roles（app_key 単位）。行が無ければ default_role
 * 利用権限が無い場合は null。
 */
function resolve_role(int $userId): ?string
{
    if (lp_auth_mode() === 'local') {
        $st = db()->prepare('SELECT role FROM lp_users WHERE user_id = ?');
        $st->execute([$userId]);
        $role = $st->fetchColumn();
        return $role !== false ? (string)$role : null;
    }
    $st = auth_db()->prepare('SELECT role FROM auth_app_roles WHERE app_key = ? AND user_id = ?');
    $st->execute([lp_app_key(), $userId]);
    $role = $st->fetchColumn();
    if ($role === false) {
        return lp_default_role();
    }
    return in_array($role, ['admin', 'viewer'], true) ? (string)$role : lp_default_role();
}

/** このアプリでの権限を設定（central では app_key 単位の行を作成／更新） */
function assign_role(int $userId, string $role, ?int $grantedBy = null): void
{
    if (lp_auth_mode() === 'local') {
        $st = db()->prepare('UPDATE lp_users SET role = ? WHERE user_id = ?');
        $st->execute([$role, $userId]);
        return;
    }
    $st = auth_db()->prepare(
        'INSERT INTO auth_app_roles (app_key, user_id, role, granted_by)
         VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE role = VALUES(role), granted_by = VALUES(granted_by)'
    );
    $st->execute([lp_app_key(), $userId, $role, $grantedBy]);
}

/** このアプリの利用権限を取り消す（central のみ。local では viewer に落とす） */
function revoke_role(int $userId): void
{
    if (lp_auth_mode() === 'local') {
        assign_role($userId, 'viewer');
        return;
    }
    $st = auth_db()->prepare('DELETE FROM auth_app_roles WHERE app_key = ? AND user_id = ?');
    $st->execute([lp_app_key(), $userId]);
}

/** 利用者一覧（このアプリでの権限つき） */
function provider_list_users(): array
{
    if (lp_auth_mode() === 'local') {
        return db()->query(
            'SELECT user_id, login_id, display_name, email, dept, role, is_active,
                    must_change_pw, last_login_at, created_at
               FROM lp_users ORDER BY role DESC, login_id'
        )->fetchAll();
    }
    $st = auth_db()->prepare(
        'SELECT u.user_id, u.login_id, u.display_name, u.email, u.dept, u.is_active,
                u.must_change_pw, u.last_login_at, u.created_at, r.role
           FROM auth_users u
           LEFT JOIN auth_app_roles r ON r.user_id = u.user_id AND r.app_key = ?
          ORDER BY (r.role IS NULL), r.role DESC, u.login_id'
    );
    $st->execute([lp_app_key()]);
    return $st->fetchAll();
}

/** このアプリで管理者権限を持つ有効な利用者の人数 */
function active_admin_count(): int
{
    if (lp_auth_mode() === 'local') {
        return (int)db()->query(
            "SELECT COUNT(*) FROM lp_users WHERE role = 'admin' AND is_active = 1"
        )->fetchColumn();
    }
    $st = auth_db()->prepare(
        "SELECT COUNT(*) FROM auth_app_roles r
           JOIN auth_users u ON u.user_id = r.user_id
          WHERE r.app_key = ? AND r.role = 'admin' AND u.is_active = 1"
    );
    $st->execute([lp_app_key()]);
    return (int)$st->fetchColumn();
}

/**
 * この画面からアカウント自体（新規作成・削除・停止・パスワード）を操作できるか。
 * 共通ユーザーDB運用では、アカウントの管理は共通の利用者管理アプリ側に一本化し、
 * 各アプリからは「このアプリでの権限」だけを変更します。
 */
function can_manage_accounts(): bool
{
    return lp_auth_mode() === 'local';
}

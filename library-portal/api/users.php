<?php
/**
 * 利用者管理 API（すべて管理者のみ）
 *   GET    api/users.php              … 利用者一覧（このアプリでの権限つき）
 *   POST   api/users.php              … 新規登録 ※ローカル運用時のみ
 *   PATCH  api/users.php?id=3         … 権限の変更（共通DB運用時はこれのみ）
 *   DELETE api/users.php?id=3         … 削除 ※ローカル運用時のみ
 *
 * 共通ユーザーデータベース運用（auth_mode = 'central'）のとき：
 *   ・アカウント自体の作成・削除・停止・パスワード変更は共通の利用者管理側で行います
 *   ・この画面からは「このアプリでの権限（管理者／閲覧のみ／権限なし）」だけを変更します
 *
 * 安全策：
 *   ・最後の管理者を降格／停止／削除／権限取消することはできません
 *   ・自分自身の権限変更・停止・削除はできません
 */
declare(strict_types=1);
require_once __DIR__ . '/../includes/auth.php';

$me     = api_require_admin();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

function fetch_user(int $id): ?array
{
    return provider_get_user($id);
}

// ---------------------------------------------------------- 一覧
if ($method === 'GET') {
    $rows = provider_list_users();

    $out = array_map(static fn(array $r): array => [
        'userId'       => (int)$r['user_id'],
        'loginId'      => $r['login_id'],
        'name'         => $r['display_name'],
        'email'        => $r['email'] ?? '',
        'dept'         => $r['dept'] ?? '',
        'role'         => $r['role'] ?? '',          // 空文字＝このアプリの権限なし
        'isActive'     => (int)$r['is_active'] === 1,
        'mustChangePw' => (int)($r['must_change_pw'] ?? 0) === 1,
        'lastLoginAt'  => $r['last_login_at'],
        'createdAt'    => $r['created_at'],
    ], $rows);

    json_out([
        'me'      => (int)$me['user_id'],
        'authMode' => lp_auth_mode(),                 // local / central
        'appKey'  => lp_app_key(),
        'canManageAccounts' => can_manage_accounts(), // central では false
        'defaultRole' => lp_default_role(),           // 権限行が無い利用者の既定
        'users'   => $out,
    ]);
}

api_verify_csrf();

// ---------------------------------------------------------- 新規登録
if ($method === 'POST') {
    if (!can_manage_accounts()) {
        json_error('共通ユーザーデータベース運用のため、アカウントの新規作成は共通の利用者管理から行ってください。', 400);
    }
    $b       = json_body();
    $loginId = s($b, 'loginId', 64);
    $name    = s($b, 'name', 60);
    $email   = s($b, 'email', 120);
    $dept    = s($b, 'dept', 60);
    $role    = s($b, 'role', 10);
    $pw      = isset($b['password']) ? (string)$b['password'] : '';

    if (!preg_match('/^[A-Za-z0-9._-]{3,64}$/', $loginId)) {
        json_error('ログインIDは半角英数字（. _ -）3文字以上で入力してください。');
    }
    if ($name === '') {
        json_error('表示名は必須です。');
    }
    if (!in_array($role, ['admin', 'viewer'], true)) {
        json_error('権限の指定が不正です。');
    }
    if ($email !== '' && !filter_var($email, FILTER_VALIDATE_EMAIL)) {
        json_error('メールアドレスの形式が正しくありません。');
    }
    if ($problem = password_problem($pw)) {
        json_error($problem);
    }

    $dup = db()->prepare('SELECT 1 FROM lp_users WHERE login_id = ?');
    $dup->execute([$loginId]);
    if ($dup->fetchColumn()) {
        json_error('そのログインIDは既に使われています。');
    }

    $st = db()->prepare(
        'INSERT INTO lp_users (login_id, display_name, email, dept, role, password_hash, is_active, must_change_pw)
         VALUES (?, ?, ?, ?, ?, ?, 1, 1)'
    );
    $st->execute([
        $loginId, $name, $email !== '' ? $email : null, $dept !== '' ? $dept : null,
        $role, password_hash($pw, PASSWORD_DEFAULT),
    ]);
    audit('user.create', $loginId, '権限：' . $role);
    json_out(['ok' => true, 'userId' => (int)db()->lastInsertId()], 201);
}

// ---------------------------------------------------------- 変更
if ($method === 'PATCH' || $method === 'PUT') {
    $id = (int)($_GET['id'] ?? 0);
    $target = fetch_user($id);
    if (!$target) {
        json_error('対象の利用者が見つかりません。', 404);
    }
    $isSelf = $id === (int)$me['user_id'];
    $b = json_body();
    $currentRole = resolve_role($id);

    // ---- 権限（このアプリでの role）。共通DB運用でも変更できる ----
    if (array_key_exists('role', $b)) {
        $role = s($b, 'role', 10);
        $revoke = ($role === '' || $role === 'none');
        if (!$revoke && !in_array($role, ['admin', 'viewer'], true)) {
            json_error('権限の指定が不正です。');
        }
        if ($revoke && lp_auth_mode() === 'local') {
            json_error('この運用形態では権限の取り消しはできません。停止または削除をご利用ください。');
        }
        if (($revoke ? null : $role) !== $currentRole) {
            if ($isSelf) {
                json_error('自分自身の権限は変更できません。他の管理者に依頼してください。');
            }
            if ($currentRole === 'admin' && active_admin_count() <= 1) {
                json_error('このアプリの管理者が0人になるため変更できません。先に他の利用者を管理者にしてください。');
            }
            if ($revoke) {
                revoke_role($id);
                audit('role.revoke', $target['login_id'], 'app=' . lp_app_key());
            } else {
                assign_role($id, $role, (int)$me['user_id']);
                audit('role.assign', $target['login_id'], 'app=' . lp_app_key() . ' role=' . $role);
            }
        }
    }

    // ---- ここから下はアカウント自体の情報。共通DB運用では共通側で管理する ----
    $accountFields = ['isActive', 'name', 'email', 'dept', 'password'];
    $touchesAccount = (bool)array_intersect($accountFields, array_keys($b));
    if ($touchesAccount && !can_manage_accounts()) {
        json_error('共通ユーザーデータベース運用のため、氏名・停止・パスワードの変更は共通の利用者管理から行ってください。', 400);
    }

    $sets = [];
    $args = [];

    if (array_key_exists('isActive', $b)) {
        $active = !empty($b['isActive']) ? 1 : 0;
        if ($active !== (int)$target['is_active']) {
            if ($isSelf && $active === 0) {
                json_error('自分自身を停止することはできません。');
            }
            if ($active === 0 && $currentRole === 'admin' && active_admin_count() <= 1) {
                json_error('管理者が0人になるため停止できません。');
            }
            $sets[] = 'is_active = ?';
            $args[] = $active;
            if ($active === 1) {
                $sets[] = 'failed_count = 0';
                $sets[] = 'locked_until = NULL';
            }
        }
    }

    foreach ([['name', 'display_name', 60], ['email', 'email', 120], ['dept', 'dept', 60]] as [$key, $col, $max]) {
        if (array_key_exists($key, $b)) {
            $v = s($b, $key, $max);
            if ($key === 'name' && $v === '') {
                json_error('表示名は空にできません。');
            }
            if ($key === 'email' && $v !== '' && !filter_var($v, FILTER_VALIDATE_EMAIL)) {
                json_error('メールアドレスの形式が正しくありません。');
            }
            $sets[] = "$col = ?";
            $args[] = $v !== '' ? $v : null;
        }
    }

    if (!empty($b['password'])) {
        $pw = (string)$b['password'];
        if ($problem = password_problem($pw)) {
            json_error($problem);
        }
        $sets[] = 'password_hash = ?';
        $args[] = password_hash($pw, PASSWORD_DEFAULT);
        $sets[] = 'must_change_pw = 1';
        $sets[] = 'failed_count = 0';
        $sets[] = 'locked_until = NULL';
    }

    if ($sets) {
        $args[] = $id;
        $st = db()->prepare('UPDATE lp_users SET ' . implode(', ', $sets) . ' WHERE user_id = ?');
        $st->execute($args);
        audit('user.update', $target['login_id'], implode(' / ', $sets));
    }

    json_out(['ok' => true, 'changed' => true]);
}

// ---------------------------------------------------------- 削除
if ($method === 'DELETE') {
    $id = (int)($_GET['id'] ?? 0);
    $target = fetch_user($id);
    if (!$target) {
        json_error('対象の利用者が見つかりません。', 404);
    }
    if ($id === (int)$me['user_id']) {
        json_error('自分自身は削除できません。');
    }
    if (!can_manage_accounts()) {
        json_error('共通ユーザーデータベース運用のため、アカウントの削除は共通の利用者管理から行ってください。'
                 . 'このアプリを使わせない場合は、権限を「権限なし」に変更してください。', 400);
    }
    if (resolve_role($id) === 'admin' && (int)$target['is_active'] === 1 && active_admin_count() <= 1) {
        json_error('管理者が0人になるため削除できません。');
    }
    $st = db()->prepare('DELETE FROM lp_users WHERE user_id = ?');
    $st->execute([$id]);
    audit('user.delete', $target['login_id']);
    json_out(['ok' => true]);
}

json_error('許可されていないメソッドです。', 405);

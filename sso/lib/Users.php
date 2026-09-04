<?php
declare(strict_types=1);

/** 共通ユーザーマスタの操作。 */
final class Users
{
    /** @return array<string,mixed>|null */
    public static function find(int $id): ?array
    {
        return Db::one('SELECT * FROM users WHERE id = :id', ['id' => $id]);
    }

    /** @return array<string,mixed>|null */
    public static function findByUsername(string $username): ?array
    {
        return Db::one('SELECT * FROM users WHERE username = :u', ['u' => $username]);
    }

    /**
     * 一覧（検索・絞り込み・ページング）。
     * @return array{rows: list<array<string,mixed>>, total: int, pages: int, page: int}
     */
    public static function search(string $keyword = '', string $status = '', int $page = 1, int $perPage = 25): array
    {
        $where  = [];
        $params = [];
        if ($keyword !== '') {
            $where[] = '(u.username LIKE :kw OR u.email LIKE :kw OR u.display_name LIKE :kw OR u.department LIKE :kw)';
            $params['kw'] = '%' . str_replace(['%', '_'], ['\\%', '\\_'], $keyword) . '%';
        }
        if ($status === 'active' || $status === 'suspended') {
            $where[] = 'u.status = :st';
            $params['st'] = $status;
        }
        $sqlWhere = $where === [] ? '' : 'WHERE ' . implode(' AND ', $where);

        $total   = (int) Db::value("SELECT COUNT(*) FROM users u {$sqlWhere}", $params);
        $perPage = max(1, min(200, $perPage));
        $pages   = max(1, (int) ceil($total / $perPage));
        $page    = max(1, min($pages, $page));
        $offset  = ($page - 1) * $perPage;

        $rows = Db::all(
            "SELECT u.*,
                    (SELECT COUNT(*) FROM app_permissions p
                      WHERE p.user_id = u.id AND p.effect = 'allow') AS allow_count
               FROM users u
               {$sqlWhere}
              ORDER BY u.username ASC
              LIMIT {$perPage} OFFSET {$offset}",
            $params
        );

        return ['rows' => $rows, 'total' => $total, 'pages' => $pages, 'page' => $page];
    }

    /** @return list<array<string,mixed>> */
    public static function allActive(): array
    {
        return Db::all("SELECT * FROM users ORDER BY status ASC, username ASC");
    }

    /**
     * 入力チェック。問題があれば「項目名 => メッセージ」を返す。
     * @return array<string,string>
     */
    public static function validate(array $input, ?int $exceptId = null, bool $requirePassword = true): array
    {
        $errors = [];

        $username = trim((string) ($input['username'] ?? ''));
        if ($username === '') {
            $errors['username'] = 'ログインIDは必須です。';
        } elseif (preg_match('/\A[A-Za-z0-9._@-]{3,64}\z/', $username) !== 1) {
            $errors['username'] = 'ログインIDは英数字と . _ - @ の3〜64文字で入力してください。';
        } else {
            $dup = $exceptId === null
                ? Db::one('SELECT id FROM users WHERE username = :u', ['u' => $username])
                : Db::one('SELECT id FROM users WHERE username = :u AND id <> :id', ['u' => $username, 'id' => $exceptId]);
            if ($dup !== null) {
                $errors['username'] = 'このログインIDは既に使われています。';
            }
        }

        $email = trim((string) ($input['email'] ?? ''));
        if ($email !== '') {
            if (filter_var($email, FILTER_VALIDATE_EMAIL) === false) {
                $errors['email'] = 'メールアドレスの形式が正しくありません。';
            } else {
                $dup = $exceptId === null
                    ? Db::one('SELECT id FROM users WHERE email = :e', ['e' => $email])
                    : Db::one('SELECT id FROM users WHERE email = :e AND id <> :id', ['e' => $email, 'id' => $exceptId]);
                if ($dup !== null) {
                    $errors['email'] = 'このメールアドレスは既に登録されています。';
                }
            }
        }

        $password = (string) ($input['password'] ?? '');
        if ($requirePassword || $password !== '') {
            $error = self::checkPasswordPolicy($password);
            if ($error !== null) {
                $errors['password'] = $error;
            } elseif (isset($input['password_confirm']) && $password !== (string) $input['password_confirm']) {
                $errors['password_confirm'] = '確認用パスワードが一致しません。';
            }
        }

        return $errors;
    }

    public static function checkPasswordPolicy(string $password): ?string
    {
        $min = (int) Config::get('password.min_length', 10);
        if (mb_strlen($password) < $min) {
            return "パスワードは{$min}文字以上にしてください。";
        }
        if (preg_match('/[A-Za-z]/', $password) !== 1 || preg_match('/[0-9]/', $password) !== 1) {
            return 'パスワードには英字と数字の両方を含めてください。';
        }
        return null;
    }

    public static function create(array $input, ?int $actorId = null): int
    {
        Db::run(
            'INSERT INTO users (username, email, password_hash, display_name, department, status, is_admin, must_change_password)
             VALUES (:username, :email, :hash, :display_name, :department, :status, :is_admin, :must_change)',
            [
                'username'     => trim((string) $input['username']),
                'email'        => ($e = trim((string) ($input['email'] ?? ''))) === '' ? null : $e,
                'hash'         => password_hash((string) $input['password'], PASSWORD_DEFAULT),
                'display_name' => trim((string) ($input['display_name'] ?? '')),
                'department'   => trim((string) ($input['department'] ?? '')),
                'status'       => ($input['status'] ?? 'active') === 'suspended' ? 'suspended' : 'active',
                'is_admin'     => !empty($input['is_admin']) ? 1 : 0,
                'must_change'  => !empty($input['must_change_password']) ? 1 : 0,
            ]
        );
        $id = Db::insertId();
        Audit::log('user.create', $actorId, $id, null, ['username' => $input['username']]);
        return $id;
    }

    public static function update(int $id, array $input, ?int $actorId = null): void
    {
        $before = self::find($id);
        Db::run(
            'UPDATE users
                SET username = :username, email = :email, display_name = :display_name,
                    department = :department, status = :status, is_admin = :is_admin,
                    must_change_password = :must_change
              WHERE id = :id',
            [
                'id'           => $id,
                'username'     => trim((string) $input['username']),
                'email'        => ($e = trim((string) ($input['email'] ?? ''))) === '' ? null : $e,
                'display_name' => trim((string) ($input['display_name'] ?? '')),
                'department'   => trim((string) ($input['department'] ?? '')),
                'status'       => ($input['status'] ?? 'active') === 'suspended' ? 'suspended' : 'active',
                'is_admin'     => !empty($input['is_admin']) ? 1 : 0,
                'must_change'  => !empty($input['must_change_password']) ? 1 : 0,
            ]
        );
        // 停止したユーザーのログインセッションは即座に無効化する
        if (($input['status'] ?? '') === 'suspended' && ($before['status'] ?? '') !== 'suspended') {
            Auth::revokeAllSessions($id);
        }
        Audit::log('user.update', $actorId, $id, null, ['username' => $input['username']]);
    }

    public static function setPassword(int $id, string $password, ?int $actorId = null, bool $mustChange = false): void
    {
        Db::run(
            'UPDATE users
                SET password_hash = :hash, must_change_password = :must,
                    failed_attempts = 0, locked_until = NULL
              WHERE id = :id',
            ['id' => $id, 'hash' => password_hash($password, PASSWORD_DEFAULT), 'must' => $mustChange ? 1 : 0]
        );
        Auth::revokeAllSessions($id);
        Audit::log('user.password_reset', $actorId, $id);
    }

    public static function delete(int $id, ?int $actorId = null): void
    {
        $user = self::find($id);
        if ($user === null) {
            return;
        }
        // 権限・セッション・チケットは外部キーの ON DELETE CASCADE で一緒に消える
        Db::run('DELETE FROM users WHERE id = :id', ['id' => $id]);
        Audit::log('user.delete', $actorId, null, null, ['username' => $user['username'], 'user_id' => $id]);
    }

    public static function unlock(int $id, ?int $actorId = null): void
    {
        Db::run('UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = :id', ['id' => $id]);
        Audit::log('user.unlock', $actorId, $id);
    }

    public static function isLocked(array $user): bool
    {
        return !empty($user['locked_until']) && strtotime((string) $user['locked_until']) > time();
    }

    /** 管理者が自分自身を締め出さないための確認。 */
    public static function otherActiveAdminExists(int $exceptId): bool
    {
        return (int) Db::value(
            "SELECT COUNT(*) FROM users WHERE is_admin = 1 AND status = 'active' AND id <> :id",
            ['id' => $exceptId]
        ) > 0;
    }
}

<?php
declare(strict_types=1);

/** SSO に参加するウェブアプリケーションの登録簿。 */
final class Apps
{
    /** @return list<array<string,mixed>> */
    public static function all(bool $activeOnly = false): array
    {
        $sql = 'SELECT * FROM apps' . ($activeOnly ? " WHERE status = 'active'" : '')
             . ' ORDER BY sort_order ASC, name ASC';
        return Db::all($sql);
    }

    /** @return array<string,mixed>|null */
    public static function find(int $id): ?array
    {
        return Db::one('SELECT * FROM apps WHERE id = :id', ['id' => $id]);
    }

    /** @return array<string,mixed>|null */
    public static function findByKey(string $key): ?array
    {
        return Db::one('SELECT * FROM apps WHERE app_key = :k', ['k' => $key]);
    }

    public static function newSecret(): string
    {
        return random_token();
    }

    /** @return array<string,string> 項目名 => エラーメッセージ */
    public static function validate(array $input, ?int $exceptId = null): array
    {
        $errors = [];

        $key = trim((string) ($input['app_key'] ?? ''));
        if ($key === '') {
            $errors['app_key'] = 'アプリ識別子は必須です。';
        } elseif (preg_match('/\A[a-z0-9][a-z0-9_-]{1,63}\z/', $key) !== 1) {
            $errors['app_key'] = 'アプリ識別子は半角英小文字・数字・- _ の2〜64文字にしてください。';
        } else {
            $dup = $exceptId === null
                ? Db::one('SELECT id FROM apps WHERE app_key = :k', ['k' => $key])
                : Db::one('SELECT id FROM apps WHERE app_key = :k AND id <> :id', ['k' => $key, 'id' => $exceptId]);
            if ($dup !== null) {
                $errors['app_key'] = 'このアプリ識別子は既に使われています。';
            }
        }

        if (trim((string) ($input['name'] ?? '')) === '') {
            $errors['name'] = 'アプリ名は必須です。';
        }

        $base = trim((string) ($input['base_url'] ?? ''));
        if ($base === '') {
            $errors['base_url'] = 'アプリのURLは必須です。';
        } else {
            $parts = parse_url($base);
            if (!is_array($parts) || empty($parts['scheme']) || empty($parts['host'])
                || !in_array(strtolower((string) $parts['scheme']), ['http', 'https'], true)) {
                $errors['base_url'] = 'URLは http:// または https:// から始まる形式で入力してください。';
            }
        }

        return $errors;
    }

    public static function create(array $input, ?int $actorId = null): int
    {
        Db::run(
            'INSERT INTO apps (app_key, name, description, base_url, app_secret, default_policy, status, sort_order)
             VALUES (:app_key, :name, :description, :base_url, :secret, :policy, :status, :sort_order)',
            [
                'app_key'     => trim((string) $input['app_key']),
                'name'        => trim((string) $input['name']),
                'description' => trim((string) ($input['description'] ?? '')),
                'base_url'    => rtrim(trim((string) $input['base_url']), '/'),
                'secret'      => (string) ($input['app_secret'] ?? self::newSecret()),
                'policy'      => ($input['default_policy'] ?? 'deny') === 'allow' ? 'allow' : 'deny',
                'status'      => ($input['status'] ?? 'active') === 'disabled' ? 'disabled' : 'active',
                'sort_order'  => (int) ($input['sort_order'] ?? 100),
            ]
        );
        $id = Db::insertId();
        Audit::log('app.create', $actorId, null, $id, ['app_key' => $input['app_key']]);
        return $id;
    }

    public static function update(int $id, array $input, ?int $actorId = null): void
    {
        Db::run(
            'UPDATE apps
                SET app_key = :app_key, name = :name, description = :description, base_url = :base_url,
                    default_policy = :policy, status = :status, sort_order = :sort_order
              WHERE id = :id',
            [
                'id'          => $id,
                'app_key'     => trim((string) $input['app_key']),
                'name'        => trim((string) $input['name']),
                'description' => trim((string) ($input['description'] ?? '')),
                'base_url'    => rtrim(trim((string) $input['base_url']), '/'),
                'policy'      => ($input['default_policy'] ?? 'deny') === 'allow' ? 'allow' : 'deny',
                'status'      => ($input['status'] ?? 'active') === 'disabled' ? 'disabled' : 'active',
                'sort_order'  => (int) ($input['sort_order'] ?? 100),
            ]
        );
        Audit::log('app.update', $actorId, null, $id, ['app_key' => $input['app_key']]);
    }

    public static function regenerateSecret(int $id, ?int $actorId = null): string
    {
        $secret = self::newSecret();
        Db::run('UPDATE apps SET app_secret = :s WHERE id = :id', ['s' => $secret, 'id' => $id]);
        Audit::log('app.secret_rotate', $actorId, null, $id);
        return $secret;
    }

    public static function delete(int $id, ?int $actorId = null): void
    {
        $app = self::find($id);
        if ($app === null) {
            return;
        }
        Db::run('DELETE FROM apps WHERE id = :id', ['id' => $id]);
        Audit::log('app.delete', $actorId, null, null, ['app_key' => $app['app_key'], 'app_id' => $id]);
    }

    /** 既存アプリ内のユーザーIDとの対応付け。 */
    public static function linkExternalUser(int $appId, int $userId, string $externalId, ?int $actorId = null): void
    {
        if ($externalId === '') {
            Db::run('DELETE FROM app_user_links WHERE app_id = :a AND user_id = :u', ['a' => $appId, 'u' => $userId]);
        } else {
            Db::run(
                'INSERT INTO app_user_links (app_id, user_id, external_user_id)
                 VALUES (:a, :u, :e)
                 ON DUPLICATE KEY UPDATE external_user_id = VALUES(external_user_id)',
                ['a' => $appId, 'u' => $userId, 'e' => $externalId]
            );
        }
        Audit::log('app.link_user', $actorId, $userId, $appId, ['external_user_id' => $externalId]);
    }

    /** @return array<int,string> app_id => external_user_id */
    public static function externalIdsFor(int $userId): array
    {
        $out = [];
        foreach (Db::all('SELECT app_id, external_user_id FROM app_user_links WHERE user_id = :u', ['u' => $userId]) as $row) {
            $out[(int) $row['app_id']] = (string) $row['external_user_id'];
        }
        return $out;
    }

    public static function externalId(int $appId, int $userId): ?string
    {
        $v = Db::value(
            'SELECT external_user_id FROM app_user_links WHERE app_id = :a AND user_id = :u',
            ['a' => $appId, 'u' => $userId]
        );
        return $v === null ? null : (string) $v;
    }
}

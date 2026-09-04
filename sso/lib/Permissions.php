<?php
declare(strict_types=1);

/**
 * ユーザー × アプリ の閲覧許可。
 *
 *  - app_permissions に行があれば、その effect（allow / deny）が優先される
 *  - 行が無ければ apps.default_policy に従う
 *  - ユーザーが停止中、またはアプリが無効なら、常に拒否
 */
final class Permissions
{
    public const ALLOW   = 'allow';
    public const DENY    = 'deny';
    public const DEFAULT = '';      // 個別設定なし（既定に従う）

    /** その組み合わせの個別設定を返す。無ければ '' 。 */
    public static function explicitEffect(int $userId, int $appId): string
    {
        $v = Db::value(
            'SELECT effect FROM app_permissions WHERE user_id = :u AND app_id = :a',
            ['u' => $userId, 'a' => $appId]
        );
        return $v === null ? self::DEFAULT : (string) $v;
    }

    /** 既定も加味した最終的な allow / deny 。 */
    public static function effectiveEffect(array $user, array $app): string
    {
        if (($user['status'] ?? '') !== 'active' || ($app['status'] ?? '') !== 'active') {
            return self::DENY;
        }
        if (!empty($user['is_admin']) && Config::get('admin_bypass_permissions', false)) {
            return self::ALLOW;
        }
        $explicit = self::explicitEffect((int) $user['id'], (int) $app['id']);
        if ($explicit !== self::DEFAULT) {
            return $explicit;
        }
        return ($app['default_policy'] ?? 'deny') === 'allow' ? self::ALLOW : self::DENY;
    }

    public static function isAllowed(array $user, array $app): bool
    {
        return self::effectiveEffect($user, $app) === self::ALLOW;
    }

    /**
     * 拒否理由を日本語で返す（許可されている場合は null）。
     */
    public static function denyReason(array $user, array $app): ?string
    {
        if (($user['status'] ?? '') !== 'active') {
            return 'このユーザーアカウントは停止されています。';
        }
        if (($app['status'] ?? '') !== 'active') {
            return 'このアプリケーションは現在無効化されています。';
        }
        return self::isAllowed($user, $app) ? null : 'このアプリケーションの閲覧が許可されていません。';
    }

    /** そのユーザーが実際に開けるアプリの一覧。 */
    public static function allowedApps(array $user): array
    {
        $out = [];
        foreach (Apps::all(true) as $app) {
            if (self::isAllowed($user, $app)) {
                $out[] = $app;
            }
        }
        return $out;
    }

    /** @return array<int,string> app_id => 'allow'|'deny'（個別設定のみ） */
    public static function explicitMapForUser(int $userId): array
    {
        $map = [];
        foreach (Db::all('SELECT app_id, effect FROM app_permissions WHERE user_id = :u', ['u' => $userId]) as $row) {
            $map[(int) $row['app_id']] = (string) $row['effect'];
        }
        return $map;
    }

    /** @return array<int,array<int,string>> user_id => [app_id => effect]（個別設定のみ） */
    public static function explicitMatrix(array $userIds): array
    {
        if ($userIds === []) {
            return [];
        }
        $place = implode(',', array_fill(0, count($userIds), '?'));
        $rows  = Db::all("SELECT user_id, app_id, effect FROM app_permissions WHERE user_id IN ({$place})",
                         array_values($userIds));
        $matrix = [];
        foreach ($rows as $row) {
            $matrix[(int) $row['user_id']][(int) $row['app_id']] = (string) $row['effect'];
        }
        return $matrix;
    }

    /**
     * 設定を書き換える。$effect が '' なら個別設定を削除して既定に戻す。
     */
    public static function set(int $userId, int $appId, string $effect, ?int $actorId = null, string $note = ''): void
    {
        if ($effect === self::DEFAULT) {
            Db::run('DELETE FROM app_permissions WHERE user_id = :u AND app_id = :a', ['u' => $userId, 'a' => $appId]);
        } elseif ($effect === self::ALLOW || $effect === self::DENY) {
            Db::run(
                'INSERT INTO app_permissions (user_id, app_id, effect, granted_by, note)
                 VALUES (:u, :a, :e, :g, :n)
                 ON DUPLICATE KEY UPDATE effect = VALUES(effect), granted_by = VALUES(granted_by), note = VALUES(note)',
                ['u' => $userId, 'a' => $appId, 'e' => $effect, 'g' => $actorId, 'n' => $note]
            );
        } else {
            return;
        }
        Audit::log('permission.set', $actorId, $userId, $appId, ['effect' => $effect === '' ? '(既定)' : $effect]);
    }

    /**
     * ユーザー1人ぶんの設定をまとめて保存する。
     * @param array<int,string> $effects app_id => 'allow'|'deny'|''
     */
    public static function replaceForUser(int $userId, array $effects, ?int $actorId = null): void
    {
        Db::transaction(static function () use ($userId, $effects, $actorId): void {
            foreach ($effects as $appId => $effect) {
                $current = self::explicitEffect($userId, (int) $appId);
                if ($current !== $effect) {
                    self::set($userId, (int) $appId, (string) $effect, $actorId);
                }
            }
        });
    }

    /**
     * 複数ユーザー × 1アプリ をまとめて設定する（一括付与・一括剥奪）。
     * @param list<int> $userIds
     */
    public static function bulkSet(array $userIds, int $appId, string $effect, ?int $actorId = null): int
    {
        $count = 0;
        Db::transaction(static function () use ($userIds, $appId, $effect, $actorId, &$count): void {
            foreach ($userIds as $userId) {
                self::set((int) $userId, $appId, $effect, $actorId);
                $count++;
            }
        });
        return $count;
    }

    /** アプリごとの許可人数（一覧表示用）。 @return array<int,int> */
    public static function allowCountsByApp(): array
    {
        $counts = [];
        $rows = Db::all(
            "SELECT p.app_id, COUNT(*) AS c
               FROM app_permissions p
               JOIN users u ON u.id = p.user_id AND u.status = 'active'
              WHERE p.effect = 'allow'
              GROUP BY p.app_id"
        );
        foreach ($rows as $row) {
            $counts[(int) $row['app_id']] = (int) $row['c'];
        }
        return $counts;
    }
}

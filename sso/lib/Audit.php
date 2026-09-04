<?php
declare(strict_types=1);

/** 監査ログ。「誰が・誰に対して・何をしたか」を残す。 */
final class Audit
{
    public static function log(
        string $action,
        ?int $actorId = null,
        ?int $targetUserId = null,
        ?int $appId = null,
        array $detail = []
    ): void {
        try {
            Db::run(
                'INSERT INTO audit_logs (actor_id, target_user_id, app_id, action, detail, ip)
                 VALUES (:actor, :target, :app, :action, :detail, :ip)',
                [
                    'actor'  => $actorId,
                    'target' => $targetUserId,
                    'app'    => $appId,
                    'action' => $action,
                    'detail' => $detail === [] ? null : json_encode($detail, JSON_UNESCAPED_UNICODE),
                    'ip'     => client_ip(),
                ]
            );
        } catch (Throwable $e) {
            // 監査ログの失敗で本処理を止めない
            error_log('[welsys-sso] audit failed: ' . $e->getMessage());
        }
    }

    /** @return list<array<string,mixed>> */
    public static function recent(int $limit = 50): array
    {
        $limit = max(1, min(500, $limit));
        return Db::all(
            "SELECT l.*, a.username AS actor_name, t.username AS target_name, p.name AS app_name
               FROM audit_logs l
               LEFT JOIN users a ON a.id = l.actor_id
               LEFT JOIN users t ON t.id = l.target_user_id
               LEFT JOIN apps  p ON p.id = l.app_id
              ORDER BY l.id DESC
              LIMIT {$limit}"
        );
    }
}

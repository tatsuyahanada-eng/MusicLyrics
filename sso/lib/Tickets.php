<?php
declare(strict_types=1);

/**
 * 使い捨てチケット。
 *
 * 認証サーバーはユーザーの素性をURLに直接載せず、寿命数十秒・1回限りのチケットだけを
 * ブラウザ経由でアプリに渡す。アプリはそれをサーバー間通信（validate.php）で
 * 引き換えて、初めてユーザー情報を受け取る。
 */
final class Tickets
{
    /** 発行してチケット文字列（平文）を返す。 */
    public static function issue(array $app, array $session, string $redirectUrl): string
    {
        $ticket = random_token();
        Db::run(
            'INSERT INTO sso_tickets (id, app_id, user_id, session_id, redirect_url, ip, expires_at)
             VALUES (:id, :app, :user, :session, :redirect, :ip, :expires)',
            [
                'id'       => token_hash($ticket),
                'app'      => (int) $app['id'],
                'user'     => (int) $session['user_id'],
                'session'  => (string) $session['id'],
                'redirect' => $redirectUrl,
                'ip'       => client_ip(),
                'expires'  => at(time() + (int) Config::get('ticket_ttl', 60)),
            ]
        );
        return $ticket;
    }

    /**
     * チケットを引き換える。成功したら行を返し、二重利用できないよう消費済みにする。
     * @return array{ok:bool, row?:array<string,mixed>, error?:string}
     */
    public static function consume(string $ticket, int $appId): array
    {
        if (preg_match('/\A[0-9a-f]{64}\z/', $ticket) !== 1) {
            return ['ok' => false, 'error' => 'invalid_ticket'];
        }
        $id = token_hash($ticket);

        return Db::transaction(static function () use ($id, $appId): array {
            $row = Db::one('SELECT * FROM sso_tickets WHERE id = :id FOR UPDATE', ['id' => $id]);
            if ($row === null) {
                return ['ok' => false, 'error' => 'invalid_ticket'];
            }
            if ($row['consumed_at'] !== null) {
                return ['ok' => false, 'error' => 'ticket_already_used'];
            }
            if (strtotime((string) $row['expires_at']) <= time()) {
                return ['ok' => false, 'error' => 'ticket_expired'];
            }
            if ((int) $row['app_id'] !== $appId) {
                return ['ok' => false, 'error' => 'ticket_app_mismatch'];
            }
            Db::run('UPDATE sso_tickets SET consumed_at = NOW() WHERE id = :id', ['id' => $id]);
            return ['ok' => true, 'row' => $row];
        });
    }
}

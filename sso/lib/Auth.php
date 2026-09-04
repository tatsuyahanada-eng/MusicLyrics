<?php
declare(strict_types=1);

/**
 * 認証サーバー側のログイン状態。
 *
 * ログイン状態は「sso_sessions テーブルの1行」＋「ブラウザに渡すトークンCookie」で表現する。
 * Cookie に入るのはトークン本体、DB に入るのはその sha256。
 * これが全アプリで共有される “ただ1つのログイン” の実体。
 */
final class Auth
{
    private static ?array $cachedSession = null;
    private static bool $resolved = false;

    // ── ログイン ─────────────────────────────────────────────────
    /**
     * @return array{ok:bool, user?:array<string,mixed>, error?:string}
     */
    public static function attempt(string $username, string $password): array
    {
        $user = Users::findByUsername($username);

        // ユーザーが存在しない場合も同じくらいの時間をかけ、存在の有無を悟らせない
        if ($user === null) {
            password_verify($password, '$2y$12$usesomesillystringfoeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX');
            Audit::log('login.failed', null, null, null, ['username' => $username, 'reason' => 'unknown_user']);
            return ['ok' => false, 'error' => 'ログインIDまたはパスワードが正しくありません。'];
        }

        if (Users::isLocked($user)) {
            Audit::log('login.locked', null, (int) $user['id'], null, ['username' => $username]);
            $until = date('H:i', strtotime((string) $user['locked_until']));
            return ['ok' => false, 'error' => "ログイン試行が多すぎます。{$until} 以降に再度お試しください。"];
        }

        if (!password_verify($password, (string) $user['password_hash'])) {
            self::registerFailure($user);
            Audit::log('login.failed', null, (int) $user['id'], null, ['username' => $username, 'reason' => 'bad_password']);
            return ['ok' => false, 'error' => 'ログインIDまたはパスワードが正しくありません。'];
        }

        if (($user['status'] ?? '') !== 'active') {
            Audit::log('login.suspended', null, (int) $user['id'], null, ['username' => $username]);
            return ['ok' => false, 'error' => 'このアカウントは停止されています。管理者にお問い合わせください。'];
        }

        // 保存済みハッシュのアルゴリズムが古ければ、この機会に貼り替える
        if (password_needs_rehash((string) $user['password_hash'], PASSWORD_DEFAULT)) {
            Db::run('UPDATE users SET password_hash = :h WHERE id = :id', [
                'h'  => password_hash($password, PASSWORD_DEFAULT),
                'id' => (int) $user['id'],
            ]);
        }

        Db::run(
            'UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login_at = NOW() WHERE id = :id',
            ['id' => (int) $user['id']]
        );

        return ['ok' => true, 'user' => $user];
    }

    private static function registerFailure(array $user): void
    {
        $max     = (int) Config::get('login.max_attempts', 5);
        $lockFor = (int) Config::get('login.lockout_seconds', 900);
        $attempts = (int) $user['failed_attempts'] + 1;

        if ($attempts >= $max) {
            Db::run(
                'UPDATE users SET failed_attempts = :n, locked_until = :until WHERE id = :id',
                ['n' => $attempts, 'until' => at(time() + $lockFor), 'id' => (int) $user['id']]
            );
        } else {
            Db::run('UPDATE users SET failed_attempts = :n WHERE id = :id',
                    ['n' => $attempts, 'id' => (int) $user['id']]);
        }
    }

    // ── セッションの発行と破棄 ───────────────────────────────────
    /** ログイン成功後に呼ぶ。SSO セッションを作り、Cookie を発行する。 */
    public static function startSession(array $user): string
    {
        $token = random_token();
        $id    = token_hash($token);

        Db::run(
            'INSERT INTO sso_sessions (id, user_id, ip, user_agent, expires_at)
             VALUES (:id, :user_id, :ip, :ua, :expires)',
            [
                'id'      => $id,
                'user_id' => (int) $user['id'],
                'ip'      => client_ip(),
                'ua'      => user_agent(),
                'expires' => at(time() + (int) Config::get('session.absolute_timeout', 43200)),
            ]
        );

        self::sendCookie($token, (int) Config::get('session.absolute_timeout', 43200));
        self::$cachedSession = null;
        self::$resolved      = false;

        Audit::log('login.success', (int) $user['id'], (int) $user['id'], null, ['session' => substr($id, 0, 12)]);
        return $id;
    }

    private static function sendCookie(string $value, int $lifetime): void
    {
        if (PHP_SAPI === 'cli') {
            return;
        }
        $params = [
            'expires'  => $lifetime > 0 ? time() + $lifetime : time() - 3600,
            'path'     => '/',
            'secure'   => (bool) Config::get('cookie.secure', true),
            'httponly' => true,
            'samesite' => (string) Config::get('cookie.samesite', 'Lax'),
        ];
        $domain = (string) Config::get('cookie.domain', '');
        if ($domain !== '') {
            $params['domain'] = $domain;
        }
        setcookie((string) Config::get('cookie.name', 'WELSYS_SSO'), $value, $params);
    }

    /** ブラウザの Cookie から現在のセッション行（＋ユーザー情報）を解決する。 */
    public static function currentSession(): ?array
    {
        if (self::$resolved) {
            return self::$cachedSession;
        }
        self::$resolved = true;
        self::$cachedSession = null;

        $token = (string) ($_COOKIE[(string) Config::get('cookie.name', 'WELSYS_SSO')] ?? '');
        if ($token === '' || preg_match('/\A[0-9a-f]{64}\z/', $token) !== 1) {
            return null;
        }

        $session = self::sessionById(token_hash($token));
        if ($session === null) {
            self::sendCookie('', -1);
            return null;
        }

        // last_seen_at はアイドルタイムアウトの基準。毎回書くと重いので60秒に一度だけ。
        if (strtotime((string) $session['last_seen_at']) < time() - 60) {
            Db::run('UPDATE sso_sessions SET last_seen_at = NOW() WHERE id = :id', ['id' => $session['id']]);
        }

        self::$cachedSession = $session;
        return $session;
    }

    /**
     * セッションIDから、まだ有効なセッションとユーザーを取り出す。
     * 無効（期限切れ・失効・ユーザー停止）なら null。
     */
    public static function sessionById(string $sessionId): ?array
    {
        $row = Db::one(
            'SELECT s.id, s.user_id, s.created_at, s.last_seen_at, s.expires_at, s.revoked_at,
                    u.username, u.email, u.display_name, u.department, u.status, u.is_admin,
                    u.must_change_password
               FROM sso_sessions s
               JOIN users u ON u.id = s.user_id
              WHERE s.id = :id',
            ['id' => $sessionId]
        );
        if ($row === null) {
            return null;
        }
        if ($row['revoked_at'] !== null) {
            return null;
        }
        if (strtotime((string) $row['expires_at']) <= time()) {
            return null;
        }
        $idle = (int) Config::get('session.idle_timeout', 1800);
        if ($idle > 0 && strtotime((string) $row['last_seen_at']) + $idle < time()) {
            return null;
        }
        if (($row['status'] ?? '') !== 'active') {
            return null;
        }
        return $row;
    }

    /** セッション行からユーザー情報の配列を作る。 */
    public static function userOf(array $session): array
    {
        return [
            'id'                   => (int) $session['user_id'],
            'username'             => (string) $session['username'],
            'email'                => $session['email'],
            'display_name'         => (string) $session['display_name'],
            'department'           => (string) $session['department'],
            'status'               => (string) $session['status'],
            'is_admin'             => (int) $session['is_admin'],
            'must_change_password' => (int) $session['must_change_password'],
        ];
    }

    /** @return array<string,mixed>|null */
    public static function currentUser(): ?array
    {
        $session = self::currentSession();
        return $session === null ? null : self::userOf($session);
    }

    public static function logout(): void
    {
        $session = self::currentSession();
        if ($session !== null) {
            self::revokeSession((string) $session['id']);
            Audit::log('logout', (int) $session['user_id'], (int) $session['user_id']);
        }
        self::sendCookie('', -1);
        self::$cachedSession = null;
        self::$resolved      = true;
    }

    public static function revokeSession(string $sessionId): void
    {
        Db::run('UPDATE sso_sessions SET revoked_at = NOW() WHERE id = :id AND revoked_at IS NULL',
                ['id' => $sessionId]);
    }

    /** そのユーザーの全端末のログインを打ち切る（停止・パスワード変更時など）。 */
    public static function revokeAllSessions(int $userId): void
    {
        Db::run('UPDATE sso_sessions SET revoked_at = NOW() WHERE user_id = :u AND revoked_at IS NULL',
                ['u' => $userId]);
    }

    // ── 画面用のガード ───────────────────────────────────────────
    /** 未ログインならログイン画面へ送る。 */
    public static function requireUser(): array
    {
        $user = self::currentUser();
        if ($user === null) {
            $next = (string) ($_SERVER['REQUEST_URI'] ?? '/');
            redirect(Config::baseUrl('login.php') . '?' . http_build_query(['next' => $next]));
        }
        return $user;
    }

    /** 管理コンソール用。管理者以外は 403。 */
    public static function requireAdmin(): array
    {
        $user = self::requireUser();
        if (empty($user['is_admin'])) {
            http_response_code(403);
            View::head('User Management', $user);
            echo '<main class="container"><div class="card"><h1>権限がありません</h1>'
               . '<p>この画面は管理者のみ利用できます。</p>'
               . '<p><a class="btn" href="' . h(Config::baseUrl('index.php')) . '">ポータルへ戻る</a></p>'
               . '</div></main>';
            View::foot();
            exit;
        }
        return $user;
    }

    /** 期限切れのセッションとチケットを掃除する。 */
    public static function gc(): void
    {
        Db::run('DELETE FROM sso_sessions WHERE expires_at < DATE_SUB(NOW(), INTERVAL 7 DAY)');
        Db::run('DELETE FROM sso_tickets  WHERE expires_at < DATE_SUB(NOW(), INTERVAL 1 DAY)');
    }
}

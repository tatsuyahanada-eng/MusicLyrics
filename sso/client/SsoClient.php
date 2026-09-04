<?php
/**
 * WELSYS SSO クライアント。
 *
 * 既存のウェブアプリに置くのはこのファイルと sso_config.php、
 * それに戻り先ページ（sso_callback.php）の3つだけ。
 * アプリ側のデータベースやログイン画面には手を入れない。
 *
 *   require __DIR__ . '/sso/sso_guard.php';   // ← 各ページの先頭にこれだけ
 *   echo $SSO_USER['display_name'];
 */
declare(strict_types=1);

final class SsoClient
{
    private array $config;

    /**
     * @param array{
     *   idp_url:string, app_key:string, app_secret:string, callback_url:string,
     *   recheck_interval?:int, session_key?:string, verify_ssl?:bool, timeout?:int
     * } $config
     */
    public function __construct(array $config)
    {
        foreach (['idp_url', 'app_key', 'app_secret', 'callback_url'] as $key) {
            if (empty($config[$key])) {
                throw new InvalidArgumentException("SSO 設定の {$key} が未設定です。");
            }
        }
        $config['idp_url']          = rtrim((string) $config['idp_url'], '/');
        $config['recheck_interval'] = (int) ($config['recheck_interval'] ?? 60);
        $config['session_key']      = (string) ($config['session_key'] ?? '_welsys_sso');
        $config['verify_ssl']       = (bool) ($config['verify_ssl'] ?? true);
        $config['timeout']          = (int) ($config['timeout'] ?? 5);
        $this->config = $config;
    }

    // ── 公開API ─────────────────────────────────────────────────
    /**
     * ログイン必須のページの先頭で呼ぶ。未ログインなら認証サーバーへ送る。
     * @return array<string,mixed> ログイン中のユーザー
     */
    public function requireLogin(): array
    {
        $this->startSession();
        $state = $this->sessionData();

        if ($state !== null) {
            // まだ有効か、たまに認証サーバーに確認する
            // （管理画面で権限を外された／停止された場合に効かせるため）
            if ($this->needsRecheck($state)) {
                $result = $this->call('check', ['session_id' => $state['session_id']]);
                if (!empty($result['ok'])) {
                    $this->store($result);
                    return $result['user'];
                }
                $this->clearSession();
                $this->redirectToIdp();
            }
            return $state['user'];
        }

        $this->redirectToIdp();
    }

    /** ログインしていれば情報を返す。未ログインでもリダイレクトしない。 */
    public function user(): ?array
    {
        $this->startSession();
        $state = $this->sessionData();
        return $state === null ? null : $state['user'];
    }

    public function isLoggedIn(): bool
    {
        return $this->user() !== null;
    }

    /**
     * 戻り先ページ（sso_callback.php）で呼ぶ。
     * チケットを引き換えてアプリ側のセッションを作り、元のページへ戻す。
     */
    public function handleCallback(): never
    {
        $this->startSession();

        $ticket = (string) ($_GET['sso_ticket'] ?? '');
        $state  = (string) ($_GET['state'] ?? '');
        $expect = (string) ($_SESSION[$this->config['session_key'] . '_state'] ?? '');
        unset($_SESSION[$this->config['session_key'] . '_state']);

        if ($ticket === '') {
            $this->fail('チケットがありません。もう一度ログインしてください。');
        }
        if ($expect === '' || !hash_equals($expect, $state)) {
            $this->fail('セッションの状態が一致しません。もう一度ログインしてください。');
        }

        $result = $this->call('validate', ['ticket' => $ticket]);
        if (empty($result['ok'])) {
            $this->fail($this->errorMessage((string) ($result['error'] ?? 'unknown')));
        }

        // セッション固定攻撃を防ぐため、ログイン確定時にIDを振り直す
        session_regenerate_id(true);
        $this->store($result);

        $intended = (string) ($_SESSION[$this->config['session_key'] . '_intended'] ?? '');
        unset($_SESSION[$this->config['session_key'] . '_intended']);

        $this->go($this->safeIntended($intended));
    }

    /**
     * ログアウト。$global を true にすると他のアプリのログインもまとめて切る。
     */
    public function logout(bool $global = true): never
    {
        $this->startSession();
        $state = $this->sessionData();

        if ($global && $state !== null) {
            $this->call('logout', ['session_id' => $state['session_id']]);
        }
        $this->clearSession();

        if ($global) {
            $this->go($this->config['idp_url'] . '/logout.php');
        }
        $this->go($this->appOrigin());
    }

    /** 認証サーバーのログイン画面へのURL（任意のリンクに使える）。 */
    public function loginUrl(?string $returnTo = null): string
    {
        $this->startSession();
        return $this->buildAuthorizeUrl($returnTo ?? $this->currentUrl());
    }

    // ── 内部処理 ────────────────────────────────────────────────
    private function startSession(): void
    {
        if (session_status() === PHP_SESSION_NONE) {
            session_start();
        }
    }

    /** @return array{user:array,session_id:string,checked_at:int}|null */
    private function sessionData(): ?array
    {
        $data = $_SESSION[$this->config['session_key']] ?? null;
        if (!is_array($data) || empty($data['user']) || empty($data['session_id'])) {
            return null;
        }
        return $data;
    }

    private function needsRecheck(array $state): bool
    {
        $interval = max(1, (int) ($state['recheck_interval'] ?? $this->config['recheck_interval']));
        return (int) ($state['checked_at'] ?? 0) + $interval < time();
    }

    private function store(array $result): void
    {
        // 認証サーバーの推奨値とアプリ側の設定の、短い方を採用する
        // （権限を外したときに、より早く反映される側に合わせる）
        $serverInterval = (int) ($result['recheck_interval'] ?? 0);
        $interval = $serverInterval > 0
            ? min($serverInterval, (int) $this->config['recheck_interval'])
            : (int) $this->config['recheck_interval'];

        $_SESSION[$this->config['session_key']] = [
            'user'             => $result['user'],
            'session_id'       => (string) $result['session_id'],
            'expires_at'       => (string) ($result['expires_at'] ?? ''),
            'recheck_interval' => $interval,
            'checked_at'       => time(),
        ];
    }

    private function clearSession(): void
    {
        unset($_SESSION[$this->config['session_key']]);
    }

    private function redirectToIdp(): never
    {
        $_SESSION[$this->config['session_key'] . '_intended'] = $this->currentUrl();
        $this->go($this->buildAuthorizeUrl($this->currentUrl()));
    }

    private function buildAuthorizeUrl(string $intended): string
    {
        $state = bin2hex(random_bytes(16));
        $_SESSION[$this->config['session_key'] . '_state']    = $state;
        $_SESSION[$this->config['session_key'] . '_intended'] = $intended;

        return $this->config['idp_url'] . '/authorize.php?' . http_build_query([
            'app'    => $this->config['app_key'],
            'return' => $this->config['callback_url'],
            'state'  => $state,
        ]);
    }

    /** 認証サーバーへのサーバー間通信。ブラウザは経由しない。 */
    private function call(string $op, array $params): array
    {
        $subject = (string) ($params['ticket'] ?? $params['session_id'] ?? '');
        $body    = $params + [
            'op'        => $op,
            'app_key'   => $this->config['app_key'],
            'signature' => hash_hmac('sha256', $op . "\n" . $subject, (string) $this->config['app_secret']),
        ];

        $url  = $this->config['idp_url'] . '/validate.php';
        $post = http_build_query($body);

        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_POST           => true,
                CURLOPT_POSTFIELDS     => $post,
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_TIMEOUT        => $this->config['timeout'],
                CURLOPT_SSL_VERIFYPEER => $this->config['verify_ssl'],
                CURLOPT_SSL_VERIFYHOST => $this->config['verify_ssl'] ? 2 : 0,
                CURLOPT_HTTPHEADER     => ['Content-Type: application/x-www-form-urlencoded'],
            ]);
            $raw = curl_exec($ch);
            $err = curl_error($ch);
            curl_close($ch);
            if ($raw === false) {
                error_log('[welsys-sso] validate failed: ' . $err);
                return ['ok' => false, 'error' => 'network_error'];
            }
        } else {
            $context = stream_context_create([
                'http' => [
                    'method'        => 'POST',
                    'header'        => "Content-Type: application/x-www-form-urlencoded\r\n",
                    'content'       => $post,
                    'timeout'       => $this->config['timeout'],
                    'ignore_errors' => true,
                ],
                'ssl' => [
                    'verify_peer'      => $this->config['verify_ssl'],
                    'verify_peer_name' => $this->config['verify_ssl'],
                ],
            ]);
            $raw = @file_get_contents($url, false, $context);
            if ($raw === false) {
                return ['ok' => false, 'error' => 'network_error'];
            }
        }

        $decoded = json_decode((string) $raw, true);
        return is_array($decoded) ? $decoded : ['ok' => false, 'error' => 'bad_response'];
    }

    private function currentUrl(): string
    {
        $https  = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
               || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
        $scheme = $https ? 'https' : 'http';
        $host   = (string) ($_SERVER['HTTP_HOST'] ?? 'localhost');
        $uri    = (string) ($_SERVER['REQUEST_URI'] ?? '/');
        return $scheme . '://' . $host . $uri;
    }

    /** 自アプリのオリジン（callback_url から導出）。 */
    private function appOrigin(): string
    {
        $p = parse_url((string) $this->config['callback_url']);
        $origin = ($p['scheme'] ?? 'https') . '://' . ($p['host'] ?? 'localhost');
        if (!empty($p['port'])) {
            $origin .= ':' . $p['port'];
        }
        return $origin;
    }

    /** 戻り先が自アプリ内かを確認する（オープンリダイレクト対策）。 */
    private function safeIntended(string $url): string
    {
        if ($url === '') {
            return $this->appOrigin();
        }
        $origin = $this->appOrigin();
        return str_starts_with($url, $origin . '/') || $url === $origin ? $url : $origin;
    }

    private function go(string $url): never
    {
        header('Location: ' . $url);
        exit;
    }

    private function errorMessage(string $code): string
    {
        return match ($code) {
            'ticket_expired'      => 'ログインの有効期限が切れました。もう一度お試しください。',
            'ticket_already_used' => 'このログインは既に使用されています。もう一度お試しください。',
            'not_permitted'       => 'このアプリケーションの閲覧が許可されていません。管理者にお問い合わせください。',
            'session_expired'     => 'ログインセッションが切れました。もう一度ログインしてください。',
            'network_error'       => '認証サーバーに接続できませんでした。しばらくしてからお試しください。',
            default               => 'ログイン処理に失敗しました（' . $code . '）。',
        };
    }

    private function fail(string $message): never
    {
        http_response_code(400);
        header('Content-Type: text/html; charset=UTF-8');
        $login = htmlspecialchars($this->config['idp_url'] . '/login.php', ENT_QUOTES, 'UTF-8');
        // 認証サーバーのCSSに依存しないよう、最低限の装飾はここに直接書く
        echo '<!DOCTYPE html><html lang="ja"><meta charset="UTF-8">'
           . '<meta name="viewport" content="width=device-width, initial-scale=1">'
           . '<title>ログインできませんでした</title>'
           . '<body style="margin:0;background:#F7F5FA;color:#1F1B26;'
           . 'font-family:\'Hiragino Kaku Gothic ProN\',\'Yu Gothic\',Meiryo,system-ui,sans-serif;line-height:1.8">'
           . '<div style="max-width:520px;margin:80px auto;padding:0 20px">'
           . '<div style="background:#fff;border:1px solid #E3DEEA;border-top:5px solid #FDB927;'
           . 'border-radius:10px;padding:26px;box-shadow:0 8px 24px rgba(43,15,74,.07)">'
           . '<h1 style="font-size:20px;margin:0 0 12px;color:#552583">ログインできませんでした</h1>'
           . '<p style="margin:0 0 18px">' . htmlspecialchars($message, ENT_QUOTES, 'UTF-8') . '</p>'
           . '<p style="margin:0"><a href="' . $login . '" '
           . 'style="display:inline-block;padding:8px 16px;border-radius:7px;background:#552583;'
           . 'color:#fff;font-weight:600;text-decoration:none">ログイン画面へ戻る</a></p>'
           . '</div></div></body></html>';
        exit;
    }
}

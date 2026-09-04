<?php
declare(strict_types=1);

/** POST フォーム用の CSRF トークン。 */
final class Csrf
{
    public static function token(): string
    {
        if (empty($_SESSION['_csrf']) || !is_string($_SESSION['_csrf'])) {
            $_SESSION['_csrf'] = random_token();
        }
        return $_SESSION['_csrf'];
    }

    public static function field(): string
    {
        return '<input type="hidden" name="_csrf" value="' . h(self::token()) . '">';
    }

    public static function check(): void
    {
        $sent = (string) ($_POST['_csrf'] ?? '');
        if ($sent === '' || !hash_equals(self::token(), $sent)) {
            http_response_code(419);
            header('Content-Type: text/html; charset=UTF-8');
            exit('<h1>セッションが切れました</h1><p>お手数ですが、前の画面に戻ってやり直してください。</p>');
        }
    }
}

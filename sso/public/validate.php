<?php
/**
 * サーバー間通信のエンドポイント（JSON）。アプリ側からのみ呼ばれる。
 *
 *   POST validate.php
 *     op         : validate | check | logout
 *     app_key    : アプリ識別子
 *     ticket     : op=validate のとき。authorize.php が発行した使い捨てチケット
 *     session_id : op=check / op=logout のとき
 *     signature  : hash_hmac('sha256', op . "\n" . (ticket|session_id), app_secret)
 *
 * app_secret はブラウザを通らないので、チケットを盗まれても
 * ユーザー情報の引き換えはできない。
 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

header('Cache-Control: no-store');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_out(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$op      = post('op', 'validate');
$appKey  = post('app_key');
$subject = post('ticket') !== '' ? post('ticket') : post('session_id');
$sig     = post('signature');

$app = $appKey === '' ? null : Apps::findByKey($appKey);
if ($app === null || ($app['status'] ?? '') !== 'active') {
    json_out(['ok' => false, 'error' => 'unknown_app'], 401);
}

$expected = hash_hmac('sha256', $op . "\n" . $subject, (string) $app['app_secret']);
if ($sig === '' || !hash_equals($expected, $sig)) {
    Audit::log('sso.bad_signature', null, null, (int) $app['id'], ['op' => $op]);
    json_out(['ok' => false, 'error' => 'bad_signature'], 401);
}

/** アプリに返すユーザー情報。 */
function user_payload(array $session, array $app): array
{
    $user = Auth::userOf($session);
    return [
        'id'           => $user['id'],
        'username'     => $user['username'],
        'display_name' => $user['display_name'],
        'email'        => $user['email'],
        'department'   => $user['department'],
        'is_admin'     => (bool) $user['is_admin'],
        // 既存アプリが自前のユーザーIDを使い続けられるようにするための対応表
        'external_user_id' => Apps::externalId((int) $app['id'], (int) $user['id']),
    ];
}

switch ($op) {
    // ── チケットの引き換え ───────────────────────────────────
    case 'validate':
        $result = Tickets::consume($subject, (int) $app['id']);
        if (!$result['ok']) {
            json_out(['ok' => false, 'error' => $result['error']], 400);
        }
        $ticket  = $result['row'];
        $session = Auth::sessionById((string) $ticket['session_id']);
        if ($session === null) {
            json_out(['ok' => false, 'error' => 'session_expired'], 401);
        }
        $user = Auth::userOf($session);
        if (!Permissions::isAllowed($user, $app)) {
            json_out(['ok' => false, 'error' => 'not_permitted'], 403);
        }
        Audit::log('sso.validated', (int) $user['id'], (int) $user['id'], (int) $app['id']);
        json_out([
            'ok'               => true,
            'user'             => user_payload($session, $app),
            'session_id'       => (string) $session['id'],
            'expires_at'       => (string) $session['expires_at'],
            'recheck_interval' => (int) Config::get('recheck_interval', 60),
        ]);
        // no break

    // ── まだログイン中か・まだ許可されているかの確認 ─────────
    case 'check':
        $session = preg_match('/\A[0-9a-f]{64}\z/', $subject) === 1
            ? Auth::sessionById($subject)
            : null;
        if ($session === null) {
            json_out(['ok' => false, 'error' => 'session_invalid'], 401);
        }
        $user = Auth::userOf($session);
        if (!Permissions::isAllowed($user, $app)) {
            json_out(['ok' => false, 'error' => 'not_permitted'], 403);
        }
        json_out([
            'ok'               => true,
            'user'             => user_payload($session, $app),
            'session_id'       => (string) $session['id'],
            'expires_at'       => (string) $session['expires_at'],
            'recheck_interval' => (int) Config::get('recheck_interval', 60),
        ]);
        // no break

    // ── アプリ側からのログアウト要求（全アプリのSSOを切る） ──
    case 'logout':
        if (preg_match('/\A[0-9a-f]{64}\z/', $subject) === 1) {
            $session = Db::one('SELECT user_id FROM sso_sessions WHERE id = :id', ['id' => $subject]);
            Auth::revokeSession($subject);
            Audit::log('logout.by_app', $session ? (int) $session['user_id'] : null,
                       $session ? (int) $session['user_id'] : null, (int) $app['id']);
        }
        json_out(['ok' => true]);
        // no break

    default:
        json_out(['ok' => false, 'error' => 'unknown_op'], 400);
}

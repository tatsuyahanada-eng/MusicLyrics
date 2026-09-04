<?php
/**
 * POST api/password.php … ログイン中の利用者が自分のパスワードを変更する
 */
declare(strict_types=1);
require_once __DIR__ . '/../includes/auth.php';

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    json_error('許可されていないメソッドです。', 405);
}

$me = api_require_login();
api_verify_csrf();

$b       = json_body();
$current = isset($b['current']) ? (string)$b['current'] : '';
$next    = isset($b['next']) ? (string)$b['next'] : '';

$st = db()->prepare('SELECT password_hash FROM lp_users WHERE user_id = ?');
$st->execute([$me['user_id']]);
$hash = (string)$st->fetchColumn();

if (!password_verify($current, $hash)) {
    json_error('現在のパスワードが正しくありません。');
}
if ($problem = password_problem($next)) {
    json_error($problem);
}
if (password_verify($next, $hash)) {
    json_error('現在と異なるパスワードを設定してください。');
}

$up = db()->prepare('UPDATE lp_users SET password_hash = ?, must_change_pw = 0 WHERE user_id = ?');
$up->execute([password_hash($next, PASSWORD_DEFAULT), $me['user_id']]);
$_SESSION['user']['must_change_pw'] = false;

audit('password.change', $me['login_id']);
json_out(['ok' => true]);

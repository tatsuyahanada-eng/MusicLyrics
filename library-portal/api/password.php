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

$row  = provider_get_user((int)$me['user_id']);
$hash = (string)($row['password_hash'] ?? '');

if (!password_verify($current, $hash)) {
    json_error('現在のパスワードが正しくありません。');
}
if ($problem = password_problem($next)) {
    json_error($problem);
}
if (password_verify($next, $hash)) {
    json_error('現在と異なるパスワードを設定してください。');
}

provider_set_password((int)$me['user_id'], password_hash($next, PASSWORD_DEFAULT), false);
$_SESSION['user']['must_change_pw'] = false;

audit('password.change', $me['login_id']);
json_out(['ok' => true]);

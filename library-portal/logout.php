<?php
/**
 * ログアウトは SSO 側に任せる（このアプリだけでなく、認証サーバーに
 * ログインしている他のアプリからも一緒にログアウトする）。
 * sso/sso_guard.php はここでは読み込まない（ログアウトのためにもう一度
 * ログインを要求してしまうため）。
 */
declare(strict_types=1);
require __DIR__ . '/sso/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso/sso_config.php');
$sso->logout(true);

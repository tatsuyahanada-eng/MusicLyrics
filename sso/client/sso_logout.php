<?php
/** ログアウト用。アプリのログアウトリンクをここへ向ける。 */
declare(strict_types=1);

require_once __DIR__ . '/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso_config.php');

// 第1引数 false にすると、このアプリだけログアウトする（他アプリは維持）
$sso->logout(true);

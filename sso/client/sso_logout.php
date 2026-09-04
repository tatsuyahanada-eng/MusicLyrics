<?php
/**
 * ログアウト用。アプリのログアウトリンクを /sso/sso_logout.php へ向ける。
 * 他のクライアントファイルと同じ <アプリの公開ディレクトリ>/sso/ に置く。
 */
declare(strict_types=1);

require_once __DIR__ . '/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso_config.php');

// 第1引数 false にすると、このアプリだけログアウトする（他アプリは維持）
$sso->logout(true);

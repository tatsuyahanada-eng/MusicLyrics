<?php
/**
 * 認証サーバーからの戻り先。SsoClient.php / sso_config.php と同じ
 * <アプリの公開ディレクトリ>/sso/ に置き、管理画面の callback_url を
 * https://<アプリのURL>/sso/sso_callback.php に合わせる。
 * このファイル自体は sso_guard.php を読み込まないこと（無限ループになる）。
 */
declare(strict_types=1);

require_once __DIR__ . '/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso_config.php');
$sso->handleCallback();

<?php
/**
 * 認証サーバーからの戻り先。アプリの公開ディレクトリに置き、
 * そのURLを管理画面の「アプリのURL」配下（既定では <base_url>/sso_callback.php）に合わせる。
 * このファイル自体は sso_guard.php を読み込まないこと（無限ループになる）。
 */
declare(strict_types=1);

require_once __DIR__ . '/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso_config.php');
$sso->handleCallback();

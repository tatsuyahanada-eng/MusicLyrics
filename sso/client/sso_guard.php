<?php
/**
 * 既存ページを SSO で保護するための1行インクルード。
 *
 *   <?php require __DIR__ . '/sso/sso_guard.php'; ?>
 *
 * これだけで、未ログインなら認証サーバーへ飛び、戻ってきたときには
 * $SSO_USER にログイン中のユーザー情報が入っている。
 *
 *   $SSO_USER['username']          共通のログインID
 *   $SSO_USER['display_name']      氏名
 *   $SSO_USER['email']             メールアドレス
 *   $SSO_USER['department']        所属
 *   $SSO_USER['external_user_id']  このアプリが元々持っているユーザーID（対応付けした場合）
 */
declare(strict_types=1);

require_once __DIR__ . '/SsoClient.php';

$sso = new SsoClient(require __DIR__ . '/sso_config.php');

/** @var array<string,mixed> $SSO_USER */
$SSO_USER = $sso->requireLogin();

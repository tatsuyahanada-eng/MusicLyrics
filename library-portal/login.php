<?php
/**
 * ログインは SSO（sso/sso_guard.php）が行う。このページ自体は
 * 「未ログインなら認証サーバーへ、ログイン済みなら元の画面へ」
 * 送り届けるだけの中継地点。
 */
declare(strict_types=1);
require __DIR__ . '/sso/sso_guard.php';

$to = (string)($_GET['to'] ?? '');
// オープンリダイレクト防止：同一アプリ内のパスのみ許可
$safe = (preg_match('#^/?[A-Za-z0-9._/-]*$#', $to) && strpos($to, '//') === false && $to !== '')
    ? $to : 'index.php';
header('Location: ' . $safe);
exit;

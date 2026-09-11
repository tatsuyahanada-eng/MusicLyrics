<?php
/**
 * 設定画面。
 *
 * 現在はSSO（usersso）でログインできた人は全員管理者として扱っており、
 * このアプリ独自の権限管理は行っていない。将来、SSO側から利用者ごとの
 * ラベル（役割・グループなど）を受け取れるようになった場合に、
 * 「管理者／閲覧のみ」のような権限管理をこの画面に追加する予定。
 */
declare(strict_types=1);
require __DIR__ . '/sso/sso_guard.php';
require_once __DIR__ . '/includes/auth.php';

$user = require_login();
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>設定 ｜ ライブラリポータル</title>
  <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=6">
  <link rel="icon" type="image/png" sizes="16x16" href="assets/favicon-16.png?v=6">
  <link rel="apple-touch-icon" href="assets/icon-192.png?v=6">
  <link rel="manifest" href="manifest.webmanifest">
  <meta name="theme-color" content="#007a33">
  <link rel="stylesheet" href="assets/library.css?v=29">
</head>
<body class="lp-body">

  <header class="lp-header">
    <div class="lp-header-inner">
      <h1 class="lp-title">
        <a class="lp-title-link" href="index.php" title="TOPへ戻る">
          <img class="lp-app-icon" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
          <span class="lp-title-text">
            設定
            <small class="lp-title-sub">ライブラリポータル</small>
          </span>
        </a>
      </h1>
      <div class="lp-header-actions">
        <a class="lp-btn lp-btn-ghost lp-btn-sm" href="index.php">← ライブラリ一覧</a>
      </div>
    </div>
  </header>

  <main class="lp-main">
    <p class="lp-help">
      ログインは共通ログイン（SSO）で行っており、現在はSSOでログインできた人は
      全員「管理者」として扱っています。利用者ごとの権限管理（管理者／閲覧のみの
      切り替えなど）は、SSO側から利用者の役割を受け取れるようになった時点で
      この画面に追加する予定です。
    </p>
    <p class="lp-empty">現在、この画面で行える設定はありません。</p>
  </main>

  <footer class="lp-footer">
    <div class="lp-footer-inner">
      <p class="lp-footer-status">ライブラリポータル ／ <?= h($user['login_id']) ?> としてログイン中（管理者）</p>
      <p class="lp-footer-copyright">
        <img class="lp-footer-logo" src="assets/welsys-logo.jpg" alt="WELSYS ロゴ">
        <span>&copy; <?= date('Y') ?> ウェルシス株式会社</span>
      </p>
    </div>
  </footer>
</body>
</html>

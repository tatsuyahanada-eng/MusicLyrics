<?php
declare(strict_types=1);
require_once __DIR__ . '/includes/auth.php';

lp_session_start();
if (is_logged_in()) {
    header('Location: index.php');
    exit;
}

$error = '';
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    if (!verify_csrf($_POST['csrf'] ?? null)) {
        $error = 'セッションの有効期限が切れました。もう一度お試しください。';
    } else {
        $result = lp_login(trim((string)($_POST['login_id'] ?? '')), (string)($_POST['password'] ?? ''));
        if ($result === true) {
            $to = (string)($_POST['to'] ?? '');
            // オープンリダイレクト防止：同一アプリ内のパスのみ許可
            $safe = (preg_match('#^/?[A-Za-z0-9._/-]*$#', $to) && strpos($to, '//') === false && $to !== '')
                ? $to : 'index.php';
            header('Location: ' . $safe);
            exit;
        }
        $error = (string)$result;
    }
}
$csrf = csrf_token();
$to   = (string)($_GET['to'] ?? ($_POST['to'] ?? ''));
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ログイン ｜ ライブラリポータル</title>
  <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=5">
  <link rel="icon" type="image/png" sizes="16x16" href="assets/favicon-16.png?v=5">
  <link rel="apple-touch-icon" href="assets/icon-192.png?v=5">
  <link rel="manifest" href="manifest.webmanifest">
  <meta name="theme-color" content="#007a33">
  <link rel="stylesheet" href="assets/library.css?v=9">
</head>
<body class="lp-body lp-body-auth">
  <main class="lp-auth">
    <div class="lp-auth-card">
      <div class="lp-auth-icons">
        <img class="lp-app-icon lp-app-icon-lg" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
        <img class="lp-auth-logo" src="assets/welsys-logo.jpg" alt="WELSYS ロゴ">
      </div>
      <h1 class="lp-auth-title">ライブラリポータル</h1>
      <p class="lp-auth-sub">関連会社 共有ライブラリ ／ 更新履歴管理</p>

      <?php if ($error !== ''): ?>
        <p class="lp-auth-error" role="alert"><?= h($error) ?></p>
      <?php endif; ?>

      <form method="post" class="lp-auth-form" autocomplete="on">
        <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
        <input type="hidden" name="to" value="<?= h($to) ?>">
        <label class="lp-field">
          <span class="lp-field-label">ログインID</span>
          <input class="lp-input" type="text" name="login_id" required autofocus
                 autocapitalize="none" autocomplete="username">
        </label>
        <label class="lp-field">
          <span class="lp-field-label">パスワード</span>
          <input class="lp-input" type="password" name="password" required autocomplete="current-password">
        </label>
        <button class="lp-btn lp-btn-primary lp-btn-block" type="submit">ログイン</button>
      </form>
      <p class="lp-auth-note">アカウントの発行・パスワードの再設定は管理者へご依頼ください。</p>
    </div>
  </main>
</body>
</html>

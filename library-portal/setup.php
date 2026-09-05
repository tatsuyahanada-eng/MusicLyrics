<?php
/**
 * 初回セットアップ — 最初の管理者アカウントを作成します。
 * 利用者が1人でも登録済みの場合、この画面は自動的に無効になります。
 * 作成が終わったらこのファイルをサーバーから削除してください。
 */
declare(strict_types=1);
require_once __DIR__ . '/includes/auth.php';

lp_session_start();

$tableReady = true;
$userCount  = 0;
try {
    $userCount = (int)db()->query('SELECT COUNT(*) FROM lp_users')->fetchColumn();
} catch (Throwable $e) {
    $tableReady = false;
}

$done = false;
$error = '';

if ($tableReady && $userCount === 0 && ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    if (!verify_csrf($_POST['csrf'] ?? null)) {
        $error = 'セッションの有効期限が切れました。再読み込みしてお試しください。';
    } else {
        $loginId = trim((string)($_POST['login_id'] ?? ''));
        $name    = trim((string)($_POST['display_name'] ?? ''));
        $pw      = (string)($_POST['password'] ?? '');
        $pw2     = (string)($_POST['password2'] ?? '');

        if (!preg_match('/^[A-Za-z0-9._-]{3,64}$/', $loginId)) {
            $error = 'ログインIDは半角英数字（. _ -）3文字以上で入力してください。';
        } elseif ($name === '') {
            $error = '表示名を入力してください。';
        } elseif ($pw !== $pw2) {
            $error = 'パスワードが一致しません。';
        } elseif ($problem = password_problem($pw)) {
            $error = $problem;
        } else {
            $st = db()->prepare(
                "INSERT INTO lp_users (login_id, display_name, role, password_hash, is_active, must_change_pw)
                 VALUES (?, ?, 'admin', ?, 1, 0)"
            );
            $st->execute([$loginId, $name, password_hash($pw, PASSWORD_DEFAULT)]);
            $done = true;
        }
    }
}
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>初回セットアップ ｜ ライブラリポータル</title>
  <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=6">
  <link rel="icon" type="image/png" sizes="16x16" href="assets/favicon-16.png?v=6">
  <link rel="apple-touch-icon" href="assets/icon-192.png?v=6">
  <meta name="theme-color" content="#007a33">
  <link rel="stylesheet" href="assets/library.css?v=15">
</head>
<body class="lp-body lp-body-auth">
  <main class="lp-auth">
    <div class="lp-auth-card">
      <div class="lp-auth-icons">
        <img class="lp-app-icon lp-app-icon-lg" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
        <img class="lp-auth-logo" src="assets/welsys-logo.jpg" alt="WELSYS ロゴ">
      </div>
      <h1 class="lp-auth-title">初回セットアップ</h1>

      <?php if (!$tableReady): ?>
        <p class="lp-auth-error">テーブルが見つかりません。先に <code>sql/schema.sql</code> をデータベースへ取り込んでください。</p>
      <?php elseif ($done): ?>
        <p class="lp-auth-ok">管理者アカウントを作成しました。<br>
          セキュリティのため、<strong>この setup.php をサーバーから削除</strong>してください。</p>
        <p><a class="lp-btn lp-btn-primary lp-btn-block" href="login.php">ログイン画面へ</a></p>
      <?php elseif ($userCount > 0): ?>
        <p class="lp-auth-error">すでに利用者が登録されているため、この画面は使用できません。<br>
          利用者の追加は、管理者でログイン後に「設定 — 利用者管理」から行ってください。</p>
        <p><a class="lp-btn lp-btn-ghost lp-btn-block" href="login.php">ログイン画面へ</a></p>
      <?php else: ?>
        <p class="lp-auth-sub">最初の管理者アカウント（フルコントロール）を作成します。</p>
        <?php if ($error !== ''): ?><p class="lp-auth-error"><?= h($error) ?></p><?php endif; ?>
        <form method="post" class="lp-auth-form">
          <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
          <label class="lp-field"><span class="lp-field-label">ログインID</span>
            <input class="lp-input" type="text" name="login_id" required autocapitalize="none"></label>
          <label class="lp-field"><span class="lp-field-label">表示名（氏名）</span>
            <input class="lp-input" type="text" name="display_name" required></label>
          <label class="lp-field"><span class="lp-field-label">パスワード（8文字以上・英数字を含む）</span>
            <input class="lp-input" type="password" name="password" required></label>
          <label class="lp-field"><span class="lp-field-label">パスワード（確認）</span>
            <input class="lp-input" type="password" name="password2" required></label>
          <button class="lp-btn lp-btn-primary lp-btn-block" type="submit">管理者を作成する</button>
        </form>
      <?php endif; ?>
    </div>
  </main>
</body>
</html>

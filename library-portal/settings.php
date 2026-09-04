<?php
declare(strict_types=1);
require_once __DIR__ . '/includes/auth.php';

require_login();
refresh_current_user();
$user = current_user();
if ($user === null) { header('Location: login.php'); exit; }
if (($user['role'] ?? '') !== 'admin') {
    http_response_code(403);
    $csrf = '';
    ?>
    <!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>設定 ｜ ライブラリポータル</title>
    <link rel="stylesheet" href="assets/library.css?v=3"></head>
    <body class="lp-body"><main class="lp-main">
      <p class="lp-empty">この画面は管理者のみ利用できます。<br><a href="index.php">ライブラリ一覧へ戻る</a></p>
    </main></body></html>
    <?php
    exit;
}
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>設定 ｜ ライブラリポータル</title>
  <link rel="icon" href="assets/welsys-logo.jpg">
  <link rel="apple-touch-icon" href="assets/icon-192.png">
  <link rel="manifest" href="manifest.webmanifest">
  <meta name="theme-color" content="#1257a8">
  <link rel="stylesheet" href="assets/library.css?v=3">
</head>
<body class="lp-body">

  <header class="lp-header">
    <div class="lp-header-inner">
      <h1 class="lp-title">
        <img class="lp-title-logo" src="assets/welsys-logo.jpg" alt="WELSYS ロゴ">
        <span class="lp-title-text">
          設定 — 利用者管理
          <small class="lp-title-sub">ライブラリポータル ／ 管理者のみ</small>
        </span>
      </h1>
      <div class="lp-header-actions">
        <a class="lp-btn lp-btn-ghost lp-btn-sm" href="index.php">← ライブラリ一覧</a>
        <button id="btnNewUser" class="lp-btn lp-btn-primary" type="button">＋ 利用者を追加</button>
      </div>
    </div>
  </header>

  <main class="lp-main">
    <nav class="lp-tabs" aria-label="設定メニュー">
      <span class="lp-tab is-active">利用者</span>
    </nav>

    <p class="lp-help">
      <strong>管理者</strong>はアイテム・更新履歴の登録と利用者管理を含むすべての操作が行えます。
      <strong>閲覧のみ</strong>は一覧と更新履歴の閲覧・ダウンロードのみが行えます。
      権限は行の中のスイッチでいつでも切り替えられます。
    </p>

    <div class="lp-listhead lp-listhead-users" aria-hidden="true">
      <span>利用者</span><span>ログインID</span><span>所属</span>
      <span>権限</span><span>状態</span><span>最終ログイン</span><span></span>
    </div>

    <div id="userList" class="lp-list"></div>
    <p id="userEmpty" class="lp-empty" hidden>利用者が登録されていません。</p>
  </main>

  <footer class="lp-footer">
    <p>ライブラリポータル ／ <?= h($user['login_id']) ?> としてログイン中（管理者）</p>
  </footer>

  <!-- 利用者 追加・編集 -->
  <div id="modalOverlay" class="lp-overlay" hidden></div>
  <div id="userModal" class="lp-modal" role="dialog" aria-modal="true" aria-labelledby="userModalTitle" hidden>
    <div class="lp-modal-head">
      <h2 class="lp-modal-title" id="userModalTitle">利用者の追加</h2>
      <button id="btnCloseUserModal" class="lp-icon-btn" type="button" aria-label="閉じる">✕</button>
    </div>
    <form id="userForm" class="lp-form">
      <input type="hidden" id="uUserId" value="">
      <div class="lp-field-row">
        <label class="lp-field">
          <span class="lp-field-label">ログインID <em>必須</em></span>
          <input id="uLoginId" class="lp-input" type="text" required
                 placeholder="hanada.t" autocapitalize="none" pattern="[A-Za-z0-9._\-]{3,64}">
        </label>
        <label class="lp-field">
          <span class="lp-field-label">表示名（氏名） <em>必須</em></span>
          <input id="uName" class="lp-input" type="text" required placeholder="花田 達也">
        </label>
      </div>
      <div class="lp-field-row">
        <label class="lp-field">
          <span class="lp-field-label">所属</span>
          <input id="uDept" class="lp-input" type="text" placeholder="情報システム室">
        </label>
        <label class="lp-field">
          <span class="lp-field-label">メールアドレス</span>
          <input id="uEmail" class="lp-input" type="email" placeholder="taro@example.co.jp">
        </label>
      </div>

      <fieldset class="lp-field lp-roleset">
        <legend class="lp-field-label">権限 <em>必須</em></legend>
        <label class="lp-roleopt">
          <input type="radio" name="uRole" value="admin">
          <span class="lp-roleopt-body">
            <span class="lp-roleopt-title">管理者（フルコントロール）</span>
            <span class="lp-roleopt-desc">アイテム・更新履歴の登録／編集、利用者の追加・権限変更まで、すべての操作が可能です。</span>
          </span>
        </label>
        <label class="lp-roleopt">
          <input type="radio" name="uRole" value="viewer" checked>
          <span class="lp-roleopt-body">
            <span class="lp-roleopt-title">閲覧のみ（一般利用者）</span>
            <span class="lp-roleopt-desc">一覧・更新履歴の閲覧とダウンロードのみ。登録・変更の操作はできません。</span>
          </span>
        </label>
      </fieldset>

      <label class="lp-field">
        <span class="lp-field-label" id="pwLabel">初期パスワード <em>必須</em></span>
        <input id="uPassword" class="lp-input" type="text" autocomplete="off"
               placeholder="8文字以上・英字と数字を含む">
        <span class="lp-field-note" id="pwNote">初回ログイン時に本人による変更を促します。空欄のまま保存すると変更しません（編集時）。</span>
      </label>

      <p class="lp-form-error" id="userError" hidden></p>
      <div class="lp-form-actions">
        <button type="button" id="btnUserCancel" class="lp-btn lp-btn-ghost">キャンセル</button>
        <button type="submit" class="lp-btn lp-btn-primary">保存する</button>
      </div>
    </form>
  </div>

  <div id="toast" class="lp-toast" hidden></div>

  <script>
    window.LP = {
      apiBase: 'api',
      csrf: <?= json_encode($csrf) ?>,
      user: { id: <?= (int)$user['user_id'] ?>, loginId: <?= json_encode($user['login_id']) ?> }
    };
  </script>
  <script src="assets/settings.js?v=1"></script>
  <script src="assets/pwa.js?v=1"></script>
</body>
</html>

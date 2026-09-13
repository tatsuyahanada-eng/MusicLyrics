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
    <link rel="stylesheet" href="assets/library.css?v=35"></head>
    <body class="lp-body"><main class="lp-main">
      <p class="lp-empty">この画面は管理者のみ利用できます。<br><a href="index.php">ライブラリ一覧へ戻る</a></p>
    </main></body></html>
    <?php
    exit;
}
$csrf     = csrf_token();
$mode     = lp_auth_mode();
$central  = $mode === 'central';
$canAcct  = can_manage_accounts();
$defRole  = lp_default_role();
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
  <link rel="stylesheet" href="assets/library.css?v=35">
</head>
<body class="lp-body">

  <header class="lp-header">
    <div class="lp-header-inner">
      <h1 class="lp-title">
        <a class="lp-title-link" href="index.php" title="TOPへ戻る">
          <img class="lp-app-icon" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
          <span class="lp-title-text">
            設定 — 利用者管理
            <small class="lp-title-sub">ライブラリポータル ／ 管理者のみ</small>
          </span>
        </a>
      </h1>
      <div class="lp-header-actions">
        <a class="lp-btn lp-btn-ghost lp-btn-sm" href="index.php">← ライブラリ一覧</a>
        <?php if ($canAcct): ?>
          <button id="btnNewUser" class="lp-btn lp-btn-primary" type="button">＋ 利用者を追加</button>
        <?php endif; ?>
        <button id="btnNewCategory" class="lp-btn lp-btn-primary" type="button" hidden>＋ カテゴリを追加</button>
      </div>
    </div>
  </header>

  <main class="lp-main">
    <nav class="lp-tabs" aria-label="設定メニュー">
      <button class="lp-tab is-active" type="button" data-tab="users">利用者</button>
      <button class="lp-tab" type="button" data-tab="categories">カテゴリ</button>
    </nav>

    <div id="panelUsers" class="lp-panel-tab">
      <p class="lp-help">
        <strong>管理者</strong>はアイテム・更新履歴の登録と利用者管理を含むすべての操作が行えます。
        <strong>編集者</strong>は閲覧・登録・更新・削除ができますが、利用者の追加・削除・権限変更（この画面）はできません。
        <strong>閲覧のみ</strong>は一覧と更新履歴の閲覧、URLへのアクセスのみが行えます。
        権限は行の中のスイッチでいつでも切り替えられます。
      </p>

      <?php if ($central): ?>
        <p class="lp-help lp-help-central">
          <strong>共通ユーザーデータベース運用中</strong>（アプリ識別子：<code><?= h(lp_app_key()) ?></code>）。
          アカウントの作成・停止・削除・パスワード再設定は<strong>共通の利用者管理</strong>で行います。
          この画面では<strong>このアプリでの権限</strong>だけを変更します。権限は共通DBにアプリ単位で保存されるため、
          他のアプリの権限には影響しません。
          <?php if ($defRole === null): ?>
            権限を付与していない利用者は、このアプリにログインできません。
          <?php else: ?>
            権限を付与していない利用者は「<?= h($defRole === 'admin' ? '管理者' : '閲覧のみ') ?>」として扱われます。
          <?php endif; ?>
        </p>
      <?php endif; ?>

      <div class="lp-listhead lp-listhead-users" aria-hidden="true">
        <span>利用者</span><span>ログインID</span><span>所属</span>
        <span>権限</span><span>状態</span><span>最終ログイン</span><span></span>
      </div>

      <div id="userList" class="lp-list"></div>
      <p id="userEmpty" class="lp-empty" hidden>利用者が登録されていません。</p>
    </div>

    <div id="panelCategories" class="lp-panel-tab" hidden>
      <p class="lp-help">
        「種別」を自由に追加・削除できます。使用中（アイテムが登録されている）のカテゴリは削除できません。
        先にそのアイテムの種別を変更するか、削除してからお試しください。
      </p>
      <div id="categoryList" class="lp-catlist"></div>
      <p id="categoryEmpty" class="lp-empty" hidden>カテゴリが登録されていません。</p>
    </div>
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
          <input type="radio" name="uRole" value="editor">
          <span class="lp-roleopt-body">
            <span class="lp-roleopt-title">編集者（閲覧・登録・更新・削除）</span>
            <span class="lp-roleopt-desc">アイテム・更新履歴の閲覧・登録・修正・削除ができます。利用者の追加・削除・権限変更はできません。</span>
          </span>
        </label>
        <label class="lp-roleopt">
          <input type="radio" name="uRole" value="viewer" checked>
          <span class="lp-roleopt-body">
            <span class="lp-roleopt-title">閲覧のみ（一般利用者）</span>
            <span class="lp-roleopt-desc">一覧・更新履歴の閲覧とURLへのアクセスのみ。登録・変更の操作はできません。</span>
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

  <!-- カテゴリ 追加・編集 -->
  <div id="categoryModal" class="lp-modal" role="dialog" aria-modal="true" aria-labelledby="categoryModalTitle" hidden>
    <div class="lp-modal-head">
      <h2 class="lp-modal-title" id="categoryModalTitle">カテゴリの追加</h2>
      <button id="btnCloseCategoryModal" class="lp-icon-btn" type="button" aria-label="閉じる">✕</button>
    </div>
    <form id="categoryForm" class="lp-form">
      <input type="hidden" id="cCode" value="">
      <div class="lp-field-row">
        <label class="lp-field">
          <span class="lp-field-label">表示名 <em>必須</em></span>
          <input id="cLabel" class="lp-input" type="text" required placeholder="外部連携">
        </label>
        <label class="lp-field" id="cCodeField">
          <span class="lp-field-label">管理IDの接頭辞 <em>必須</em></span>
          <input id="cCodeInput" class="lp-input" type="text" required placeholder="EXT"
                 pattern="[A-Za-z][A-Za-z0-9]{1,9}" autocapitalize="none">
          <span class="lp-field-hint">半角英字で始まる英数字2〜10文字。あとから変更できません（例：EXT-001）。</span>
        </label>
      </div>

      <fieldset class="lp-field">
        <legend class="lp-field-label">配色 <em>必須</em></legend>
        <div class="lp-swatchrow" id="cColorRow" role="radiogroup" aria-label="よく使う配色"></div>
        <div class="lp-huepicker">
          <span class="lp-huepreview" id="cHuePreview" aria-hidden="true"></span>
          <input id="cHue" class="lp-hueslider" type="range" min="0" max="359" value="200"
                 aria-label="好みの色合いを色相バーで選ぶ">
        </div>
        <span class="lp-field-hint">上のプリセットにない色合いは、バーをドラッグして好みの色を選べます。</span>
      </fieldset>

      <fieldset class="lp-field">
        <legend class="lp-field-label">アイコン <em>必須</em></legend>
        <div class="lp-iconrow" id="cIconRow" role="radiogroup" aria-label="アイコン"></div>
      </fieldset>

      <p class="lp-form-error" id="categoryError" hidden></p>
      <div class="lp-form-actions">
        <button type="button" id="btnCategoryCancel" class="lp-btn lp-btn-ghost">キャンセル</button>
        <button type="submit" class="lp-btn lp-btn-primary">保存する</button>
      </div>
    </form>
  </div>

  <div id="toast" class="lp-toast" hidden></div>

  <script>
    window.LP = {
      apiBase: 'api',
      csrf: <?= json_encode($csrf) ?>,
      user: { id: <?= (int)$user['user_id'] ?>, loginId: <?= json_encode($user['login_id']) ?> },
      authMode: <?= json_encode($mode) ?>,
      canManageAccounts: <?= $canAcct ? 'true' : 'false' ?>
    };
  </script>
  <script src="assets/settings.js?v=4"></script>
  <script src="assets/pwa.js?v=2"></script>
</body>
</html>

<?php
declare(strict_types=1);
require_once __DIR__ . '/includes/auth.php';

$user = require_login();
refresh_current_user();
$user = current_user();
if ($user === null) {
    header('Location: login.php');
    exit;
}
$isAdmin = ($user['role'] ?? '') === 'admin';
$csrf    = csrf_token();
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ライブラリポータル</title>
  <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=5">
  <link rel="icon" type="image/png" sizes="16x16" href="assets/favicon-16.png?v=5">
  <link rel="apple-touch-icon" href="assets/icon-192.png?v=5">
  <link rel="manifest" href="manifest.webmanifest">
  <meta name="theme-color" content="#007a33">
  <link rel="stylesheet" href="assets/library.css?v=12">
</head>
<body class="lp-body">

  <header class="lp-header">
    <div class="lp-header-inner">
      <h1 class="lp-title">
        <a class="lp-title-link" href="index.php" title="TOPへ戻る">
          <img class="lp-app-icon" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
          <span class="lp-title-text">
            ライブラリポータル
            <small class="lp-title-sub">関連会社 共有ライブラリ ／ 更新履歴管理</small>
          </span>
        </a>
      </h1>
      <div class="lp-header-actions">
        <span class="lp-count"><strong id="statItems">0</strong> 件 ／ 更新 <strong id="statHistory">0</strong> 件</span>
        <?php if ($isAdmin): ?>
          <button id="btnNewItem" class="lp-btn lp-btn-ghost lp-btn-sm" type="button">＋ アイテム</button>
          <button id="btnNewUpdate" class="lp-btn lp-btn-primary" type="button">＋ 更新を登録</button>
        <?php endif; ?>
        <div class="lp-user">
          <button id="btnUserMenu" class="lp-user-btn" type="button" aria-haspopup="true" aria-expanded="false">
            <span class="lp-user-name"><?= h($user['display_name']) ?></span>
            <span class="lp-role lp-role-<?= $isAdmin ? 'admin' : 'viewer' ?>"><?= $isAdmin ? '管理者' : '閲覧のみ' ?></span>
          </button>
          <div id="userMenu" class="lp-user-menu" hidden>
            <?php if ($isAdmin): ?>
              <a class="lp-user-menu-item" href="settings.php">⚙ 設定（利用者管理）</a>
            <?php endif; ?>
            <button class="lp-user-menu-item" type="button" id="btnChangePw">🔑 パスワード変更</button>
            <a class="lp-user-menu-item" id="lnkInstall" href="install.php">⤓ アプリをインストール</a>
            <a class="lp-user-menu-item lp-user-menu-danger" href="logout.php">↩ ログアウト</a>
          </div>
        </div>
      </div>
    </div>
  </header>

  <main class="lp-main">
    <?php if (!empty($user['must_change_pw'])): ?>
      <p class="lp-notice">初期パスワードのままです。メニューから<strong>パスワード変更</strong>を行ってください。</p>
    <?php endif; ?>

    <div class="lp-toolbar">
      <div class="lp-search">
        <span class="lp-search-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg></span>
        <input id="searchInput" class="lp-input lp-search-input" type="search"
               placeholder="名称・説明・更新内容・対象機能・対応者で検索" autocomplete="off">
      </div>
      <div class="lp-chips" id="chipRow" role="group" aria-label="種別で絞り込み"></div>
      <div class="lp-toolbar-right">
        <select id="sortSelect" class="lp-select" aria-label="並び替え">
          <option value="updated_desc">更新が新しい順</option>
          <option value="updated_asc">更新が古い順</option>
          <option value="created_desc">作成日が新しい順</option>
          <option value="name_asc">名称順</option>
        </select>
        <button id="btnExpandAll" class="lp-btn lp-btn-ghost lp-btn-sm" type="button">すべて開く</button>
        <button id="btnCollapseAll" class="lp-btn lp-btn-ghost lp-btn-sm" type="button">すべて閉じる</button>
      </div>
    </div>

    <div class="lp-listhead" aria-hidden="true">
      <span>種別</span><span>名称</span><span>最終更新</span>
      <span>最新の更新内容</span><span>作成者</span><span>URL</span><span></span>
    </div>

    <div id="list" class="lp-list"></div>
    <p id="listEmpty" class="lp-empty" hidden>条件に一致するアイテムがありません。</p>
  </main>

  <footer class="lp-footer">
    <div class="lp-footer-inner">
      <p class="lp-footer-status">ライブラリポータル ／ <?= h($user['login_id']) ?> としてログイン中<?= $isAdmin ? '（管理者）' : '（閲覧のみ）' ?></p>
      <p class="lp-footer-copyright">
        <img class="lp-footer-logo" src="assets/welsys-logo.jpg" alt="WELSYS ロゴ">
        <span>&copy; <?= date('Y') ?> ウェルシス株式会社</span>
      </p>
    </div>
  </footer>

  <!-- 更新登録 -->
  <div id="modalOverlay" class="lp-overlay" hidden></div>
  <div id="updateModal" class="lp-modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle" hidden>
    <div class="lp-modal-head">
      <h2 class="lp-modal-title" id="modalTitle">更新内容の登録</h2>
      <button id="btnCloseModal" class="lp-icon-btn" type="button" aria-label="閉じる">✕</button>
    </div>
    <form id="updateForm" class="lp-form">
      <label class="lp-field">
        <span class="lp-field-label">対象アイテム <em>必須</em></span>
        <select id="fItem" class="lp-select lp-w-full" required></select>
      </label>
      <div class="lp-field-row">
        <label class="lp-field"><span class="lp-field-label">更新日 <em>必須</em></span>
          <input id="fDate" class="lp-input" type="date" required></label>
        <label class="lp-field"><span class="lp-field-label">時間 <em>必須</em></span>
          <input id="fTime" class="lp-input" type="time" required></label>
        <label class="lp-field"><span class="lp-field-label">区分</span>
          <select id="fKind" class="lp-select">
            <option>機能追加</option><option>不具合修正</option><option>改善</option>
            <option>資料改訂</option><option>初版公開</option>
          </select></label>
      </div>
      <div class="lp-field-row">
        <label class="lp-field"><span class="lp-field-label">対応者 <em>必須</em></span>
          <input id="fAuthor" class="lp-input" type="text" value="<?= h($user['display_name']) ?>" required></label>
        <label class="lp-field"><span class="lp-field-label">版数</span>
          <input id="fVersion" class="lp-input" type="text" placeholder="v1.2.0"></label>
        <label class="lp-field"><span class="lp-field-label">管理番号</span>
          <input id="fTicket" class="lp-input" type="text" placeholder="WLS-1234"></label>
      </div>
      <label class="lp-field"><span class="lp-field-label">更新内容 <em>必須</em></span>
        <textarea id="fSummary" class="lp-input lp-textarea" rows="2"
                  placeholder="例）CSV出力に部署コード列を追加" required></textarea></label>
      <label class="lp-field"><span class="lp-field-label">対象機能 <em>必須</em></span>
        <input id="fTarget" class="lp-input" type="text" placeholder="例）CSV出力機能 / 月次集計処理" required></label>
      <label class="lp-field">
        <span class="lp-field-label">実際に直したプログラム・ファイル</span>
        <textarea id="fFiles" class="lp-input lp-textarea" rows="4"
                  placeholder="export/csvExporter.js : buildRow() に部署コードを追加&#10;db/schema/expense.sql : dept_code カラム追加"></textarea>
        <span class="lp-field-hint">1行に1件。<code>ファイルのパス</code> と <code>直した内容</code> を
          <code> : </code>（半角スペース＋コロン＋半角スペース）で区切ると、履歴に表として並びます。</span>
      </label>
      <label class="lp-field"><span class="lp-field-label">URL（変更がある場合のみ）</span>
        <input id="fUrl" class="lp-input" type="url" placeholder="https://share.example.co.jp/..."></label>
      <p class="lp-form-error" id="updateError" hidden></p>
      <div class="lp-form-actions">
        <button type="button" id="btnCancel" class="lp-btn lp-btn-ghost">キャンセル</button>
        <button type="submit" class="lp-btn lp-btn-primary">登録する</button>
      </div>
    </form>
  </div>

  <!-- アイテム新規登録 -->
  <div id="itemModal" class="lp-modal" role="dialog" aria-modal="true" aria-labelledby="itemModalTitle" hidden>
    <div class="lp-modal-head">
      <h2 class="lp-modal-title" id="itemModalTitle">アイテムの新規登録</h2>
      <button id="btnCloseItemModal" class="lp-icon-btn" type="button" aria-label="閉じる">✕</button>
    </div>
    <form id="itemForm" class="lp-form">
      <div class="lp-field-row">
        <label class="lp-field"><span class="lp-field-label">管理ID <em>必須</em></span>
          <input id="iId" class="lp-input" type="text" placeholder="APP-004" required></label>
        <label class="lp-field"><span class="lp-field-label">種別 <em>必須</em></span>
          <select id="iCategory" class="lp-select">
            <option>アプリ</option><option>プログラム</option><option>資料</option><option>マニュアル</option>
          </select></label>
        <label class="lp-field"><span class="lp-field-label">作成日 <em>必須</em></span>
          <input id="iCreated" class="lp-input" type="date" required></label>
      </div>
      <div class="lp-field-row">
        <label class="lp-field"><span class="lp-field-label">名称 <em>必須</em></span>
          <input id="iName" class="lp-input" type="text" required></label>
        <label class="lp-field"><span class="lp-field-label">作成者 <em>必須</em></span>
          <input id="iCreator" class="lp-input" type="text" required></label>
      </div>
      <label class="lp-field"><span class="lp-field-label">説明</span>
        <textarea id="iDesc" class="lp-input lp-textarea" rows="3"></textarea></label>
      <label class="lp-field"><span class="lp-field-label">URL（アプリの入口）</span>
        <input id="iUrl" class="lp-input" type="url" placeholder="https://share.example.co.jp/..."></label>
      <p class="lp-form-error" id="itemError" hidden></p>
      <div class="lp-form-actions">
        <button type="button" id="btnItemCancel" class="lp-btn lp-btn-ghost">キャンセル</button>
        <button type="submit" class="lp-btn lp-btn-primary">登録する</button>
      </div>
    </form>
  </div>

  <!-- パスワード変更 -->
  <div id="pwModal" class="lp-modal" role="dialog" aria-modal="true" aria-labelledby="pwModalTitle" hidden>
    <div class="lp-modal-head">
      <h2 class="lp-modal-title" id="pwModalTitle">パスワードの変更</h2>
      <button id="btnClosePwModal" class="lp-icon-btn" type="button" aria-label="閉じる">✕</button>
    </div>
    <form id="pwForm" class="lp-form">
      <label class="lp-field"><span class="lp-field-label">現在のパスワード <em>必須</em></span>
        <input id="pwCurrent" class="lp-input" type="password" required autocomplete="current-password"></label>
      <label class="lp-field"><span class="lp-field-label">新しいパスワード <em>必須</em></span>
        <input id="pwNext" class="lp-input" type="password" required autocomplete="new-password"
               placeholder="8文字以上・英字と数字を含む"></label>
      <label class="lp-field"><span class="lp-field-label">新しいパスワード（確認） <em>必須</em></span>
        <input id="pwConfirm" class="lp-input" type="password" required autocomplete="new-password"></label>
      <p class="lp-form-error" id="pwError" hidden></p>
      <div class="lp-form-actions">
        <button type="button" id="btnPwCancel" class="lp-btn lp-btn-ghost">キャンセル</button>
        <button type="submit" class="lp-btn lp-btn-primary">変更する</button>
      </div>
    </form>
  </div>

  <div id="toast" class="lp-toast" hidden></div>

  <script>
    window.LP = {
      apiBase: 'api',
      csrf: <?= json_encode($csrf) ?>,
      user: {
        id: <?= (int)$user['user_id'] ?>,
        loginId: <?= json_encode($user['login_id']) ?>,
        name: <?= json_encode($user['display_name']) ?>,
        role: <?= json_encode($user['role']) ?>
      },
      canEdit: <?= $isAdmin ? 'true' : 'false' ?>
    };
  </script>
  <script src="assets/library.js?v=12"></script>
  <script src="assets/pwa.js?v=2"></script>
  <script>
    // インストール導線：すぐに実行できる端末ではその場で、それ以外は案内ページへ
    (function () {
      var link = document.getElementById('lnkInstall');
      if (!link) return;
      window.LP_PWA.onChange(function () {
        link.hidden = window.LP_PWA.isInstalled();
      });
      link.addEventListener('click', function (e) {
        if (!window.LP_PWA.canPrompt()) return;   // 案内ページ（install.php）へ
        e.preventDefault();
        window.LP_PWA.install();
      });
    })();
  </script>
</body>
</html>

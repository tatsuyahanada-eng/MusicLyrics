<?php
/** 管理コンソールのトップ。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();
Auth::gc();

$userCount    = (int) Db::value('SELECT COUNT(*) FROM users');
$activeCount  = (int) Db::value("SELECT COUNT(*) FROM users WHERE status = 'active'");
$appCount     = (int) Db::value("SELECT COUNT(*) FROM apps WHERE status = 'active'");
$sessionCount = (int) Db::value('SELECT COUNT(*) FROM sso_sessions WHERE revoked_at IS NULL AND expires_at > NOW()');
$recent       = Audit::recent(10);

View::head('ダッシュボード', $admin, '');
?>
<main class="container">
  <?php View::pageTitle('User Management', '共通ユーザーデータベースとシングルサインオンの管理コンソールです。'); ?>

  <div class="grid">
    <div class="card">
      <div class="muted">登録ユーザー</div>
      <div class="stat"><?= $userCount ?></div>
      <div class="muted">うち有効 <?= $activeCount ?></div>
    </div>
    <div class="card">
      <div class="muted">連携アプリ（有効）</div>
      <div class="stat"><?= $appCount ?></div>
    </div>
    <div class="card">
      <div class="muted">現在のログインセッション</div>
      <div class="stat"><?= $sessionCount ?></div>
    </div>
  </div>

  <div class="card" style="margin-top:20px">
    <h2 class="card__title">操作</h2>
    <p>
      <a class="btn btn--accent" href="<?= h(Config::baseUrl('admin/user_edit.php')) ?>">＋ ユーザーを追加</a>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/users_import.php')) ?>">CSV一括登録</a>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/permissions.php')) ?>">権限マトリクス</a>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/app_edit.php')) ?>">＋ アプリを登録</a>
    </p>
  </div>

  <div class="card">
    <h2 class="card__title">最近の操作</h2>
    <div class="table-wrap">
      <table class="data">
        <thead><tr><th>日時</th><th>操作</th><th>操作者</th><th>対象</th></tr></thead>
        <tbody>
        <?php foreach ($recent as $log): ?>
          <tr>
            <td><?= h($log['created_at']) ?></td>
            <td><?= h($log['action']) ?></td>
            <td><?= h($log['actor_name'] ?: '—') ?></td>
            <td><?= h($log['target_name'] ?: ($log['app_name'] ?: '—')) ?></td>
          </tr>
        <?php endforeach; ?>
        <?php if ($recent === []): ?>
          <tr><td colspan="4" class="muted">記録はまだありません。</td></tr>
        <?php endif; ?>
        </tbody>
      </table>
    </div>
    <p style="margin-top:12px"><a href="<?= h(Config::baseUrl('admin/logs.php')) ?>">監査ログをすべて見る →</a></p>
  </div>
</main>
<?php View::foot();

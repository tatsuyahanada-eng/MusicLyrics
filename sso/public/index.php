<?php
/** ポータル。ログイン中のユーザーが開けるアプリを並べる。 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

$user = Auth::requireUser();
$apps = Permissions::allowedApps($user);

View::head('ポータル', $user, 'portal');
?>
<main class="container">
  <?php View::pageTitle(
      'ご利用いただけるアプリケーション',
      ($user['display_name'] ?: $user['username']) . ' さんに許可されているアプリの一覧です。'
  ); ?>

  <?php if ($apps === []): ?>
    <div class="card">
      <p>現在、閲覧が許可されているアプリケーションはありません。</p>
      <p class="muted">利用したいアプリがある場合は、管理者に閲覧許可を依頼してください。</p>
    </div>
  <?php else: ?>
    <div class="grid">
      <?php foreach ($apps as $app): ?>
        <a class="app-card" href="<?= h($app['base_url']) ?>">
          <div class="app-card__name"><?= h($app['name']) ?></div>
          <?php if (($app['description'] ?? '') !== ''): ?>
            <div class="app-card__desc"><?= h($app['description']) ?></div>
          <?php endif; ?>
          <div class="app-card__url"><?= h($app['base_url']) ?></div>
        </a>
      <?php endforeach; ?>
    </div>
  <?php endif; ?>

  <div class="card" style="margin-top:24px">
    <h2 class="card__title">アカウント</h2>
    <table class="data">
      <tr><th>ログインID</th><td><?= h($user['username']) ?></td></tr>
      <tr><th>氏名</th><td><?= h($user['display_name'] ?: '—') ?></td></tr>
      <tr><th>所属</th><td><?= h($user['department'] ?: '—') ?></td></tr>
      <tr><th>メール</th><td><?= h($user['email'] ?: '—') ?></td></tr>
    </table>
    <p style="margin-top:14px">
      <a class="btn btn--ghost btn--sm" href="<?= h(Config::baseUrl('password.php')) ?>">パスワードを変更</a>
    </p>
  </div>
</main>
<?php View::foot();

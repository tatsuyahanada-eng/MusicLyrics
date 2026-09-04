<?php
/** 連携アプリケーションの一覧。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    if (post('action') === 'delete') {
        $app = Apps::find((int) post('id'));
        if ($app !== null) {
            Apps::delete((int) $app['id'], (int) $admin['id']);
            flash('success', 'アプリ「' . $app['name'] . '」の登録を削除しました。');
        }
    }
    redirect(Config::baseUrl('admin/apps.php'));
}

$apps   = Apps::all();
$counts = Permissions::allowCountsByApp();

View::head('アプリケーション管理', $admin, 'apps');
?>
<main class="container">
  <?php View::pageTitle(
      'アプリケーション管理',
      'SSO に参加させるウェブアプリケーションを登録します。稼働中のアプリは止めずに、順次ここへ追加できます。'
  ); ?>

  <div class="card">
    <div class="row">
      <div class="spacer"></div>
      <a class="btn btn--accent" href="<?= h(Config::baseUrl('admin/app_edit.php')) ?>">＋ アプリを登録</a>
    </div>
  </div>

  <div class="card">
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>アプリ名</th><th>識別子</th><th>URL</th>
            <th>既定ポリシー</th><th>状態</th><th>許可ユーザー数</th><th></th>
          </tr>
        </thead>
        <tbody>
        <?php if ($apps === []): ?>
          <tr><td colspan="7" class="muted">まだアプリが登録されていません。</td></tr>
        <?php endif; ?>
        <?php foreach ($apps as $app): ?>
          <tr>
            <td class="wrap">
              <a href="<?= h(url_with(Config::baseUrl('admin/app_edit.php'), ['id' => $app['id']])) ?>">
                <?= h($app['name']) ?>
              </a>
              <?php if (($app['description'] ?? '') !== ''): ?>
                <div class="muted"><?= h($app['description']) ?></div>
              <?php endif; ?>
            </td>
            <td><code><?= h($app['app_key']) ?></code></td>
            <td class="wrap"><a href="<?= h($app['base_url']) ?>"><?= h($app['base_url']) ?></a></td>
            <td><?= $app['default_policy'] === 'allow'
                     ? '<span class="badge badge--allow">全員に許可</span>'
                     : '<span class="badge badge--deny">許可した人のみ</span>' ?></td>
            <td><?= $app['status'] === 'active'
                     ? '<span class="badge badge--allow">有効</span>'
                     : '<span class="badge badge--warn">無効</span>' ?></td>
            <td><?= (int) ($counts[(int) $app['id']] ?? 0) ?></td>
            <td class="right">
              <form method="post" style="display:inline"
                    onsubmit="return confirm('アプリ「<?= h($app['name']) ?>」の登録を削除します。\nこのアプリの閲覧許可設定も全て消えます。よろしいですか？');">
                <?= Csrf::field() ?>
                <input type="hidden" name="action" value="delete">
                <input type="hidden" name="id" value="<?= (int) $app['id'] ?>">
                <button class="btn btn--danger btn--sm" type="submit">削除</button>
              </form>
            </td>
          </tr>
        <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </div>
</main>
<?php View::foot();

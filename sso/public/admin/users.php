<?php
/** ユーザー一覧：検索・追加・削除の入口。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $action = post('action');

    if ($action === 'delete') {
        $id = (int) post('id');
        if ($id === (int) $admin['id']) {
            flash('error', '自分自身を削除することはできません。');
        } else {
            $target = Users::find($id);
            if ($target === null) {
                flash('error', '対象のユーザーが見つかりません。');
            } elseif (!empty($target['is_admin']) && !Users::otherActiveAdminExists($id)) {
                flash('error', '最後の管理者は削除できません。先に別の管理者を作成してください。');
            } else {
                Users::delete($id, (int) $admin['id']);
                flash('success', 'ユーザー「' . $target['username'] . '」を削除しました。');
            }
        }
    } elseif ($action === 'unlock') {
        Users::unlock((int) post('id'), (int) $admin['id']);
        flash('success', 'ロックを解除しました。');
    }
    redirect(url_with(Config::baseUrl('admin/users.php'), array_filter([
        'q' => post('q'), 'status' => post('status'), 'page' => post('page'),
    ], static fn ($v) => $v !== '')));
}

$keyword = query('q');
$status  = query('status');
$page    = max(1, (int) query('page', '1'));
$result  = Users::search($keyword, $status, $page);

View::head('ユーザー管理', $admin, 'users');
?>
<main class="container">
  <?php View::pageTitle('ユーザー管理', '全アプリ共通のユーザーを、ここで一括して登録・管理します。'); ?>

  <div class="card">
    <form method="get" class="row">
      <div class="field" style="flex:2 1 260px;margin:0">
        <label class="field__label" for="q">検索</label>
        <input id="q" type="search" name="q" value="<?= h($keyword) ?>"
               placeholder="ログインID / 氏名 / メール / 所属">
      </div>
      <div class="field" style="flex:0 1 160px;margin:0">
        <label class="field__label" for="status">状態</label>
        <select id="status" name="status">
          <option value="">すべて</option>
          <option value="active"    <?= $status === 'active' ? 'selected' : '' ?>>有効</option>
          <option value="suspended" <?= $status === 'suspended' ? 'selected' : '' ?>>停止中</option>
        </select>
      </div>
      <button class="btn" type="submit">絞り込む</button>
      <div class="spacer"></div>
      <a class="btn btn--accent" href="<?= h(Config::baseUrl('admin/user_edit.php')) ?>">＋ ユーザーを追加</a>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/users_import.php')) ?>">CSV一括登録</a>
    </form>
  </div>

  <div class="card">
    <p class="card__note"><?= (int) $result['total'] ?> 件中 <?= count($result['rows']) ?> 件を表示</p>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>ログインID</th>
            <th>氏名</th>
            <th>所属</th>
            <th>メール</th>
            <th>状態</th>
            <th>許可アプリ</th>
            <th>最終ログイン</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
        <?php if ($result['rows'] === []): ?>
          <tr><td colspan="8" class="muted">該当するユーザーはいません。</td></tr>
        <?php endif; ?>
        <?php foreach ($result['rows'] as $u): ?>
          <tr>
            <td>
              <a href="<?= h(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $u['id']])) ?>">
                <?= h($u['username']) ?>
              </a>
              <?php if (!empty($u['is_admin'])): ?>
                <span class="badge badge--admin">管理者</span>
              <?php endif; ?>
            </td>
            <td class="wrap"><?= h($u['display_name'] ?: '—') ?></td>
            <td class="wrap"><?= h($u['department'] ?: '—') ?></td>
            <td class="wrap"><?= h($u['email'] ?: '—') ?></td>
            <td>
              <?php if ($u['status'] === 'active'): ?>
                <span class="badge badge--allow">有効</span>
              <?php else: ?>
                <span class="badge badge--deny">停止中</span>
              <?php endif; ?>
              <?php if (Users::isLocked($u)): ?>
                <span class="badge badge--warn">ロック中</span>
              <?php endif; ?>
            </td>
            <td><?= (int) $u['allow_count'] ?></td>
            <td><?= h($u['last_login_at'] ?: '—') ?></td>
            <td class="right">
              <?php if (Users::isLocked($u)): ?>
                <form method="post" style="display:inline">
                  <?= Csrf::field() ?>
                  <input type="hidden" name="action" value="unlock">
                  <input type="hidden" name="id" value="<?= (int) $u['id'] ?>">
                  <input type="hidden" name="q" value="<?= h($keyword) ?>">
                  <input type="hidden" name="status" value="<?= h($status) ?>">
                  <button class="btn btn--ghost btn--sm" type="submit">ロック解除</button>
                </form>
              <?php endif; ?>
              <form method="post" style="display:inline"
                    onsubmit="return confirm('ユーザー「<?= h($u['username']) ?>」を削除します。よろしいですか？');">
                <?= Csrf::field() ?>
                <input type="hidden" name="action" value="delete">
                <input type="hidden" name="id" value="<?= (int) $u['id'] ?>">
                <input type="hidden" name="q" value="<?= h($keyword) ?>">
                <input type="hidden" name="status" value="<?= h($status) ?>">
                <button class="btn btn--danger btn--sm" type="submit">削除</button>
              </form>
            </td>
          </tr>
        <?php endforeach; ?>
        </tbody>
      </table>
    </div>

    <?php if ((int) $result['pages'] > 1): ?>
      <p style="margin-top:14px">
        <?php for ($i = 1; $i <= (int) $result['pages']; $i++): ?>
          <?php if ($i === (int) $result['page']): ?>
            <strong style="padding:0 6px"><?= $i ?></strong>
          <?php else: ?>
            <a style="padding:0 6px"
               href="<?= h(url_with(Config::baseUrl('admin/users.php'),
                     ['q' => $keyword, 'status' => $status, 'page' => $i])) ?>"><?= $i ?></a>
          <?php endif; ?>
        <?php endfor; ?>
      </p>
    <?php endif; ?>
  </div>
</main>
<?php View::foot();

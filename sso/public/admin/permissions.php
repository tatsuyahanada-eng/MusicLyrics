<?php
/**
 * 権限マトリクス：ユーザー × アプリ をこの1画面でまとめて設定する。
 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();
$apps  = Apps::all();

$keyword = query('q');
$status  = query('status');
$page    = max(1, (int) query('page', '1'));
$perPage = 25;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $action  = post('action');
    $keyword = post('q');
    $status  = post('status');
    $page    = max(1, (int) post('page', '1'));

    // 表示中のユーザー全員に、あるアプリの設定を一括適用する
    if ($action === 'bulk') {
        $appId  = (int) post('bulk_app');
        $effect = post('bulk_effect');
        $target = post('bulk_target', 'page');   // page = 表示中のページ / all = 検索結果すべて

        $rows = $target === 'all'
            ? Users::search($keyword, $status, 1, 10000)['rows']
            : Users::search($keyword, $status, $page, $perPage)['rows'];

        $userIds = array_map(static fn (array $u): int => (int) $u['id'], $rows);
        if ($appId > 0 && $userIds !== []) {
            $count = Permissions::bulkSet($userIds, $appId, $effect, (int) $admin['id']);
            $label = $effect === '' ? '既定に戻しました' : ($effect === 'allow' ? '許可しました' : '拒否しました');
            flash('success', "{$count} 件のユーザーについて{$label}。");
        } else {
            flash('error', '対象のアプリまたはユーザーが選択されていません。');
        }
    }

    // マトリクスの変更をまとめて保存
    if ($action === 'save') {
        $matrix  = $_POST['perm'] ?? [];
        $changed = 0;
        if (is_array($matrix)) {
            foreach ($matrix as $userId => $effects) {
                if (!is_array($effects)) {
                    continue;
                }
                $userId  = (int) $userId;
                $current = Permissions::explicitMapForUser($userId);
                $diff    = [];
                foreach ($effects as $appId => $effect) {
                    $appId  = (int) $appId;
                    $effect = (string) $effect;
                    if (($current[$appId] ?? '') !== $effect) {
                        $diff[$appId] = $effect;
                        $changed++;
                    }
                }
                if ($diff !== []) {
                    Permissions::replaceForUser($userId, $diff, (int) $admin['id']);
                }
            }
        }
        flash($changed > 0 ? 'success' : 'info',
              $changed > 0 ? "{$changed} 件の設定を保存しました。" : '変更はありませんでした。');
    }

    redirect(url_with(Config::baseUrl('admin/permissions.php'), array_filter([
        'q' => $keyword, 'status' => $status, 'page' => (string) $page,
    ], static fn ($v) => $v !== '' && $v !== '1')));
}

$result   = Users::search($keyword, $status, $page, $perPage);
$userIds  = array_map(static fn (array $u): int => (int) $u['id'], $result['rows']);
$explicit = Permissions::explicitMatrix($userIds);

View::head('権限マトリクス', $admin, 'permissions');
?>
<main class="container">
  <?php View::pageTitle(
      '権限マトリクス',
      'どのユーザーがどのアプリを閲覧できるかを、一覧で一括設定します。'
  ); ?>

  <div class="card">
    <form method="get" class="row">
      <div class="field" style="flex:2 1 240px;margin:0">
        <label class="field__label" for="q">ユーザー検索</label>
        <input id="q" type="search" name="q" value="<?= h($keyword) ?>" placeholder="ログインID / 氏名 / 所属">
      </div>
      <div class="field" style="flex:0 1 150px;margin:0">
        <label class="field__label" for="status">状態</label>
        <select id="status" name="status">
          <option value="">すべて</option>
          <option value="active"    <?= $status === 'active' ? 'selected' : '' ?>>有効</option>
          <option value="suspended" <?= $status === 'suspended' ? 'selected' : '' ?>>停止中</option>
        </select>
      </div>
      <button class="btn" type="submit">絞り込む</button>
    </form>
  </div>

  <?php if ($apps === []): ?>
    <div class="card">
      <p>アプリがまだ登録されていません。</p>
      <p><a class="btn" href="<?= h(Config::baseUrl('admin/app_edit.php')) ?>">アプリを登録する</a></p>
    </div>
    <?php View::foot(); exit; ?>
  <?php endif; ?>

  <div class="card">
    <h2 class="card__title">一括設定</h2>
    <form method="post" class="row">
      <?= Csrf::field() ?>
      <input type="hidden" name="action" value="bulk">
      <input type="hidden" name="q" value="<?= h($keyword) ?>">
      <input type="hidden" name="status" value="<?= h($status) ?>">
      <input type="hidden" name="page" value="<?= (int) $result['page'] ?>">
      <div class="field" style="flex:1 1 220px;margin:0">
        <label class="field__label" for="bulk_app">アプリ</label>
        <select id="bulk_app" name="bulk_app">
          <?php foreach ($apps as $app): ?>
            <option value="<?= (int) $app['id'] ?>"><?= h($app['name']) ?></option>
          <?php endforeach; ?>
        </select>
      </div>
      <div class="field" style="flex:0 1 160px;margin:0">
        <label class="field__label" for="bulk_effect">設定</label>
        <select id="bulk_effect" name="bulk_effect">
          <option value="allow">許可</option>
          <option value="deny">拒否</option>
          <option value="">既定に戻す</option>
        </select>
      </div>
      <div class="field" style="flex:0 1 220px;margin:0">
        <label class="field__label" for="bulk_target">対象</label>
        <select id="bulk_target" name="bulk_target">
          <option value="page">このページの <?= count($result['rows']) ?> 人</option>
          <option value="all">検索結果すべて（<?= (int) $result['total'] ?> 人）</option>
        </select>
      </div>
      <button class="btn" type="submit"
              onclick="return confirm('選択した対象にまとめて適用します。よろしいですか？');">適用</button>
    </form>
  </div>

  <form method="post">
    <?= Csrf::field() ?>
    <input type="hidden" name="action" value="save">
    <input type="hidden" name="q" value="<?= h($keyword) ?>">
    <input type="hidden" name="status" value="<?= h($status) ?>">
    <input type="hidden" name="page" value="<?= (int) $result['page'] ?>">

    <div class="card">
      <p class="card__note">
        セルの「既定」は各アプリの既定ポリシーに従います。変更後は右下の「保存」を押してください。
      </p>
      <div class="table-wrap">
        <table class="data matrix">
          <thead>
            <tr>
              <th class="matrix__user">ユーザー</th>
              <?php foreach ($apps as $app): ?>
                <th class="matrix__app" title="<?= h($app['app_key']) ?>"><?= h($app['name']) ?></th>
              <?php endforeach; ?>
            </tr>
          </thead>
          <tbody>
          <?php if ($result['rows'] === []): ?>
            <tr><td colspan="<?= count($apps) + 1 ?>" class="muted">該当するユーザーはいません。</td></tr>
          <?php endif; ?>
          <?php foreach ($result['rows'] as $u): ?>
            <tr>
              <td class="matrix__user wrap">
                <a href="<?= h(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $u['id']])) ?>">
                  <?= h($u['username']) ?>
                </a>
                <?php if ($u['status'] !== 'active'): ?>
                  <span class="badge badge--deny">停止中</span>
                <?php endif; ?>
                <div class="muted"><?= h($u['display_name'] ?: '') ?></div>
              </td>
              <?php foreach ($apps as $app): ?>
                <?php $effect = $explicit[(int) $u['id']][(int) $app['id']] ?? ''; ?>
                <td class="matrix__cell">
                  <select name="perm[<?= (int) $u['id'] ?>][<?= (int) $app['id'] ?>]">
                    <option value=""      <?= $effect === ''      ? 'selected' : '' ?>>既定</option>
                    <option value="allow" <?= $effect === 'allow' ? 'selected' : '' ?>>許可</option>
                    <option value="deny"  <?= $effect === 'deny'  ? 'selected' : '' ?>>拒否</option>
                  </select>
                </td>
              <?php endforeach; ?>
            </tr>
          <?php endforeach; ?>
          </tbody>
        </table>
      </div>

      <div class="row" style="margin-top:16px">
        <?php if ((int) $result['pages'] > 1): ?>
          <div>
            <?php for ($i = 1; $i <= (int) $result['pages']; $i++): ?>
              <?php if ($i === (int) $result['page']): ?>
                <strong style="padding:0 6px"><?= $i ?></strong>
              <?php else: ?>
                <a style="padding:0 6px"
                   href="<?= h(url_with(Config::baseUrl('admin/permissions.php'),
                         ['q' => $keyword, 'status' => $status, 'page' => $i])) ?>"><?= $i ?></a>
              <?php endif; ?>
            <?php endfor; ?>
          </div>
        <?php endif; ?>
        <div class="spacer"></div>
        <button class="btn" type="submit">このページの設定を保存</button>
      </div>
    </div>
  </form>

  <div class="card">
    <h2 class="card__title">アプリごとの既定ポリシー</h2>
    <div class="table-wrap">
      <table class="data">
        <thead><tr><th>アプリ</th><th>既定</th><th>状態</th></tr></thead>
        <tbody>
        <?php foreach ($apps as $app): ?>
          <tr>
            <td class="wrap">
              <a href="<?= h(url_with(Config::baseUrl('admin/app_edit.php'), ['id' => $app['id']])) ?>">
                <?= h($app['name']) ?>
              </a>
            </td>
            <td><?= $app['default_policy'] === 'allow'
                     ? '<span class="badge badge--allow">全員に許可</span>'
                     : '<span class="badge badge--deny">許可した人のみ</span>' ?></td>
            <td><?= $app['status'] === 'active'
                     ? '<span class="badge badge--allow">有効</span>'
                     : '<span class="badge badge--warn">無効</span>' ?></td>
          </tr>
        <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </div>
</main>
<?php View::foot();

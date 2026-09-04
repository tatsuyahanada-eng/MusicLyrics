<?php
/** ユーザーの新規追加・編集・権限設定・パスワード再設定。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin  = Auth::requireAdmin();
$id     = (int) query('id', '0');
$isNew  = $id === 0;
$user   = $isNew ? null : Users::find($id);

if (!$isNew && $user === null) {
    flash('error', '指定されたユーザーは存在しません。');
    redirect(Config::baseUrl('admin/users.php'));
}

$apps   = Apps::all();
$errors = [];
$form   = [
    'username'             => $user['username']     ?? '',
    'email'                => $user['email']        ?? '',
    'display_name'         => $user['display_name'] ?? '',
    'department'           => $user['department']   ?? '',
    'status'               => $user['status']       ?? 'active',
    'is_admin'             => (int) ($user['is_admin'] ?? 0),
    'must_change_password' => (int) ($user['must_change_password'] ?? ($isNew ? 1 : 0)),
];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $action = post('action');

    // ── 基本情報の保存 ─────────────────────────────────────
    if ($action === 'save') {
        $form = [
            'username'             => post('username'),
            'email'                => post('email'),
            'display_name'         => post('display_name'),
            'department'           => post('department'),
            'status'               => post('status', 'active'),
            'is_admin'             => isset($_POST['is_admin']) ? 1 : 0,
            'must_change_password' => isset($_POST['must_change_password']) ? 1 : 0,
        ];
        $input  = $form + [
            'password'         => (string) ($_POST['password'] ?? ''),
            'password_confirm' => (string) ($_POST['password_confirm'] ?? ''),
        ];
        $errors = Users::validate($input, $isNew ? null : $id, $isNew);

        // 自分自身を締め出さないための歯止め
        if (!$isNew && $id === (int) $admin['id']) {
            if ($form['is_admin'] === 0 && !Users::otherActiveAdminExists($id)) {
                $errors['is_admin'] = '最後の管理者から管理者権限を外すことはできません。';
            }
            if ($form['status'] === 'suspended') {
                $errors['status'] = '自分自身を停止することはできません。';
            }
        }

        if ($errors === []) {
            if ($isNew) {
                $newId = Users::create($input, (int) $admin['id']);
                // 追加と同時に閲覧許可も設定できるようにする
                $effects = [];
                foreach ($apps as $app) {
                    $effects[(int) $app['id']] = (string) ($_POST['perm'][(int) $app['id']] ?? '');
                }
                Permissions::replaceForUser($newId, $effects, (int) $admin['id']);
                flash('success', 'ユーザー「' . $input['username'] . '」を追加しました。');
                redirect(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $newId]));
            }
            Users::update($id, $input, (int) $admin['id']);
            flash('success', '基本情報を更新しました。');
            redirect(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $id]));
        }
    }

    // ── 閲覧許可の保存 ─────────────────────────────────────
    if ($action === 'permissions' && !$isNew) {
        $effects = [];
        foreach ($apps as $app) {
            $appId = (int) $app['id'];
            $effects[$appId] = (string) ($_POST['perm'][$appId] ?? '');
            Apps::linkExternalUser($appId, $id, trim((string) ($_POST['ext'][$appId] ?? '')), (int) $admin['id']);
        }
        Permissions::replaceForUser($id, $effects, (int) $admin['id']);
        flash('success', '閲覧許可を更新しました。');
        redirect(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $id]));
    }

    // ── パスワード再設定 ───────────────────────────────────
    if ($action === 'password' && !$isNew) {
        $new = (string) ($_POST['password'] ?? '');
        $policyError = Users::checkPasswordPolicy($new);
        if ($policyError !== null) {
            $errors['reset_password'] = $policyError;
        } elseif ($new !== (string) ($_POST['password_confirm'] ?? '')) {
            $errors['reset_password'] = '確認用パスワードが一致しません。';
        } else {
            Users::setPassword($id, $new, (int) $admin['id'], isset($_POST['must_change_password']));
            flash('success', 'パスワードを再設定しました。このユーザーは全端末からログアウトされます。');
            redirect(url_with(Config::baseUrl('admin/user_edit.php'), ['id' => $id]));
        }
    }
}

$permMap = $isNew ? [] : Permissions::explicitMapForUser($id);
$extMap  = $isNew ? [] : Apps::externalIdsFor($id);

View::head($isNew ? 'ユーザーの追加' : 'ユーザーの編集', $admin, 'users');
?>
<main class="container container--narrow">
  <?php View::pageTitle(
      $isNew ? 'ユーザーの追加' : 'ユーザーの編集：' . (string) $user['username'],
      $isNew ? '登録したIDとパスワードで、許可した全てのアプリにログインできるようになります。' : ''
  ); ?>

  <form method="post">
    <?= Csrf::field() ?>
    <input type="hidden" name="action" value="save">

    <div class="card">
      <h2 class="card__title">基本情報</h2>

      <div class="field<?= isset($errors['username']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="username">ログインID <span class="muted">（必須）</span></label>
        <input id="username" name="username" type="text" value="<?= h($form['username']) ?>" required>
        <div class="field__hint">半角英数字と . _ - @ が使えます。全アプリ共通のIDになります。</div>
        <?php if (isset($errors['username'])): ?><div class="field__error"><?= h($errors['username']) ?></div><?php endif; ?>
      </div>

      <div class="field">
        <label class="field__label" for="display_name">氏名</label>
        <input id="display_name" name="display_name" type="text" value="<?= h($form['display_name']) ?>">
      </div>

      <div class="field">
        <label class="field__label" for="department">所属</label>
        <input id="department" name="department" type="text" value="<?= h($form['department']) ?>">
      </div>

      <div class="field<?= isset($errors['email']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="email">メールアドレス</label>
        <input id="email" name="email" type="email" value="<?= h((string) $form['email']) ?>">
        <?php if (isset($errors['email'])): ?><div class="field__error"><?= h($errors['email']) ?></div><?php endif; ?>
      </div>

      <div class="field<?= isset($errors['status']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="status">状態</label>
        <select id="status" name="status">
          <option value="active"    <?= $form['status'] === 'active' ? 'selected' : '' ?>>有効</option>
          <option value="suspended" <?= $form['status'] === 'suspended' ? 'selected' : '' ?>>停止中（全アプリのログイン不可）</option>
        </select>
        <?php if (isset($errors['status'])): ?><div class="field__error"><?= h($errors['status']) ?></div><?php endif; ?>
      </div>

      <div class="checkbox">
        <input id="is_admin" name="is_admin" type="checkbox" <?= $form['is_admin'] ? 'checked' : '' ?>>
        <label for="is_admin">この画面（User Management）を利用できる管理者にする</label>
      </div>
      <?php if (isset($errors['is_admin'])): ?><div class="field__error"><?= h($errors['is_admin']) ?></div><?php endif; ?>

      <div class="checkbox">
        <input id="must_change_password" name="must_change_password" type="checkbox"
               <?= $form['must_change_password'] ? 'checked' : '' ?>>
        <label for="must_change_password">次回ログイン時にパスワードの変更を求める</label>
      </div>
    </div>

    <?php if ($isNew): ?>
      <div class="card">
        <h2 class="card__title">初期パスワード</h2>
        <div class="field<?= isset($errors['password']) ? ' field--invalid' : '' ?>">
          <label class="field__label" for="password">パスワード <span class="muted">（必須）</span></label>
          <input id="password" name="password" type="password" autocomplete="new-password" required>
          <div class="field__hint">
            <?= (int) Config::get('password.min_length', 10) ?>文字以上、英字と数字を両方含めてください。
          </div>
          <?php if (isset($errors['password'])): ?><div class="field__error"><?= h($errors['password']) ?></div><?php endif; ?>
        </div>
        <div class="field<?= isset($errors['password_confirm']) ? ' field--invalid' : '' ?>">
          <label class="field__label" for="password_confirm">パスワード（確認）</label>
          <input id="password_confirm" name="password_confirm" type="password" autocomplete="new-password" required>
          <?php if (isset($errors['password_confirm'])): ?>
            <div class="field__error"><?= h($errors['password_confirm']) ?></div>
          <?php endif; ?>
        </div>
      </div>

      <div class="card">
        <h2 class="card__title">アプリの閲覧許可</h2>
        <p class="card__note">追加と同時に設定できます。あとから変更もできます。</p>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>アプリケーション</th><th>閲覧</th></tr></thead>
            <tbody>
            <?php foreach ($apps as $app): ?>
              <tr>
                <td class="wrap"><?= h($app['name']) ?> <span class="muted"><?= h($app['app_key']) ?></span></td>
                <td>
                  <select name="perm[<?= (int) $app['id'] ?>]">
                    <option value="">既定（<?= $app['default_policy'] === 'allow' ? '許可' : '拒否' ?>）</option>
                    <option value="allow">許可</option>
                    <option value="deny">拒否</option>
                  </select>
                </td>
              </tr>
            <?php endforeach; ?>
            <?php if ($apps === []): ?>
              <tr><td colspan="2" class="muted">アプリがまだ登録されていません。</td></tr>
            <?php endif; ?>
            </tbody>
          </table>
        </div>
      </div>
    <?php endif; ?>

    <p>
      <button class="btn" type="submit"><?= $isNew ? '登録する' : '基本情報を保存' ?></button>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/users.php')) ?>">一覧へ戻る</a>
    </p>
  </form>

  <?php if (!$isNew): ?>
    <form method="post">
      <?= Csrf::field() ?>
      <input type="hidden" name="action" value="permissions">
      <div class="card">
        <h2 class="card__title">アプリの閲覧許可</h2>
        <p class="card__note">
          「既定」は各アプリの既定ポリシーに従います。「拒否」は既定が許可でも個別に閉じます。<br>
          「アプリ内のユーザーID」は、既存アプリが自前で持っているIDとの対応付けです（移行用・任意）。
        </p>
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr><th>アプリケーション</th><th>閲覧</th><th>現在の判定</th><th class="wrap">アプリ内のユーザーID</th></tr>
            </thead>
            <tbody>
            <?php foreach ($apps as $app): ?>
              <?php
                $appId    = (int) $app['id'];
                $explicit = $permMap[$appId] ?? '';
                $effective = Permissions::effectiveEffect($user, $app);
              ?>
              <tr>
                <td class="wrap">
                  <?= h($app['name']) ?>
                  <span class="muted"><?= h($app['app_key']) ?></span>
                  <?php if ($app['status'] !== 'active'): ?>
                    <span class="badge badge--warn">無効</span>
                  <?php endif; ?>
                </td>
                <td>
                  <select name="perm[<?= $appId ?>]">
                    <option value=""      <?= $explicit === ''      ? 'selected' : '' ?>>既定（<?= $app['default_policy'] === 'allow' ? '許可' : '拒否' ?>）</option>
                    <option value="allow" <?= $explicit === 'allow' ? 'selected' : '' ?>>許可</option>
                    <option value="deny"  <?= $explicit === 'deny'  ? 'selected' : '' ?>>拒否</option>
                  </select>
                </td>
                <td><?= View::effectBadge($effective) ?></td>
                <td><input type="text" name="ext[<?= $appId ?>]" value="<?= h($extMap[$appId] ?? '') ?>"
                           placeholder="（任意）"></td>
              </tr>
            <?php endforeach; ?>
            <?php if ($apps === []): ?>
              <tr><td colspan="4" class="muted">アプリがまだ登録されていません。</td></tr>
            <?php endif; ?>
            </tbody>
          </table>
        </div>
        <p style="margin-top:14px"><button class="btn" type="submit">閲覧許可を保存</button></p>
      </div>
    </form>

    <form method="post">
      <?= Csrf::field() ?>
      <input type="hidden" name="action" value="password">
      <div class="card">
        <h2 class="card__title">パスワードの再設定</h2>
        <p class="card__note">再設定すると、このユーザーの全端末のログインが解除されます。</p>
        <?php if (isset($errors['reset_password'])): ?>
          <p class="notice notice--warn"><?= h($errors['reset_password']) ?></p>
        <?php endif; ?>
        <div class="field">
          <label class="field__label" for="reset_pw">新しいパスワード</label>
          <input id="reset_pw" name="password" type="password" autocomplete="new-password" required>
        </div>
        <div class="field">
          <label class="field__label" for="reset_pw2">新しいパスワード（確認）</label>
          <input id="reset_pw2" name="password_confirm" type="password" autocomplete="new-password" required>
        </div>
        <div class="checkbox">
          <input id="reset_must" name="must_change_password" type="checkbox" checked>
          <label for="reset_must">次回ログイン時に本人へ変更を求める</label>
        </div>
        <button class="btn" type="submit">パスワードを再設定</button>
      </div>
    </form>

    <div class="card">
      <h2 class="card__title">このユーザーを削除</h2>
      <p class="card__note">
        削除すると、閲覧許可・ログインセッションも併せて削除されます。元に戻せません。<br>
        一時的に止めたいだけであれば、状態を「停止中」にしてください。
      </p>
      <form method="post" action="<?= h(Config::baseUrl('admin/users.php')) ?>"
            onsubmit="return confirm('ユーザー「<?= h((string) $user['username']) ?>」を削除します。よろしいですか？');">
        <?= Csrf::field() ?>
        <input type="hidden" name="action" value="delete">
        <input type="hidden" name="id" value="<?= (int) $id ?>">
        <button class="btn btn--danger" type="submit">削除する</button>
      </form>
    </div>
  <?php endif; ?>
</main>
<?php View::foot();

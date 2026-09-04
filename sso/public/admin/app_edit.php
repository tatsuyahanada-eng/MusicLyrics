<?php
/** アプリの登録・編集。組み込み用の設定スニペットもここで表示する。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();
$id    = (int) query('id', '0');
$isNew = $id === 0;
$app   = $isNew ? null : Apps::find($id);

if (!$isNew && $app === null) {
    flash('error', '指定されたアプリは存在しません。');
    redirect(Config::baseUrl('admin/apps.php'));
}

$errors = [];
$form = [
    'app_key'        => $app['app_key']        ?? '',
    'name'           => $app['name']           ?? '',
    'description'    => $app['description']    ?? '',
    'base_url'       => $app['base_url']       ?? '',
    'default_policy' => $app['default_policy'] ?? 'deny',
    'status'         => $app['status']         ?? 'active',
    'sort_order'     => (int) ($app['sort_order'] ?? 100),
];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $action = post('action');

    if ($action === 'rotate' && !$isNew) {
        $secret = Apps::regenerateSecret($id, (int) $admin['id']);
        flash('warn', '共有秘密鍵を再生成しました。アプリ側の設定ファイルも必ず更新してください。');
        redirect(url_with(Config::baseUrl('admin/app_edit.php'), ['id' => $id]));
    }

    $form = [
        'app_key'        => post('app_key'),
        'name'           => post('name'),
        'description'    => post('description'),
        'base_url'       => post('base_url'),
        'default_policy' => post('default_policy', 'deny'),
        'status'         => post('status', 'active'),
        'sort_order'     => (int) post('sort_order', '100'),
    ];
    $errors = Apps::validate($form, $isNew ? null : $id);

    if ($errors === []) {
        if ($isNew) {
            $newId = Apps::create($form, (int) $admin['id']);
            flash('success', 'アプリを登録しました。下の設定をアプリ側にコピーしてください。');
            redirect(url_with(Config::baseUrl('admin/app_edit.php'), ['id' => $newId]));
        }
        Apps::update($id, $form, (int) $admin['id']);
        flash('success', 'アプリ情報を更新しました。');
        redirect(url_with(Config::baseUrl('admin/app_edit.php'), ['id' => $id]));
    }
}

View::head($isNew ? 'アプリの登録' : 'アプリの編集', $admin, 'apps');
?>
<main class="container container--narrow">
  <?php View::pageTitle($isNew ? 'アプリの登録' : 'アプリの編集：' . (string) $app['name']); ?>

  <form method="post">
    <?= Csrf::field() ?>
    <div class="card">
      <h2 class="card__title">基本情報</h2>

      <div class="field<?= isset($errors['name']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="name">アプリ名 <span class="muted">（必須）</span></label>
        <input id="name" name="name" type="text" value="<?= h($form['name']) ?>" required>
        <?php if (isset($errors['name'])): ?><div class="field__error"><?= h($errors['name']) ?></div><?php endif; ?>
      </div>

      <div class="field<?= isset($errors['app_key']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="app_key">アプリ識別子 <span class="muted">（必須）</span></label>
        <input id="app_key" name="app_key" type="text" value="<?= h($form['app_key']) ?>"
               placeholder="lyrics" required>
        <div class="field__hint">半角英小文字・数字・- _ 。アプリ側の設定ファイルにも同じ値を書きます。</div>
        <?php if (isset($errors['app_key'])): ?><div class="field__error"><?= h($errors['app_key']) ?></div><?php endif; ?>
      </div>

      <div class="field">
        <label class="field__label" for="description">説明</label>
        <input id="description" name="description" type="text" value="<?= h($form['description']) ?>">
      </div>

      <div class="field<?= isset($errors['base_url']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="base_url">アプリのURL <span class="muted">（必須）</span></label>
        <input id="base_url" name="base_url" type="text" value="<?= h($form['base_url']) ?>"
               placeholder="https://lyrics.example.com" required>
        <div class="field__hint">
          ログイン後の戻り先は、このURL配下だけを許可します（不正なリダイレクト防止）。
        </div>
        <?php if (isset($errors['base_url'])): ?><div class="field__error"><?= h($errors['base_url']) ?></div><?php endif; ?>
      </div>

      <div class="field">
        <label class="field__label" for="default_policy">既定ポリシー</label>
        <select id="default_policy" name="default_policy">
          <option value="deny"  <?= $form['default_policy'] === 'deny'  ? 'selected' : '' ?>>
            許可した人のみ閲覧できる（推奨）
          </option>
          <option value="allow" <?= $form['default_policy'] === 'allow' ? 'selected' : '' ?>>
            個別に拒否した人以外は全員閲覧できる
          </option>
        </select>
        <div class="field__hint">個別設定が無いユーザーの扱いです。全社共通の掲示板などは「全員」が便利です。</div>
      </div>

      <div class="field">
        <label class="field__label" for="status">状態</label>
        <select id="status" name="status">
          <option value="active"   <?= $form['status'] === 'active'   ? 'selected' : '' ?>>有効</option>
          <option value="disabled" <?= $form['status'] === 'disabled' ? 'selected' : '' ?>>無効（誰もログインできない）</option>
        </select>
      </div>

      <div class="field">
        <label class="field__label" for="sort_order">表示順</label>
        <input id="sort_order" name="sort_order" type="number" value="<?= (int) $form['sort_order'] ?>">
      </div>

      <button class="btn" type="submit"><?= $isNew ? '登録する' : '保存する' ?></button>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/apps.php')) ?>">一覧へ戻る</a>
    </div>
  </form>

  <?php if (!$isNew): ?>
    <?php
      $snippet = "<?php\n"
        . "// このアプリ用の SSO 設定（client/sso_config.php として保存）\n"
        . "return [\n"
        . "    'idp_url'      => '" . Config::baseUrl() . "',\n"
        . "    'app_key'      => '" . $app['app_key'] . "',\n"
        . "    'app_secret'   => '" . $app['app_secret'] . "',\n"
        . "    'callback_url' => '" . $app['base_url'] . "/sso_callback.php',\n"
        . "];\n";
    ?>
    <div class="card">
      <h2 class="card__title">アプリ側に置く設定</h2>
      <p class="card__note">
        下の内容をアプリのサーバーに <code>sso_config.php</code> として保存し、
        <code>client/</code> のファイル一式と一緒に配置してください。
        <strong>共有秘密鍵はサーバー間通信にのみ使うもの</strong>で、ブラウザには一切送られません。
        公開ディレクトリの外に置くか、Web からアクセスできない場所に保存してください。
      </p>
      <textarea readonly rows="9"><?= h($snippet) ?></textarea>
      <form method="post" style="margin-top:12px"
            onsubmit="return confirm('共有秘密鍵を作り直します。\nアプリ側の設定を更新するまで、そのアプリはログインできなくなります。よろしいですか？');">
        <?= Csrf::field() ?>
        <input type="hidden" name="action" value="rotate">
        <button class="btn btn--danger btn--sm" type="submit">共有秘密鍵を再生成</button>
      </form>
    </div>

    <div class="card">
      <h2 class="card__title">アプリへの組み込み</h2>
      <p class="card__note">既存アプリの各ページの先頭に、次の1行を足すだけです。</p>
      <textarea readonly rows="4"><?php
        echo h("<?php require __DIR__ . '/sso/sso_guard.php';  // \$SSO_USER にログイン中のユーザーが入る ?>\n\n"
             . "こんにちは、<?= htmlspecialchars(\$SSO_USER['display_name']) ?> さん");
      ?></textarea>
      <p class="muted" style="margin-top:10px">
        戻り先ページ <code><?= h($app['base_url']) ?>/sso_callback.php</code> の設置も必要です。
        詳しくは <code>sso/README.md</code> を参照してください。
      </p>
    </div>
  <?php endif; ?>
</main>
<?php View::foot();

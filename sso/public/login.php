<?php
/**
 * ログイン画面。SSO で「一度だけ」通る場所。
 * アプリから authorize.php 経由で飛んでくる場合は next に戻り先が入る。
 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

/** next は自サイト内のパスだけを許可する（オープンリダイレクト対策）。 */
function safe_next(string $next): string
{
    if ($next === '') {
        return '';
    }
    if (str_starts_with($next, '/') && !str_starts_with($next, '//')) {
        return $next;
    }
    return url_is_within($next, Config::baseUrl()) ? $next : '';
}

$next    = safe_next((string) ($_REQUEST['next'] ?? ''));
$appKey  = (string) ($_REQUEST['app'] ?? '');
$app     = $appKey !== '' ? Apps::findByKey($appKey) : null;
$landing = $next !== '' ? $next : Config::baseUrl('index.php');

$current = Auth::currentUser();
if ($current !== null && $_SERVER['REQUEST_METHOD'] !== 'POST') {
    redirect($landing);
}

$error    = '';
$username = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $username = post('username');
    $result   = Auth::attempt($username, (string) ($_POST['password'] ?? ''));

    if ($result['ok']) {
        session_regenerate_id(true);
        Auth::startSession($result['user']);
        if (!empty($result['user']['must_change_password'])) {
            flash('warn', 'パスワードの変更が必要です。新しいパスワードを設定してください。');
            redirect(url_with(Config::baseUrl('password.php'), ['next' => $landing]));
        }
        redirect($landing);
    }
    $error = (string) $result['error'];
}

View::bare('ログイン');
?>
<main class="login-wrap">
  <div class="login-card">
    <div class="card">
      <div class="brand--stacked">
        <img src="<?= h(Config::baseUrl('assets')) ?>/img/welsys-logo.png" alt="WELSYS">
        <span class="brand__title"><?= h(View::PRODUCT_NAME) ?></span>
      </div>

      <?php if ($app !== null): ?>
        <p class="login-app-hint">
          <strong><?= h($app['name']) ?></strong> を利用するにはログインしてください。
        </p>
      <?php endif; ?>

      <?php if ($error !== ''): ?>
        <p class="notice notice--warn"><?= h($error) ?></p>
      <?php endif; ?>

      <form method="post" action="<?= h(Config::baseUrl('login.php')) ?>" autocomplete="on">
        <?= Csrf::field() ?>
        <input type="hidden" name="next" value="<?= h($next) ?>">
        <input type="hidden" name="app" value="<?= h($appKey) ?>">
        <div class="field">
          <label class="field__label" for="username">ログインID</label>
          <input id="username" name="username" type="text" value="<?= h($username) ?>"
                 autocomplete="username" autocapitalize="none" autofocus required>
        </div>
        <div class="field">
          <label class="field__label" for="password">パスワード</label>
          <input id="password" name="password" type="password" autocomplete="current-password" required>
        </div>
        <button class="btn" type="submit" style="width:100%">ログイン</button>
      </form>
      <p class="muted" style="margin-top:16px;text-align:center">
        1つのIDとパスワードで、許可された全てのアプリケーションを利用できます。
      </p>
    </div>
  </div>
</main>
<?php View::foot();

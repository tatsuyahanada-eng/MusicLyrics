<?php
/** ログアウト。SSO セッションを失効させ、全アプリのログイン状態を切る。 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

$returnTo = (string) ($_REQUEST['return'] ?? '');

// 戻り先は登録済みアプリの配下だけ許可する
$allowedReturn = '';
if ($returnTo !== '') {
    foreach (Apps::all() as $app) {
        if (url_is_within($returnTo, (string) $app['base_url'])) {
            $allowedReturn = $returnTo;
            break;
        }
    }
}

Auth::logout();
$_SESSION = [];
session_regenerate_id(true);

if ($allowedReturn !== '') {
    redirect($allowedReturn);
}

View::bare('ログアウトしました');
?>
<main class="login-wrap">
  <div class="login-card">
    <div class="card" style="text-align:center">
      <div class="brand--stacked">
        <img src="<?= h(Config::baseUrl('assets')) ?>/img/welsys-logo.png" alt="WELSYS">
        <span class="brand__title"><?= h(View::PRODUCT_NAME) ?></span>
      </div>
      <p>ログアウトしました。</p>
      <p class="muted">連携している各アプリケーションのログイン状態も順次解除されます。</p>
      <p><a class="btn" href="<?= h(Config::baseUrl('login.php')) ?>">もう一度ログイン</a></p>
    </div>
  </div>
</main>
<?php View::foot();

<?php
/** 本人によるパスワード変更。 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

$user   = Auth::requireUser();
$next   = (string) ($_REQUEST['next'] ?? '');
$errors = [];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $currentPassword = (string) ($_POST['current_password'] ?? '');
    $new             = (string) ($_POST['password'] ?? '');
    $confirm         = (string) ($_POST['password_confirm'] ?? '');

    $row = Users::find((int) $user['id']);
    if ($row === null || !password_verify($currentPassword, (string) $row['password_hash'])) {
        $errors['current_password'] = '現在のパスワードが正しくありません。';
    }
    if (($policyError = Users::checkPasswordPolicy($new)) !== null) {
        $errors['password'] = $policyError;
    } elseif ($new !== $confirm) {
        $errors['password_confirm'] = '確認用パスワードが一致しません。';
    } elseif ($new === $currentPassword) {
        $errors['password'] = '現在と異なるパスワードを設定してください。';
    }

    if ($errors === []) {
        // setPassword は全セッションを失効させるので、変更後にログインし直す
        Users::setPassword((int) $user['id'], $new, (int) $user['id'], false);
        Audit::log('user.password_change_self', (int) $user['id'], (int) $user['id']);
        flash('success', 'パスワードを変更しました。新しいパスワードでログインしてください。');
        redirect(url_with(Config::baseUrl('login.php'), $next !== '' ? ['next' => $next] : []));
    }
}

View::head('パスワード変更', $user, 'portal');
?>
<main class="container container--narrow">
  <?php View::pageTitle('パスワードの変更'); ?>
  <div class="card">
    <p class="card__note">
      変更すると、現在ログイン中の全ての端末・全てのアプリからログアウトされます。
    </p>
    <form method="post">
      <?= Csrf::field() ?>
      <input type="hidden" name="next" value="<?= h($next) ?>">
      <div class="field<?= isset($errors['current_password']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="current_password">現在のパスワード</label>
        <input id="current_password" name="current_password" type="password" autocomplete="current-password" required>
        <?php if (isset($errors['current_password'])): ?>
          <div class="field__error"><?= h($errors['current_password']) ?></div>
        <?php endif; ?>
      </div>
      <div class="field<?= isset($errors['password']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="password">新しいパスワード</label>
        <input id="password" name="password" type="password" autocomplete="new-password" required>
        <div class="field__hint">
          <?= (int) Config::get('password.min_length', 10) ?>文字以上で、英字と数字を両方含めてください。
        </div>
        <?php if (isset($errors['password'])): ?>
          <div class="field__error"><?= h($errors['password']) ?></div>
        <?php endif; ?>
      </div>
      <div class="field<?= isset($errors['password_confirm']) ? ' field--invalid' : '' ?>">
        <label class="field__label" for="password_confirm">新しいパスワード（確認）</label>
        <input id="password_confirm" name="password_confirm" type="password" autocomplete="new-password" required>
        <?php if (isset($errors['password_confirm'])): ?>
          <div class="field__error"><?= h($errors['password_confirm']) ?></div>
        <?php endif; ?>
      </div>
      <button class="btn" type="submit">変更する</button>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('index.php')) ?>">キャンセル</a>
    </form>
  </div>
</main>
<?php View::foot();

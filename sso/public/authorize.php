<?php
/**
 * SSO の入口。各アプリは未ログインの利用者をここへ送る。
 *
 *   GET authorize.php?app=<app_key>&return=<戻り先URL>&state=<任意の文字列>
 *
 *   1. 認証サーバーにログイン済みか確認（未ログインなら login.php へ）
 *   2. そのアプリの閲覧が許可されているか確認
 *   3. 許可されていれば使い捨てチケットを発行し、戻り先URLへリダイレクト
 *
 * ユーザー情報そのものは URL に載せない。アプリは受け取ったチケットを
 * validate.php にサーバー間通信で引き換える。
 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

$appKey    = query('app');
$returnUrl = (string) ($_GET['return'] ?? '');
$state     = query('state');

/** エラーはアプリに投げ返さず、この画面で止めて表示する。 */
function authorize_error(string $title, string $message, ?array $user = null): never
{
    http_response_code(400);
    View::head($title, $user, '');
    echo '<main class="container container--narrow"><div class="card">'
       . '<h1 class="page-head__title">' . h($title) . '</h1>'
       . '<p>' . h($message) . '</p>'
       . '<p class="muted">この画面が繰り返し表示される場合は、システム管理者にご連絡ください。</p>'
       . '</div></main>';
    View::foot();
    exit;
}

// ── 1. アプリの確認 ───────────────────────────────────────────
$app = $appKey === '' ? null : Apps::findByKey($appKey);
if ($app === null) {
    authorize_error('不明なアプリケーション', '認証を要求したアプリケーションが登録されていません。');
}
if (($app['status'] ?? '') !== 'active') {
    authorize_error('利用できません', 'このアプリケーションは現在無効化されています。');
}

// ── 2. 戻り先URLの検証（登録されたURL配下のみ許可） ────────────
if ($returnUrl === '') {
    $returnUrl = (string) $app['base_url'];
}
if (!url_is_within($returnUrl, (string) $app['base_url'])) {
    authorize_error('戻り先URLが不正です', '登録されているアプリのURL配下ではない戻り先が指定されました。');
}

// ── 3. ログイン確認 ───────────────────────────────────────────
$session = Auth::currentSession();
if ($session === null) {
    redirect(url_with(Config::baseUrl('login.php'), [
        'next' => (string) ($_SERVER['REQUEST_URI'] ?? '/authorize.php'),
        'app'  => $appKey,
    ]));
}
$user = Auth::userOf($session);

if (!empty($user['must_change_password'])) {
    flash('warn', 'パスワードの変更が必要です。');
    redirect(url_with(Config::baseUrl('password.php'), ['next' => (string) $_SERVER['REQUEST_URI']]));
}

// ── 4. 閲覧許可の判定 ─────────────────────────────────────────
if (!Permissions::isAllowed($user, $app)) {
    Audit::log('sso.denied', (int) $user['id'], (int) $user['id'], (int) $app['id']);
    http_response_code(403);
    $allowed = Permissions::allowedApps($user);
    View::head('閲覧が許可されていません', $user, '');
    ?>
    <main class="container container--narrow">
      <div class="card">
        <h1 class="page-head__title">閲覧が許可されていません</h1>
        <p>
          <strong><?= h($user['display_name'] ?: $user['username']) ?></strong> さんには
          <strong><?= h($app['name']) ?></strong> の閲覧権限が設定されていません。
        </p>
        <p class="muted"><?= h((string) Permissions::denyReason($user, $app)) ?></p>
        <p>利用が必要な場合は、システム管理者に閲覧許可を依頼してください。</p>

        <?php if ($allowed !== []): ?>
          <h2 class="card__title" style="margin-top:22px">ご利用いただけるアプリケーション</h2>
          <ul>
            <?php foreach ($allowed as $a): ?>
              <li><a href="<?= h($a['base_url']) ?>"><?= h($a['name']) ?></a></li>
            <?php endforeach; ?>
          </ul>
        <?php endif; ?>

        <p style="margin-top:18px">
          <a class="btn btn--ghost" href="<?= h(Config::baseUrl('index.php')) ?>">ポータルへ</a>
          <a class="btn btn--ghost" href="<?= h(Config::baseUrl('logout.php')) ?>">別のアカウントでログイン</a>
        </p>
      </div>
    </main>
    <?php
    View::foot();
    exit;
}

// ── 5. チケットを発行してアプリへ戻す ─────────────────────────
$ticket = Tickets::issue($app, $session, $returnUrl);
Audit::log('sso.ticket_issued', (int) $user['id'], (int) $user['id'], (int) $app['id']);

$params = ['sso_ticket' => $ticket];
if ($state !== '') {
    $params['state'] = $state;
}
redirect(url_with($returnUrl, $params));

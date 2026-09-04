<?php
declare(strict_types=1);

/**
 * 画面の共通枠（ヘッダー・ナビ・フッター）。
 * ヘッダーには WELSYS ロゴと英字タイトル "User Management" を並べて表示する。
 */
final class View
{
    public const PRODUCT_NAME = 'User Management';

    /**
     * @param array<string,mixed>|null $user   ログイン中のユーザー
     * @param string                   $active 現在のナビ項目
     * @param string                   $subtitle ページ見出し（<title> に付く）
     */
    public static function head(string $subtitle = '', ?array $user = null, string $active = ''): void
    {
        $title = $subtitle === '' ? self::PRODUCT_NAME : $subtitle . ' | ' . self::PRODUCT_NAME;
        $asset = Config::baseUrl('assets');

        header('Content-Type: text/html; charset=UTF-8');
        header('X-Frame-Options: DENY');
        header('X-Content-Type-Options: nosniff');
        header('Referrer-Policy: same-origin');
        ?>
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title><?= h($title) ?></title>
<link rel="icon" href="<?= h($asset) ?>/img/welsys-mark.png">
<link rel="stylesheet" href="<?= h($asset) ?>/style.css">
</head>
<body>
<header class="site-header">
  <div class="site-header__inner">
    <a class="brand" href="<?= h(Config::baseUrl('index.php')) ?>">
      <img class="brand__mark" src="<?= h($asset) ?>/img/welsys-mark.png" alt="WELSYS">
      <span class="brand__title"><?= h(self::PRODUCT_NAME) ?></span>
    </a>
    <?php if ($user !== null): ?>
      <nav class="nav">
        <a class="nav__item<?= $active === 'portal' ? ' is-active' : '' ?>"
           href="<?= h(Config::baseUrl('index.php')) ?>">ポータル</a>
        <?php if (!empty($user['is_admin'])): ?>
          <a class="nav__item<?= $active === 'users' ? ' is-active' : '' ?>"
             href="<?= h(Config::baseUrl('admin/users.php')) ?>">ユーザー</a>
          <a class="nav__item<?= $active === 'apps' ? ' is-active' : '' ?>"
             href="<?= h(Config::baseUrl('admin/apps.php')) ?>">アプリ</a>
          <a class="nav__item<?= $active === 'permissions' ? ' is-active' : '' ?>"
             href="<?= h(Config::baseUrl('admin/permissions.php')) ?>">権限マトリクス</a>
          <a class="nav__item<?= $active === 'logs' ? ' is-active' : '' ?>"
             href="<?= h(Config::baseUrl('admin/logs.php')) ?>">監査ログ</a>
        <?php endif; ?>
      </nav>
      <div class="account">
        <span class="account__name"><?= h($user['display_name'] ?: $user['username']) ?></span>
        <a class="btn btn--ghost btn--sm" href="<?= h(Config::baseUrl('logout.php')) ?>">ログアウト</a>
      </div>
    <?php endif; ?>
  </div>
</header>
        <?php
        foreach (take_flashes() as $flash) {
            printf(
                '<div class="flash flash--%s"><div class="flash__inner">%s</div></div>',
                h($flash['type']),
                h($flash['message'])
            );
        }
    }

    public static function foot(): void
    {
        ?>
<footer class="site-footer">
  <div class="site-footer__inner">
    <img class="site-footer__logo" src="<?= h(Config::baseUrl('assets')) ?>/img/welsys-wordmark.png" alt="WELSYS">
    <span>&copy; <?= date('Y') ?> WELSYS &mdash; <?= h(self::PRODUCT_NAME) ?></span>
  </div>
</footer>
</body>
</html>
        <?php
    }

    /** ログイン画面など、ナビの無い1枚ものの画面。 */
    public static function bare(string $subtitle = ''): void
    {
        self::head($subtitle, null, '');
    }

    /** ページ見出し。 */
    public static function pageTitle(string $title, string $lead = ''): void
    {
        echo '<div class="page-head"><h1 class="page-head__title">' . h($title) . '</h1>';
        if ($lead !== '') {
            echo '<p class="page-head__lead">' . h($lead) . '</p>';
        }
        echo '</div>';
    }

    /** 許可 / 拒否 / 既定 のバッジ。 */
    public static function effectBadge(string $effect, string $defaultPolicy = 'deny'): string
    {
        if ($effect === Permissions::ALLOW) {
            return '<span class="badge badge--allow">許可</span>';
        }
        if ($effect === Permissions::DENY) {
            return '<span class="badge badge--deny">拒否</span>';
        }
        $label = $defaultPolicy === 'allow' ? '既定（許可）' : '既定（拒否）';
        return '<span class="badge badge--default">' . h($label) . '</span>';
    }
}

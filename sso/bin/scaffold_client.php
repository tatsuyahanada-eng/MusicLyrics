<?php
/**
 * 登録済みのアプリ1つぶんの、既存アプリ側に置くファイル一式（sso/フォルダの中身）を
 * サーバー上の指定先へまとめて生成する。
 *
 *   php bin/scaffold_client.php --key=library --dest=/home/welsys/www/Library/sso
 *
 * 事前に、そのアプリを管理画面（または bin/register_app.php）で登録しておくこと。
 * app_secret などはデータベースから直接読むので、画面の表示内容を手でコピー＆
 * 貼り付けする必要はない。
 *
 * 生成されるもの：
 *   SsoClient.php / sso_guard.php / sso_callback.php / sso_logout.php （client/からコピー）
 *   sso_config.php   … そのアプリの実際の値で生成
 *   .htaccess         … 秘密情報の入ったファイルへの直接アクセスを拒否
 *
 * 既に同名のファイルが指定先にある場合は、確認なしで上書きする
 * （app_secretを再生成したあとの更新などに使うため）。
 */
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    exit("このスクリプトはコマンドラインから実行してください。\n");
}

require __DIR__ . '/../lib/bootstrap.php';

$options = [];
foreach (array_slice($argv, 1) as $arg) {
    if (preg_match('/\A--([a-z-]+)(?:=(.*))?\z/', $arg, $m) === 1) {
        $options[$m[1]] = $m[2] ?? '1';
    }
}

if (!isset($options['key'], $options['dest'])) {
    exit("使い方: php bin/scaffold_client.php --key=<アプリ識別子> --dest=<設置先ディレクトリ>\n"
       . "例    : php bin/scaffold_client.php --key=library --dest=~/www/Library/sso\n");
}

$app = Apps::findByKey($options['key']);
if ($app === null) {
    exit("アプリ識別子「{$options['key']}」は登録されていません。"
       . "先に管理画面（アプリ → ＋アプリを登録）か bin/register_app.php で登録してください。\n");
}

// "~" はシェルが展開してくれるはずだが、念のためここでも展開しておく
$dest = (string) $options['dest'];
if ($dest === '~' || str_starts_with($dest, '~/')) {
    $home = getenv('HOME');
    if ($home !== false) {
        $dest = $home . substr($dest, 1);
    }
}
$dest = rtrim($dest, '/');

if (!is_dir($dest) && !mkdir($dest, 0750, true)) {
    exit("設置先ディレクトリを作成できませんでした: {$dest}\n"
       . "（親ディレクトリが存在するか、書き込み権限があるか確認してください）\n");
}

$clientDir = SSO_ROOT . '/client';
$files = ['SsoClient.php', 'sso_guard.php', 'sso_callback.php', 'sso_logout.php'];
foreach ($files as $file) {
    $src = $clientDir . '/' . $file;
    if (!is_file($src)) {
        exit("元ファイルが見つかりません: {$src}\n");
    }
    if (!copy($src, $dest . '/' . $file)) {
        exit("コピーに失敗しました: {$dest}/{$file}\n");
    }
}

$callbackUrl = rtrim((string) $app['base_url'], '/') . '/sso/sso_callback.php';

$config = "<?php\n"
    . "// このアプリ（{$app['app_key']}）用の SSO 設定。\n"
    . "// bin/scaffold_client.php が自動生成した。手で編集する必要はない。\n"
    . "return [\n"
    . "    'idp_url'      => '" . addslashes(Config::baseUrl()) . "',\n"
    . "    'app_key'      => '" . addslashes((string) $app['app_key']) . "',\n"
    . "    'app_secret'   => '" . addslashes((string) $app['app_secret']) . "',\n"
    . "    'callback_url' => '" . addslashes($callbackUrl) . "',\n"
    . "];\n";
if (file_put_contents($dest . '/sso_config.php', $config) === false) {
    exit("sso_config.php の書き込みに失敗しました: {$dest}\n");
}
chmod($dest . '/sso_config.php', 0640);

$htaccess = '<FilesMatch "^(sso_config\.php|SsoClient\.php|sso_guard\.php)$">' . "\n"
    . "    Require all denied\n"
    . "</FilesMatch>\n";
file_put_contents($dest . '/.htaccess', $htaccess);

echo "「{$app['name']}」（{$app['app_key']}）用のファイル一式を作成しました。\n\n";
echo "  {$dest}/\n";
foreach ($files as $file) {
    echo "    {$file}\n";
}
echo "    sso_config.php   （app_secret 反映済み。編集不要）\n";
echo "    .htaccess        （秘密情報ファイルを非公開化）\n\n";

if (basename($dest) === 'sso') {
    echo "保護したいページの先頭に、次の1行を足してください。\n\n";
    echo "  <?php require __DIR__ . '/sso/sso_guard.php'; ?>\n";
} else {
    echo "保護したいページの先頭に、次のような1行を足してください\n";
    echo "（require のパスは、ページから {$dest} までの実際の相対位置に合わせること）。\n\n";
    echo "  <?php require __DIR__ . '/.../sso_guard.php'; ?>\n";
}

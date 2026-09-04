<?php
/**
 * アプリをコマンドラインから登録する（管理画面からでも登録できる）。
 *
 *   php bin/register_app.php --key=lyrics --name='Music Lyrics' --url=https://lyrics.example.com
 *   php bin/register_app.php --key=board --name='掲示板' --url=https://board.example.com --allow-all
 *
 * 登録後、アプリ側に置く設定ファイルの中身を表示する。
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

if (!isset($options['key'], $options['name'], $options['url'])) {
    exit("使い方: php bin/register_app.php --key=<識別子> --name=<アプリ名> --url=<URL> [--allow-all]\n");
}

$input = [
    'app_key'        => $options['key'],
    'name'           => $options['name'],
    'description'    => $options['description'] ?? '',
    'base_url'       => $options['url'],
    'default_policy' => isset($options['allow-all']) ? 'allow' : 'deny',
    'status'         => 'active',
];

$errors = Apps::validate($input);
if ($errors !== []) {
    echo "入力にエラーがあります:\n";
    foreach ($errors as $field => $message) {
        echo "  - {$field}: {$message}\n";
    }
    exit(1);
}

$secret = Apps::newSecret();
$id     = Apps::create($input + ['app_secret' => $secret]);
$app    = Apps::find($id);

echo "アプリ「{$app['name']}」を登録しました。\n\n";
echo "--- アプリ側に sso_config.php として保存 ---\n";
echo "<?php\nreturn [\n";
echo "    'idp_url'      => '" . Config::baseUrl() . "',\n";
echo "    'app_key'      => '" . $app['app_key'] . "',\n";
echo "    'app_secret'   => '" . $app['app_secret'] . "',\n";
echo "    'callback_url' => '" . $app['base_url'] . "/sso/sso_callback.php',\n";
echo "];\n";
echo "-------------------------------------------\n\n";
echo "共有秘密鍵は再表示できます（管理画面 > アプリ > 該当アプリ）。公開しないでください。\n";

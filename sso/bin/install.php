<?php
/**
 * 初期セットアップ（コマンドライン専用）。
 *
 *   php bin/install.php
 *   php bin/install.php --username=admin --password='Admin#2026pass' --name='システム管理者'
 *
 * テーブルを作成し、最初の管理者ユーザーを登録する。
 * 何度実行しても安全（テーブルは CREATE TABLE IF NOT EXISTS）。
 */
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    exit("このスクリプトはコマンドラインから実行してください。\n");
}

require __DIR__ . '/../lib/bootstrap.php';

/** @return array<string,string> */
function cli_options(array $argv): array
{
    $out = [];
    foreach (array_slice($argv, 1) as $arg) {
        if (preg_match('/\A--([a-z_]+)(?:=(.*))?\z/', $arg, $m) === 1) {
            $out[$m[1]] = $m[2] ?? '1';
        }
    }
    return $out;
}

function ask(string $prompt, bool $hidden = false): string
{
    echo $prompt;
    if ($hidden && DIRECTORY_SEPARATOR !== '\\') {
        shell_exec('stty -echo 2>/dev/null');
    }
    $line = trim((string) fgets(STDIN));
    if ($hidden && DIRECTORY_SEPARATOR !== '\\') {
        shell_exec('stty echo 2>/dev/null');
        echo "\n";
    }
    return $line;
}

$options = cli_options($argv);

echo "== WELSYS User Management セットアップ ==\n\n";

// ── 1. 接続確認 ───────────────────────────────────────────────
try {
    Db::pdo();
    echo "[1/3] データベースに接続しました。\n";
} catch (Throwable $e) {
    exit("データベースに接続できません: " . $e->getMessage() . "\n"
       . "config.php の db 設定を確認してください。\n");
}

// ── 2. テーブル作成 ───────────────────────────────────────────
$sql = (string) file_get_contents(SSO_ROOT . '/schema.sql');
$sql = (string) preg_replace('/^\s*--.*$/m', '', $sql);   // コメント行を除去

$applied = 0;
foreach (array_filter(array_map('trim', explode(';', $sql))) as $statement) {
    Db::pdo()->exec($statement);
    $applied++;
}
echo "[2/3] テーブルを作成しました（{$applied} 文を実行）。\n";

// ── 3. 最初の管理者 ───────────────────────────────────────────
$adminExists = (int) Db::value("SELECT COUNT(*) FROM users WHERE is_admin = 1") > 0;
if ($adminExists && !isset($options['force'])) {
    echo "[3/3] 管理者は既に登録されています。（追加したい場合は管理画面から）\n\n";
    echo "セットアップ完了です。ログイン画面: " . Config::baseUrl('login.php') . "\n";
    exit(0);
}

$username = $options['username'] ?? ask('管理者のログインID: ');
$name     = $options['name']     ?? ask('氏名（省略可）: ');
$email    = $options['email']    ?? ask('メールアドレス（省略可）: ');
$password = $options['password'] ?? ask('パスワード（入力は表示されません）: ', true);

$errors = Users::validate([
    'username' => $username,
    'email'    => $email,
    'password' => $password,
], null, true);

if ($errors !== []) {
    echo "\n入力にエラーがあります:\n";
    foreach ($errors as $field => $message) {
        echo "  - {$field}: {$message}\n";
    }
    exit(1);
}

$id = Users::create([
    'username'             => $username,
    'email'                => $email,
    'display_name'         => $name,
    'password'             => $password,
    'status'               => 'active',
    'is_admin'             => true,
    'must_change_password' => false,
], null);

echo "[3/3] 管理者ユーザー「{$username}」を作成しました（id={$id}）。\n\n";
echo "セットアップ完了です。\n";
echo "  ログイン画面 : " . Config::baseUrl('login.php') . "\n";
echo "  管理コンソール: " . Config::baseUrl('admin/users.php') . "\n";

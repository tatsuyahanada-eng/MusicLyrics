<?php
/* ============================================================
   Case By Case — backup-cron.php
   « さくらのレンタルサーバー等の cron（自動実行）から呼ぶファイル »

   決まった時刻に、その日の完全バックアップ（backups/backup-YYYY-MM-DD.json）
   を作ります。作られる中身は、画面から作られる自動バックアップとまったく同じ
   です（作成処理は backup.php に1本化してあります）。

   ── さくらのレンタルサーバーでの設定例 ──────────────────
   コントロールパネル →「スクリプト設定」→「CRON設定」
     実行コマンド: cd /home/アカウント名/www/casebycase && php backup-cron.php
     実行日時    : 毎日 3:10  など
   ※「/home/アカウント名/www/casebycase」の部分は、manual.html や api.php を
     置いてあるフォルダに読み替えてください。
   ※ php のパスを聞かれる／動かないときは「php」を
     「/usr/local/bin/php」に変えてお試しください。

   ── URLで実行したい場合（cronでcurlを使う等） ──────────
   そのままでは動きません（誰でも実行できてしまうため）。
   config.php に
       define('BACKUP_CRON_TOKEN', '長めの好きな文字列');
   を書くと、次のURLで実行できるようになります。
       https://（サイトのURL）/backup-cron.php?token=長めの好きな文字列

   ── 使えるオプション ──────────────────────────
   --force  … その日のバックアップが既にあっても作り直す
              （URLの場合は &force=1）
   ============================================================ */

$isCli = (PHP_SAPI === 'cli');

$cfgFile = __DIR__ . '/config.php';
if (is_file($cfgFile)) require_once $cfgFile;
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/backup.php';

if (!defined('BACKUP_CRON_TOKEN')) define('BACKUP_CRON_TOKEN', '');

/* ---- ブラウザ／URLから呼ばれたときは、合言葉が合っているときだけ実行する ---- */
if (!$isCli) {
  header('Content-Type: text/plain; charset=utf-8');
  header('Cache-Control: no-store');
  $token = isset($_GET['token']) ? (string) $_GET['token'] : '';
  if (BACKUP_CRON_TOKEN === '' || !hash_equals((string) BACKUP_CRON_TOKEN, $token)) {
    http_response_code(403);
    echo "このファイルはサーバーの自動実行（cron）用です。\n";
    echo "URLから実行するには config.php に BACKUP_CRON_TOKEN を設定してください。\n";
    exit(1);
  }
}

$force = false;
if ($isCli) {
  foreach (array_slice($argv, 1) as $a) { if ($a === '--force' || $a === '-f') $force = true; }
} else {
  $force = !empty($_GET['force']);
}

// cronは時間がかかっても構わないので、実行時間の上限をゆるめる
@set_time_limit(300);
@ini_set('memory_limit', '512M');

$stamp = date('Y-m-d H:i:s');
try {
  $pdo = cbc_pdo();
} catch (Throwable $e) {
  fwrite($isCli ? STDERR : STDOUT, "[$stamp] バックアップ失敗：データベースに接続できません（" . $e->getMessage() . "）\n");
  exit(1);
}

list($done, $msg, $path) = cbc_write_backup($pdo, date('Y-m-d'), $force);
$out = "[$stamp] " . basename($path) . ' … ' . $msg . "\n";

// 古いものの整理（config.php の BACKUP_KEEP_DAYS を設定しているときだけ動く）
$removed = cbc_prune_backups();
if ($removed) $out .= "[$stamp] 古いバックアップを削除：" . implode(', ', $removed) . "\n";

echo $out;
// 「すでに本日ぶんがあります」は異常ではないので、成功（0）で終える
exit(0);

<?php
/**
 * 期限切れセッション・チケットの掃除。cron で1日1回程度実行する。
 *
 *   0 4 * * *  php /var/www/sso/bin/gc.php > /dev/null 2>&1
 */
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    exit("このスクリプトはコマンドラインから実行してください。\n");
}

require __DIR__ . '/../lib/bootstrap.php';

Auth::gc();
echo "[" . now() . "] 期限切れのセッションとチケットを削除しました。\n";

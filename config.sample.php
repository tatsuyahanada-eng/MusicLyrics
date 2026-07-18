<?php
/* ============================================================
   Case By Case — 設定ファイル（サンプル）
   このファイルを「config.php」という名前でコピーし、値を設定してください。
   config.php はサーバーへアップロードしますが、Git には含めません。
   ============================================================ */

// CSV の保存先（サーバー上の "書き込み可能な" パス）
// 例: レンタルサーバの public 直下に置く場合は __DIR__ . '/data/manual.csv'
define('CSV_PATH', __DIR__ . '/data/manual.csv');

// API トークン（空なら認証なし）。
// インターネット公開サーバーでは必ず設定してください。
// 画面の「サーバー連携」→「APIトークン」に同じ値を入力すると連携できます。
define('API_TOKEN', '');

// ---- FTP 設定（別サーバー／指定パスへ CSV を一括更新する場合のみ） ----
define('FTP_HOST', '');                 // 例: ftp.example.com （空ならFTP機能OFF）
define('FTP_PORT', 21);
define('FTP_USER', '');
define('FTP_PASSWORD', '');
define('FTP_SECURE', false);            // 明示的FTPS(ftp_ssl_connect)を使う場合は true
define('FTP_REMOTE_PATH', 'manual.csv'); // アップロード先のリモートパス／ファイル名

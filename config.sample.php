<?php
/* ============================================================
   Case By Case — 設定ファイル（サンプル）
   このファイルを「config.php」にコピーし、値を設定してください。
   config.php はサーバーへアップロードしますが、Git には含めません。
   ============================================================ */

/* ---------- データベース（ロリポップ = mysql） ----------
   ロリポップ! ユーザー専用ページ →「サーバーの管理・設定」→「データベース」
   で作成したデータベースの情報を入力します。
     - サーバー   … 例: mysql-XXXXX.phy.lolipop.lan   → DB_HOST
     - データベース … 例: LAA0000000-casebycase         → DB_NAME
     - ユーザー    … 例: LAA0000000                     → DB_USER
     - パスワード  … 設定したパスワード                  → DB_PASS
   ※PHPのバージョンはユーザー専用ページで 7.4 以上を選択してください。
*/
define('DB_DRIVER',  'mysql');           // mysql | sqlite | pgsql
define('DB_HOST',    'mysql-XXXXX.phy.lolipop.lan');
define('DB_PORT',    3306);
define('DB_NAME',    'LAA0000000-casebycase');
define('DB_USER',    'LAA0000000');
define('DB_PASS',    'ここにDBパスワード');
define('DB_CHARSET', 'utf8mb4');

// DB_DRIVER='sqlite' のときのみ使用（ローカル検証や簡易運用向け）
define('DB_SQLITE_PATH', __DIR__ . '/data/manual.sqlite');

/* ---------- 編集用トークン（合言葉） ----------
   空 '' なら「誰でも編集可」。公開URLで運用する場合は必ず設定してください。
   画面の「サーバー連携 → 編集トークン」に同じ値を入力した人だけが
   追加・修正・削除・画像アップロードを行えます（閲覧は全員可）。
*/
define('API_TOKEN', '');

/* ---------- 管理者パスワード ----------
   項目の「閲覧ロック」を、個別パスワードを知らなくても解除できる管理用パスワード。
   初期化などの管理操作にも使います。
*/
define('ADMIN_PW', 'Welsys1234');

/* ---------- 画像アップロード ---------- */
define('UPLOAD_DIR',       __DIR__ . '/uploads');   // 保存先（書き込み権限が必要）
define('UPLOAD_URL',       'uploads');              // ブラウザからの相対URL
define('UPLOAD_MAX_BYTES', 5 * 1024 * 1024);        // 1ファイル上限（5MB）

/* ---------- AI（Google Gemini）連携 ----------
   「AIで探す」「AI要約」機能で使います。空 '' のときは AI 機能は表示されません。
   APIキーの取得: Google AI Studio（https://aistudio.google.com/apikey）で無料のキーを発行。
   ※キーはこのサーバー内だけで使い、ブラウザには渡しません（安全）。
   ※AIを使うと、その項目の本文がGoogleのAPIへ送信されます（要約・検索のため）。
   ※サーバー（PHP）から外部へのHTTPS通信（curl等）が必要です。ロリポップは既定で利用可能です。
*/
define('GEMINI_API_KEY', '');                 // 例: 'AIza...'（空なら AI 機能オフ）
define('GEMINI_MODEL',   'gemini-flash-latest'); // 使うモデル（例: gemini-flash-latest / gemini-2.5-flash）
// ※モデル名が古くなって使えなくなっても、自動で候補（2.5-flash等）を試して切り替えます。

/* ---------- 交通費計算（Google マップ距離） ----------
   オンサイト案件の交通費で、目的地までの車の距離を自動計算するのに使います。
   空 '' のときは自動計算は無効になり、距離は手入力になります。
   キーの取得: Google Cloud で「Distance Matrix API」を有効化し、APIキーを発行（課金設定が必要）。
   起点（TRAVEL_ORIGIN）は既定で「日本リテイル（東京都台東区台東2-1-1）」。変更可。 */
define('GOOGLE_MAPS_API_KEY', '');
define('TRAVEL_ORIGIN',       '東京都台東区台東2-1-1');

/* ---------- 自動バックアップ ----------
   毎日の完全バックアップ（backups/backup-YYYY-MM-DD.json）の設定です。

   ■ 時刻を決めて実行したい場合（さくら等の cron）
     cron で次のファイルを実行してください：  backup-cron.php
       例）cd /home/アカウント名/www/casebycase && php backup-cron.php
     cron が動くようになったら、下の AUTO_BACKUP_ON_ACCESS を false にすると、
     「誰かが画面を開いたとき」のバックアップは行わなくなります（cronだけになる）。
     ※ true のままでも二重にはなりません（1日1ファイルのため）。

   ■ cron を使わない場合
     これまでどおり、正午を過ぎた最初のアクセスで自動作成されます。
*/
define('AUTO_BACKUP_ON_ACCESS', true);  // 画面を開いたときの自動バックアップ（cronに任せるなら false）
define('BACKUP_KEEP_DAYS',      0);     // 何日分残すか。0 = 消さない。例）90 で90日より古いものを自動削除
define('BACKUP_CRON_TOKEN',     '');    // URLで backup-cron.php を実行したいときの合言葉（cronでphpを直接実行するなら空でOK）

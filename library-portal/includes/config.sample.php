<?php
/**
 * ライブラリポータル — 設定ファイル（ひな形）
 *
 * このファイルを config.php という名前でコピーし、
 * さくらのコントロールパネルで確認したデータベース情報を記入してください。
 * config.php はサーバー上にのみ置き、公開リポジトリには含めないでください。
 */

return [
    // ---- データベース（さくらのコントロールパネル → データベース で確認）----
    'db_host'     => 'mysqlXXX.db.sakura.ne.jp', // データベースサーバ名
    'db_name'     => 'アカウント名_library',       // データベース名
    'db_user'     => 'アカウント名',               // 接続ユーザー名
    'db_pass'     => 'ここにパスワード',           // 接続パスワード
    'db_port'     => 3306,

    // ---- アプリケーション ----
    'app_name'    => 'ライブラリポータル',
    'timezone'    => 'Asia/Tokyo',

    // 本番（https）では必ず true。http で動作確認する間だけ false。
    'secure_cookie' => true,

    // ログイン失敗のロック設定
    'max_failed'   => 5,    // 連続失敗回数
    'lock_minutes' => 10,   // ロック時間（分）

    // セッションの有効時間（分）。無操作がこの時間続くと再ログインが必要。
    'session_minutes' => 480,

    // ---- 利用者情報の供給元 ----------------------------------------
    // 'local'   … このアプリの lp_users を使う（単独運用。既定）
    // 'central' … 複数アプリ共通のユーザーデータベースを使う
    'auth_mode' => 'local',

    // このアプリの識別子。共通ユーザーDBで権限をアプリ単位に分けるための鍵。
    'app_key' => 'library',

    // 共通ユーザーDBで権限（auth_app_roles の行）が無い利用者の扱い
    //   'viewer' … 閲覧のみとしてログインを許可（社内へ広く公開する場合）
    //   'none'   … ログインを拒否（利用者を限定する場合）
    'default_role' => 'viewer',

    // ---- 共通ユーザーDBの接続情報（auth_mode = 'central' のときのみ使用）----
    // 同じ MySQL サーバー上なら db_host と同じ値で構いません。
    'auth_db_host' => 'mysqlXXX.db.sakura.ne.jp',
    'auth_db_name' => 'アカウント名_auth',
    'auth_db_user' => 'アカウント名',
    'auth_db_pass' => 'ここにパスワード',
    'auth_db_port' => 3306,
];

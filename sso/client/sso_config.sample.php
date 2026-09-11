<?php
/**
 * アプリごとの SSO 設定。
 * 管理画面（アプリの編集）に表示される内容をそのまま貼り付ける。
 * app_secret は絶対に公開しないこと。
 */
return [
    'idp_url'      => 'https://auth.example.com',
    'app_key'      => 'lyrics',
    'app_secret'   => 'ここに管理画面で発行された共有秘密鍵',
    'callback_url' => 'https://lyrics.example.com/sso/sso_callback.php',

    // 権限変更や停止を反映させるための再確認間隔（秒）
    'recheck_interval' => 60,

    // 開発環境で自己署名証明書を使う場合のみ false
    'verify_ssl' => true,

    // セッションCookieの名前。既定では app_key から自動生成される（例: WSSO_lyrics）。
    // 通常は指定不要。同一ドメイン配下に複数アプリを置く構成では、
    // 各アプリのセッションCookieが衝突しないよう、必ずアプリごとに別名になる。
    // 'session_name' => 'WSSO_lyrics',
];

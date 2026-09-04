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
];

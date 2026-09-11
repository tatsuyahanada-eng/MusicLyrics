<?php
// このアプリ（library）用の SSO 設定のひな形。
// サーバーごとに実際の値が異なるため、このファイルはコピーして
// sso/sso_config.php（Git管理外）として保存してください。
//
// 値は認証サーバー（usersso）側でこのアプリを登録したときに
// bin/scaffold_client.php が自動生成したものをそのまま使います。
return [
    'idp_url'      => 'https://welsysapp.com/usersso/public',
    'app_key'      => 'library',
    'app_secret'   => 'ここに認証サーバーが発行した app_secret を入れる',
    'callback_url' => 'https://welsysapp.com/Library/sso/sso_callback.php',
];

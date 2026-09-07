<?php
/**
 * OES入替作業APP / 管理者パスワードの設定ファイル
 *
 * 置き場所: index.html と同じ場所に "config.php" という名前で置く。
 *
 * このファイルはPHPとして実行されるため、ブラウザからURLを直接開いても
 * 中身（パスワード）は表示されません。index.html にはパスワードを書きません。
 *
 * パスワードを変えるときは、この1か所だけを書き換えてください。
 * （PHPが使えないサーバーの場合はこのファイルではなく config.json を使います。README参照）
 */

declare(strict_types=1);

return [
    'admin_password' => 'Welsys@1234',
];

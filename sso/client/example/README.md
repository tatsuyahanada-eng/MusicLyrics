# 組み込みサンプル

このディレクトリは、既存アプリに SSO を組み込むときの最小構成です。

アプリの公開ディレクトリに、次の4ファイルを置きます。

```
（アプリのドキュメントルート）/
├── index.php            ← 既存のページ。先頭に require を1行足すだけ
├── sso/
│   ├── SsoClient.php    ← client/SsoClient.php をコピー
│   ├── sso_guard.php    ← client/sso_guard.php をコピー
│   └── sso_config.php   ← 管理画面が表示する内容を貼り付け（公開しないこと）
├── sso_callback.php     ← client/sso_callback.php をコピー（require のパスをsso/に合わせる）
└── sso_logout.php       ← client/sso_logout.php をコピー
```

保護したいページの先頭に、次の1行を書きます。

```php
<?php require __DIR__ . '/sso/sso_guard.php'; ?>
```

これだけで、未ログインの利用者は認証サーバーへ送られ、
ログインと閲覧許可の確認が済んでからページが表示されます。
`$SSO_USER` に共通ユーザーデータベースのユーザー情報が入ります。

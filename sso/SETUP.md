# 導入手順書 — WELSYS User Management / SSO

このドキュメントだけを見れば、ゼロから本番導入できるようにまとめています。
機能の概要や画面の説明は [README.md](README.md) を参照してください。

---

## 1. 先に決めること：どちらの運用にするか

> **結論：各アプリのURLはこれまでどおりで構いません。**
> 「必ずポータルを開いてから各アプリへ」という運用にする必要はなく、
> 「各アプリのトップページを認証サーバーに差し替える」必要もありません。

この仕組みは **未ログインのときだけ自動的に認証サーバーを経由します**。

| 利用者の状態 | 何が起きるか |
|---|---|
| 未ログイン | アプリを開いた瞬間に認証サーバーへ転送 → ログイン → **元の開こうとしたページへ戻る** |
| ログイン済み | 認証サーバーを一瞬経由するが画面は出ず、そのままページが開く（体感 0.1〜0.3 秒） |
| 権限が無い | 「閲覧が許可されていません」と表示し、アプリの中身は一切表示しない |

アクセス制御としては、保護したページを開くたびに必ずガードが働きます。
つまり **URL を直接叩かれても素通りはできません**。
一方で、利用者にポータル画面を毎回見せる必要はない、ということです。

### 3つの運用パターン

| | 利用者の入口 | 向いている場合 | 必要な作業 |
|---|---|---|---|
| **A. 通過型（推奨）** | 各アプリのURL（今までのブックマークのまま） | 既存の運用・周知を変えたくない | なし（既定の動作） |
| **B. ポータル型** | 認証サーバーのポータル `https://auth.example.com/` | 「どのアプリが使えるか分からない」人が多い | ポータルのURLを周知するだけ |
| **C. A + B 併用（現実的なおすすめ）** | 両方 | ほとんどの現場 | 各アプリのヘッダーにポータルへのリンクを1本足す |

```
 A. 通過型                              B. ポータル型
 ┌──────────┐                         ┌──────────┐
 │ 利用者    │ ブックマーク            │ 利用者    │
 └────┬─────┘                         └────┬─────┘
      │ https://lyrics.example.com          │ https://auth.example.com
      ▼                                     ▼
 ┌──────────┐  未ログインの時だけ      ┌──────────────┐
 │ アプリA   │ ───────────────▶       │ ポータル       │
 └──────────┘   認証サーバー          │ 使えるアプリ一覧 │
                                      └───┬───┬───┬───┘
                                          ▼   ▼   ▼
                                        アプリA B  C
```

C を選ぶ場合、各アプリのヘッダーに次の1行を置くだけです。

```php
<a href="https://auth.example.com/">アプリ一覧</a>
<a href="/sso/sso_logout.php">ログアウト</a>
```

> 「社内からのアクセスを物理的に1か所へ集約したい」という要件（URL直打ちも通させない、
> PHP 以外の資材も守りたい）であれば、それはアプリ側の実装ではなく
> リバースプロキシや認証プロキシの担当領域です。必要になったら別途ご相談ください。

---

## 2. 置き場所を決める

認証サーバーは**独立したドメイン（またはサブドメイン）**で動かします。
既存アプリと同じサーバーに同居させても構いません（バーチャルホストを分けるだけ）。

```
サーバー（認証サーバー）
/var/www/welsys-sso/              ← リポジトリの sso/ をここに配置
├── config.php                    ← 秘密情報。公開領域の外にあること
├── schema.sql
├── lib/                          ← 公開しない
├── bin/                          ← 公開しない
├── client/                       ← 配布用。公開しなくてよい
└── public/                       ← ★ここだけを DocumentRoot にする
    ├── login.php  authorize.php  validate.php ...
    └── admin/

サーバー（既存アプリ側・アプリごと）
/var/www/lyrics/                  ← 既存アプリ。DocumentRoot はこれまでどおり
├── index.php                     ← 既存のページ（先頭に1行足すだけ）
└── sso/                          ← ここにクライアント4ファイル＋設定を置く
    ├── SsoClient.php
    ├── sso_guard.php
    ├── sso_callback.php
    ├── sso_logout.php
    └── sso_config.php            ← 共有秘密鍵。外部に漏らさない
```

| 決めること | 例 |
|---|---|
| 認証サーバーのURL | `https://auth.example.com` |
| データベース名 | `welsys_sso` |
| DBユーザー | `welsys_sso` |
| 配置先 | `/var/www/welsys-sso` |
| PHP の実行ユーザー | `www-data`（Apache/nginx の標準） |

---

## 3. 認証サーバーの構築

### 手順1. 必要なものを確認する

```bash
php -v                    # 8.1 以上
php -m | grep -E 'pdo_mysql|mbstring|json|curl'
mysql --version           # MySQL 5.7.8 以上 / MariaDB 10.2 以上
```

HTTPS の証明書を用意してください（Cookie を Secure 属性で発行するため、本番では必須です）。

### 手順2. データベースとDBユーザーを作る

`mysql -u root -p` で接続し、次を実行します。パスワードは推測されないものにしてください。

```sql
-- データベース本体
CREATE DATABASE welsys_sso
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- アプリが日常的に使うユーザー（テーブル作成の権限は与えない）
CREATE USER 'welsys_sso'@'localhost' IDENTIFIED BY '＊＊＊＊＊＊＊＊';
GRANT SELECT, INSERT, UPDATE, DELETE ON welsys_sso.* TO 'welsys_sso'@'localhost';
FLUSH PRIVILEGES;
```

> DB が別サーバーにある場合は `'welsys_sso'@'localhost'` の部分を
> `'welsys_sso'@'10.0.0.%'` のようにウェブサーバー側のアドレスに変えてください。

### 手順3. ファイルを配置する

```bash
# 取得（すでに手元にある場合はコピーでも構いません）
git clone https://github.com/tatsuyahanada-eng/MusicLyrics.git /tmp/musiclyrics

# sso/ の中身を配置先へ
sudo mkdir -p /var/www/welsys-sso
sudo cp -r /tmp/musiclyrics/sso/. /var/www/welsys-sso/

# 所有者を PHP の実行ユーザーに
sudo chown -R www-data:www-data /var/www/welsys-sso
sudo find /var/www/welsys-sso -type d -exec chmod 750 {} \;
sudo find /var/www/welsys-sso -type f -exec chmod 640 {} \;
```

### 手順4. 設定ファイルを作る

```bash
cd /var/www/welsys-sso
sudo -u www-data cp config.sample.php config.php
sudo -u www-data vi config.php
sudo chmod 600 config.php          # 他のユーザーから読めないようにする
```

最低限、次の3か所を自分の環境に書き換えます。

```php
'db' => [
    'dsn'  => 'mysql:host=127.0.0.1;port=3306;dbname=welsys_sso;charset=utf8mb4',
    'user' => 'welsys_sso',
    'pass' => '手順2で決めたパスワード',
],
'base_url' => 'https://auth.example.com',   // public/ の公開URL。末尾スラッシュ無し
'cookie' => [
    'secure' => true,                        // HTTPS でないと動きません
],
```

### 手順5. テーブルを作り、最初の管理者を登録する

`bin/install.php` はテーブルを作成するため、**CREATE 権限のある接続**が必要です。
手順2で作った DB ユーザーには CREATE を与えていないので、次のどちらかで行います。

**方法A：先に root でテーブルだけ作る（推奨）**

```bash
mysql -u root -p welsys_sso < /var/www/welsys-sso/schema.sql
cd /var/www/welsys-sso
sudo -u www-data php bin/install.php      # 既存テーブルはそのまま使われ、管理者だけ作られる
```

**方法B：一時的に CREATE 権限を与える**

```sql
GRANT ALL ON welsys_sso.* TO 'welsys_sso'@'localhost';   -- 作業前
```
```bash
sudo -u www-data php bin/install.php
```
```sql
REVOKE ALL ON welsys_sso.* FROM 'welsys_sso'@'localhost';               -- 作業後に戻す
GRANT SELECT, INSERT, UPDATE, DELETE ON welsys_sso.* TO 'welsys_sso'@'localhost';
FLUSH PRIVILEGES;
```

対話形式で管理者のログインIDとパスワードを聞かれます。引数でも渡せます。

```bash
sudo -u www-data php bin/install.php --username=admin --name='システム管理者' --email=admin@example.com
```

作成されたテーブルは次の7つです。

| テーブル | 役割 |
|---|---|
| `users` | 全アプリ共通のユーザーマスタ |
| `apps` | SSO に参加するアプリの登録簿 |
| `app_permissions` | ユーザー × アプリ の許可／拒否 |
| `app_user_links` | 既存アプリ内のユーザーIDとの対応表 |
| `sso_sessions` | ログインセッション（SSO の実体） |
| `sso_tickets` | 使い捨てチケット |
| `audit_logs` | 監査ログ |

### 手順6. ウェブサーバーを設定する

**DocumentRoot は必ず `public/` にしてください。** 一段上を公開すると
`config.php`（DBパスワード）が読めてしまいます。

Apache:

```apache
<VirtualHost *:443>
    ServerName auth.example.com
    DocumentRoot /var/www/welsys-sso/public

    <Directory /var/www/welsys-sso/public>
        AllowOverride None
        Require all granted
    </Directory>

    SSLEngine on
    SSLCertificateFile    /etc/letsencrypt/live/auth.example.com/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/auth.example.com/privkey.pem
</VirtualHost>
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name auth.example.com;
    root /var/www/welsys-sso/public;
    index index.php;

    location / { try_files $uri $uri/ /index.php?$query_string; }

    location ~ \.php$ {
        include fastcgi_params;
        fastcgi_pass unix:/run/php/php-fpm.sock;
        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
    }

    ssl_certificate     /etc/letsencrypt/live/auth.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/auth.example.com/privkey.pem;
}
```

設定を反映します。

```bash
sudo apachectl configtest && sudo systemctl reload apache2     # Apache の場合
sudo nginx -t && sudo systemctl reload nginx                   # nginx の場合
```

### 手順7. 動作を確認する

1. `https://auth.example.com/login.php` を開き、ログイン画面が出ること
2. 手順5で作った管理者でログインできること
3. 上部メニューに「ユーザー」「アプリ」「権限マトリクス」「監査ログ」が出ること
4. `https://auth.example.com/config.php` を開いて **404 か 403 になること**（中身が見えたら DocumentRoot の設定ミス）

### 手順8. 掃除処理を cron に入れる

```bash
sudo crontab -u www-data -e
```
```cron
0 4 * * * php /var/www/welsys-sso/bin/gc.php > /dev/null 2>&1
```

**ここまでで認証サーバーは完成です。既存のアプリはまだ何も変わっていません。**

---

## 4. 既存アプリを1つずつ切り替える

以下はアプリ1つぶんの手順です。1つ終えてから次へ進めば、影響を局所化できます。

### 手順9. アプリを登録する

管理画面 → **アプリ** → 「＋ アプリを登録」

| 項目 | 例 | 備考 |
|---|---|---|
| アプリ名 | Music Lyrics | 画面に出る名前 |
| アプリ識別子 | `lyrics` | 半角英小文字・数字・`-` `_` |
| アプリのURL | `https://lyrics.example.com` | **戻り先はこのURL配下のみ許可されます** |
| 既定ポリシー | 許可した人のみ | 全社共有のアプリだけ「全員に許可」 |

保存すると、アプリ側に貼り付ける設定内容がその場で表示されます。

コマンドラインでも登録できます。

```bash
cd /var/www/welsys-sso
sudo -u www-data php bin/register_app.php \
  --key=lyrics --name='Music Lyrics' --url=https://lyrics.example.com
```

### 手順10. アプリ側にファイルを置く

```bash
sudo mkdir -p /var/www/lyrics/sso
sudo cp /var/www/welsys-sso/client/SsoClient.php \
        /var/www/welsys-sso/client/sso_guard.php \
        /var/www/welsys-sso/client/sso_callback.php \
        /var/www/welsys-sso/client/sso_logout.php \
        /var/www/lyrics/sso/
sudo chown -R www-data:www-data /var/www/lyrics/sso
```

続いて設定ファイルを作ります。中身は手順9の画面に表示されたものをそのまま貼ります。

```bash
sudo -u www-data vi /var/www/lyrics/sso/sso_config.php
sudo chmod 600 /var/www/lyrics/sso/sso_config.php
```

```php
<?php
return [
    'idp_url'      => 'https://auth.example.com',
    'app_key'      => 'lyrics',
    'app_secret'   => '（管理画面に表示された共有秘密鍵）',
    'callback_url' => 'https://lyrics.example.com/sso/sso_callback.php',
];
```

> 4ファイルは**すべて同じ `sso/` に置きます**。パスの書き換えは不要です。
> `app_secret` はサーバー間通信にだけ使う鍵で、ブラウザには一切送られません。
> Git にコミットしないでください。

### 手順11. 既存ページを保護する

保護したいページの**いちばん先頭**（HTML より前）に1行足します。

```php
<?php require __DIR__ . '/sso/sso_guard.php'; ?>
<!DOCTYPE html>
<html>
...
```

サブディレクトリのページからは相対の深さに合わせてください。

```php
<?php require __DIR__ . '/../sso/sso_guard.php'; ?>
```

ログイン中のユーザーは `$SSO_USER` で参照できます。

```php
こんにちは、<?= htmlspecialchars($SSO_USER['display_name']) ?> さん
```

ログアウトのリンクは `/sso/sso_logout.php` に向けます。

### 手順12. 権限を付けて動作確認する

1. 管理画面 → **権限マトリクス** → そのアプリの列を、使わせたい人だけ「許可」にする
2. ブラウザのシークレットウィンドウで `https://lyrics.example.com/` を開く
3. ログイン画面に飛び、ログインすると **元のページに戻ってくる**こと
4. 権限を「拒否」に変えると、1分以内にそのユーザーが締め出されること
5. 別のアプリを切り替えたあと、**再ログインなしで**そのアプリも開けること

### 手順13. 元に戻したくなったら

手順11で足した `require` の1行をコメントアウトするだけで、そのアプリは元の状態に戻ります。
認証サーバーやデータベースを触る必要はありません。

```php
<?php // require __DIR__ . '/sso/sso_guard.php'; ?>
```

---

## 5. 既存ユーザーを移行する

### 5-1. 一覧を CSV にする

既存アプリのユーザー表から、次の並びで CSV を作ります（ヘッダー行はあってもなくても可）。

```csv
username,display_name,department,email,password,status,is_admin,apps
y.suzuki,鈴木 陽子,営業,y.suzuki@example.com,Initial#2026pw,active,0,lyrics|kintai
k.sato,佐藤 健,総務,k.sato@example.com,Initial#2026pw,active,0,*
```

- `password` は仮のもので構いません。初回ログイン時に本人へ変更を求めます
- `apps` は閲覧を許可するアプリ識別子。`*` で全アプリ、空欄なら権限を変更しません
- Excel が出す Shift_JIS の CSV もそのまま読めます

管理画面 → **ユーザー** → 「CSV一括登録」で貼り付けるかファイルを選び、
**確認画面の内容を見てから**「実行する」を押します。

### 5-2. 既存アプリ内のユーザーIDと対応付ける

アプリが自前のユーザーテーブルを持っている場合、
管理画面 → ユーザーを開く → 「アプリの閲覧許可」の
**「アプリ内のユーザーID」**に、そのアプリでの ID を入れておきます。

アプリ側では次のように受け取れるので、既存のデータ構造をそのまま使い続けられます。

```php
require __DIR__ . '/sso/sso_guard.php';

$localUserId = $SSO_USER['external_user_id'] ?? null;
if ($localUserId === null) {
    // 初回だけ、アプリ側のユーザー行を作る／既存行に紐づける
}
$me = findLocalUser($localUserId);      // 以降は今までのコードのまま
```

### 5-3. 独自ログイン画面を閉じる

切り替えたアプリのログインフォームとパスワード欄は、動作が安定したら削除してください。
移行中はパスワードが二重管理になり、利用者が混乱します。

---

## 6. 日々の運用

| やりたいこと | 場所 |
|---|---|
| ユーザーを1人追加 | ユーザー → ＋ ユーザーを追加 |
| ユーザーをまとめて追加・更新 | ユーザー → CSV一括登録 |
| 退職者を止める | ユーザーを開いて状態を「停止中」に（全端末が即ログアウト） |
| 退職者を消す | ユーザー一覧の「削除」（権限・セッションも一緒に消える） |
| パスワードを忘れた | ユーザーを開いて「パスワードの再設定」＋「次回変更を求める」 |
| ロックされた | ユーザー一覧の「ロック解除」 |
| 権限を1人ずつ変える | ユーザーを開いて「アプリの閲覧許可」 |
| 権限をまとめて変える | 権限マトリクス → 一括設定 |
| 誰が何をしたか調べる | 監査ログ |

### バックアップ

```bash
mysqldump -u root -p --single-transaction welsys_sso > /backup/welsys_sso_$(date +%F).sql
```

`config.php` と各アプリの `sso_config.php` も、DBとは別に安全な場所へ保管してください。

---

## 7. 導入チェックリスト

- [ ] `config.php` のパーミッションが 600 で、ウェブから見えない
- [ ] DocumentRoot が `public/` になっている（`/config.php` が 404/403）
- [ ] HTTPS で動いていて、`cookie.secure` が `true`
- [ ] DBユーザーに CREATE 権限が残っていない
- [ ] 管理者アカウントが2人以上いる（1人だと事故時に詰みます）
- [ ] `bin/gc.php` を cron に登録した
- [ ] 各アプリの `sso_config.php` を Git にコミットしていない
- [ ] 権限を外したユーザーが1分以内に締め出されることを確認した
- [ ] DB のバックアップが取れている

---

## 8. つまずきやすい点

| 症状 | 原因と対処 |
|---|---|
| ログイン後にログイン画面へ戻ってしまう | Cookie が保存できていない。`cookie.secure = true` なのに http で開いている、または PHP と MySQL の時刻が大きくずれている（`date` と `SELECT NOW()` を比較） |
| 「戻り先URLが不正です」と出る | 管理画面の「アプリのURL」と、`sso_config.php` の `callback_url` のドメイン・ポートが一致していない |
| 「bad_signature」と出る | `sso_config.php` の `app_secret` が古い。管理画面でアプリを開き直して貼り直す |
| 「network_error」と出る | アプリのサーバーから認証サーバーへ HTTPS で到達できていない。ファイアウォールと名前解決を確認 |
| 転送が延々と繰り返される | `sso_callback.php` の中で `sso_guard.php` を読み込んでいる。コールバックは保護しないこと |
| 画面が真っ白 | PHP のエラーログを確認。多くは `config.php` の書式ミスか DB 接続失敗 |

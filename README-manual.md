# Case By Case — ツリー型 作業マニュアル

大項目を選ぶと枝分かれし、最終的な作業手順まで案内する「チャットボット風」のツリーマニュアルです。
案内モードで使い、編集モードで項目を追加・修正できます。データは CSV で管理でき、PHP を置けば
サーバー保存・追記・FTP一括更新にも対応します。

## ファイル構成

| ファイル | 役割 | 必須 |
|---|---|---|
| `manual.html` / `manual.css` / `manual.js` | アプリ本体（画面・ロジック） | ○ |
| `manifest.webmanifest` / `sw.js` / `icon.svg` | PWA（インストール・オフライン） | ○ |
| `api.php` | サーバーCSV / FTP 連携 API（PHP） | 任意 |
| `config.sample.php` | 設定サンプル（→ `config.php` にコピー） | api.php使用時 |
| `data/` | サーバー保存CSVの置き場（`.htaccess`で直接アクセス禁止） | api.php使用時 |

## レンタルサーバへのアップロード手順

1. 上記ファイルを、公開ディレクトリ（例: `public_html/manual/`）へFTPでアップロード。
2. サーバー連携を使う場合:
   - `config.sample.php` を **`config.php`** という名前でコピーし、値を設定。
     - `CSV_PATH` … CSVの保存先（書き込み可能なパス。`data/manual.csv` のままでもOK）
     - `API_TOKEN` … 公開サーバーでは必ず設定（画面側にも同じ値を入力）
     - `FTP_*` … 別サーバー／指定パスへ一括更新する場合のみ設定
   - `data/` フォルダに書き込み権限（例: 705/755、必要に応じて 707/777）を付与。
3. ブラウザで `https://お使いのドメイン/manual/manual.html` を開く。
4. 「ホーム画面に追加 / インストール」でアプリとして利用可能（PWA）。

> HTTPS で配信するとPWA（オフライン・インストール）が有効になります。

## データ（CSV）の形式

フラットな隣接リスト形式。将来 MySQL に移行する際も、そのままテーブル化できます。

```csv
id,parent_id,sort_order,title,body
n_a,,0,ドミネーターが起動しない,
n_b,n_a,0,ウォレットが接続できない,
n_c,n_b,0,「CONNECT WALLET」を押しても反応しない,"# 対処手順
- ブラウザを更新
- 拡張機能を確認"
```

- `id` … 行の一意ID
- `parent_id` … 親のID（空＝大項目/カテゴリ）
- `sort_order` … 同じ親の中での並び順
- `title` … 画面の選択肢に表示される項目名
- `body` … 選んだときに表示される説明・手順（改行・カンマ可。CSVとして引用符で囲まれます）

## 使い方（画面）

- **案内モード**: 大項目 → 分岐 → 最終作業項目まで選択で進む。パンくず／戻る／最初から で移動。
- **編集モード**:
  - 項目の追加・修正・削除・並び替え。
  - **CSV出力／CSV取込**: ブラウザだけで完結（サーバー不要）。
  - **サーバー連携（api.php 設置時）**:
    - サーバーから読込 / サーバーへ保存（全体）
    - FTPから取得 / FTPへ送信（一括更新）
    - 「項目を追加したらサーバーCSVにも自動追記」トグル

## API（api.php）概要

すべて `api.php?action=...`。`API_TOKEN` 設定時はヘッダ `X-Api-Token` が必要。

| action | メソッド | 内容 |
|---|---|---|
| `config` | GET | 接続確認 / FTP設定有無 |
| `load` | GET | サーバーCSVを取得 |
| `save` | POST `{csv}` | サーバーCSVを全置換 |
| `append` | POST `{rows:[...]}` | サーバーCSVに行を追記 |
| `ftp_pull` | POST | FTP先からCSVを取得 |
| `ftp_push` | POST `{csv}` | FTP先へCSVを送信（一括更新） |

## 将来のDB（MySQL）化について

CSVの列（`id, parent_id, sort_order, title, body`）はそのままテーブル定義にできます。
`api.php` の各アクションの中身を、ファイル入出力から `PDO`/`mysqli` のクエリに置き換えるだけで、
画面側（`manual.js`）は変更なしに移行できます。

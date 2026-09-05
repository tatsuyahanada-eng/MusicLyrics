# OES入替作業APP

GoogleカレンダーへOES入替作業の予定をまとめて登録するためのWebツールです。
パソコン・スマートフォンのどちらからでも使えます。**会社の複数の担当者が、それぞれ自分の端末で使うこと**を想定しています。

- 本体: [`index.html`](index.html)（単一ファイル・外部依存なし）
- 操作マニュアル: [`manual.html`](manual.html)
- 仕様書: [`SPEC.md`](SPEC.md) ／ 開発時の指示書: [`CLAUDE.md`](CLAUDE.md)

## 使い方（概要）

1. **📅 カレンダー** — 作業日を複数タップして選び、業態・時間帯・担当者・店舗名・住所を指定して登録リストへ追加
2. **📋 登録リスト** — 1件ずつ内容を確認・修正し、Googleカレンダーへ順番に登録（または ICS で一括取込）
3. **🏃 作業当日** — まず「自分（読み込む担当者）」を選び、その日の予定を読み込む。入店連絡／中間報告／退店連絡をコピーして Google Chat に貼り付け（「よく使う文」もワンタップでコピー）。
   予定は、選んだ本人自身のGoogleカレンダーから読み込むか、該当の予定を手動でコピーして貼り付ける
4. **⚙️ 設定** — 業態・時間帯ごとの定型文、担当者と担当者定型文（＋各自のカレンダー連携URL）、タイトル書式などを編集。管理者が1つの設定ファイルにまとめて全員に配布できる

詳しい操作は [`manual.html`](manual.html) をブラウザで開いてください。

## 動作確認

サーバーは不要です。`index.html` をブラウザで開けばそのまま動きます。

## サーバーへの配置

同じ階層に次を配置します。

```
index.html
manual.html
assets/welsys-logo.jpg
assets/device-printer.jpg
assets/device-kitchen.jpg
```

`index.html` は画像をすべて内包しているため単体でも動作します。`manual.html` は `assets/` の画像を参照します。

Basic認証をかける場合は [`deploy/.htaccess.sample`](deploy/.htaccess.sample) を参考にしてください
（`AuthUserFile` は配置先サーバーの絶対パスに書き換えが必要です）。

## ディレクトリ

| パス | 内容 |
|---|---|
| `index.html` | アプリ本体 |
| `manual.html` | 操作マニュアル |
| `assets/` | ロゴ画像・機器画像 |
| `apps-script/` | Googleカレンダー連携用のApps Scriptコード（任意機能） |
| `deploy/` | サーバー配置用サンプル |
| `legacy/` | Claude Chatで作成した旧版（参照用・非稼働） |

---

&copy; ウェルシス株式会社

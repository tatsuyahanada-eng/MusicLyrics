# OES入れ替え作業登録カレンダー

GoogleカレンダーへOES入替作業の予定をまとめて登録するためのWebツールです。
パソコン・スマートフォンのどちらからでも使えます。

- 本体: [`index.html`](index.html)（単一ファイル・外部依存なし）
- 操作マニュアル: [`manual.html`](manual.html)
- 仕様書: [`SPEC.md`](SPEC.md) ／ 開発時の指示書: [`CLAUDE.md`](CLAUDE.md)

## 使い方（概要）

1. **📅 カレンダー** — 作業日を複数タップして選び、業態・時間帯・担当者・店舗名・住所を指定して登録リストへ追加
2. **📋 登録リスト** — 1件ずつ内容を確認・修正し、Googleカレンダーへ順番に登録（または ICS で一括取込）
3. **⚙️ 設定** — 業態・時間帯ごとの定型文、担当者と担当者定型文、タイトル書式などを編集

詳しい操作は [`manual.html`](manual.html) をブラウザで開いてください。

## 動作確認

サーバーは不要です。`index.html` をブラウザで開けばそのまま動きます。

## サーバーへの配置

同じ階層に次を配置します。

```
index.html
manual.html
assets/welsys-logo.jpg
```

`index.html` はロゴを内包しているため単体でも動作します。`manual.html` は `assets/welsys-logo.jpg` を参照します。

Basic認証をかける場合は [`deploy/.htaccess.sample`](deploy/.htaccess.sample) を参考にしてください
（`AuthUserFile` は配置先サーバーの絶対パスに書き換えが必要です）。

## ディレクトリ

| パス | 内容 |
|---|---|
| `index.html` | アプリ本体 |
| `manual.html` | 操作マニュアル |
| `assets/` | ロゴ画像 |
| `deploy/` | サーバー配置用サンプル |
| `legacy/` | Claude Chatで作成した旧版（参照用・非稼働） |

---

&copy; ウェルシス株式会社

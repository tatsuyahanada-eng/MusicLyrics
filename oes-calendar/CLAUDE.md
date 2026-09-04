# CLAUDE.md — OES入れ替え作業登録カレンダー

このディレクトリは「OES入れ替え作業登録カレンダー」プロジェクトです。詳細仕様は `SPEC.md` を参照してください。

## プロジェクト概要

GoogleカレンダーへOES入替作業の予定を登録するための、単一HTMLファイルのWebツール。

| ファイル | 役割 |
|---|---|
| `index.html` | 本体。HTML/CSS/JS＋ロゴ画像（data URI）を1ファイルに内包 |
| `manual.html` | 操作マニュアル（レスポンシブHTML。ブラウザの印刷でPDF化可） |
| `assets/welsys-logo.jpg` | ウェルシス株式会社ロゴ（manual.html が参照。index.html は同じ画像をbase64で内包） |
| `deploy/.htaccess.sample` | Basic認証用サンプル |
| `legacy/` | Claude Chat時代の旧版（参照用・非稼働） |

## 重要なルール

- **`index.html` は1ファイル完結**を維持する。フレームワーク・ビルドツール・外部CDN・npmパッケージは導入しない。バニラJS（ES5相当）で書く。
- 画像も外部ファイルにせず data URI で埋め込む（FTPで `index.html` 1つ置けば動く状態を保つ）。
- **設定（業態・時間帯・定型文・担当者）は `localStorage` に保存する**（キー: `oes-calendar-settings-v1`）。
  旧版の「ブラウザストレージ禁止」ルールは、定型文を利用者が編集できるようにするため廃止した。
  ただし**登録リスト（作業予定）は保存しない**（揮発）。
- 日本語UI。配色は青系（メイン `#1565C0`）を維持する。
- **パソコン・スマートフォン両対応**。入力欄の `font-size` は16px以上（iOSの自動ズーム防止）、タップ領域は42px前後を確保する。
- **フッターのコピーライトにはウェルシス株式会社のロゴと社名を必ず入れる。**
- **`index.html` の仕様を変えたら `manual.html` と `SPEC.md` も必ず更新する。**

## 画面構成（3タブ）

1. **📅 カレンダー** — 日付を複数選択 → 業態・時間帯・担当者・店舗名・住所 → 登録リストへ一括追加
2. **📋 登録リスト** — 1件ずつ編集 → Googleカレンダーへ順番に登録／ICS書き出し
3. **⚙️ 設定** — 業態・時間帯・定型文、担当者と担当者定型文、共通設定、設定の書き出し/読み込み

## 主な編集ポイント（index.html内）

| 関数・定数 | 役割 |
|---|---|
| `defaultSettings()` | 初期の業態・時間帯・定型文・担当者。既定値の変更はここ |
| `TPL_STANDARD` / `TPL_SUKIYA` / `TPL_STAFF` | 定型文のひな形 |
| `normalizeSettings(o)` | 読み込んだ設定の正規化。**設定項目を増やしたらここも必ず更新** |
| `fillTemplate(tpl, ctx)` | 差し込み文字の置換。`{担当者定型文}` を最初に展開する |
| `buildDesc(en)` / `buildTitle(en)` | 説明文・タイトルの生成 |
| `renderCalendars()` | カレンダー描画（PCは2ヶ月、スマホは1ヶ月） |
| `addSelectedToList()` | 選択日をまとめて登録リストへ |
| `entryInnerHtml(en, i)` | 登録リストのカード1件分のHTML |
| `googleUrl(en)` | GoogleカレンダーのTEMPLATE URL |
| `buildIcs()` / `icsFold()` | ICS生成（RFC5545の75オクテット折返し） |
| `renderSettings()` / `renderStaffList()` | 設定画面の描画 |

## 差し込み文字

`{業態}` `{店舗名}` `{日付}` `{開始}` `{終了}` `{時間帯}` `{担当者}` `{担当者定型文}`
（旧版互換で `★0`〜`★3` も置換される）

## 動作確認

`index.html` をブラウザで直接開けば動く（サーバー不要）。
自動テストを走らせる場合はPlaywrightで、PC（1280x900）とスマホ（390x844）の両方を確認する。
確認観点: 複数日選択／一括追加、業態・時間帯・担当者の切替、定型文の編集反映、localStorage保存と復元、
ICSの中身、横スクロールが出ないこと、JSエラーが出ないこと。

## 配置

サーバーのドキュメントルート配下に `index.html` / `manual.html` / `assets/` を同じ階層で置く。
Basic認証を使う場合は `deploy/.htaccess.sample` を参考にする（`AuthUserFile` は配置先の絶対パスに書き換える）。

# TubeVault

個人利用専用の Android アプリ。YouTube を検索し、動画／音声をフォルダ分けして端末に保存し、オフラインで再生する。

> **前提**: 公式ストアには配布できません（Apple / Google の両ストアが、許諾のない第三者サービスからのダウンロード機能を明確に禁止しています）。自分でビルドした APK を自分の端末にサイドロードする用途だけを想定しています。YouTube の利用規約に反する可能性があること、権利者が許諾していないコンテンツを保存・再配布してはいけないことを理解したうえで使ってください。

## できること

- **検索** — `ytsearch` で YouTube を検索。URL の直接貼り付け、YouTube アプリの「共有」からの受け取りにも対応
- **ホーム（アプリ内ブラウザ）** — YouTube をそのまま閲覧し、開いている動画をボタン一つで保存
- **ダウンロード** — 動画（mp4 / 画質選択可）または音声のみ（m4a）。直列キューで進捗表示、キャンセル・再試行あり
- **フォルダ分け** — カテゴリ＝実際のディレクトリ。作成・リネーム（ディレクトリごと）・削除、項目の移動に対応
- **オフライン再生** — Media3 / ExoPlayer。バックグラウンド再生とロック画面操作、レジューム位置の記憶、フォルダ丸ごと連続再生

## 「Please sign in」で失敗するとき

YouTube は匿名の取得要求のほとんどを弾くようになっており、yt-dlp 側の回避策は「本物のブラウザセッションを渡す」ことです。ホームタブの WebView がまさにそれなので、そこの Cookie がページ読み込みごとに Netscape 形式で書き出され、検索・取得の各コマンドに `--cookies` として渡されます。

つまり **一度ホームタブで YouTube を開くだけで改善する**ことが多く、ログインすればさらに通りやすくなります。それでも駄目なら、設定タブで「yt-dlp を更新」、次に「プレイヤークライアント」を `web_embedded` や `tv` などに変更してください。

> Google は WebView からのログインを弾くことがあります。ログインできない場合でも、ログアウト状態の Cookie だけで改善するケースがあります。

## 保存先

```
Android/data/dev.hanada.tubevault/files/TubeVault/
├── 音楽/
│   ├── dQw4w9WgXcQ.m4a
│   └── dQw4w9WgXcQ.jpg
├── 学習/
├── あとで見る/
└── 未分類/
```

ファイル名は動画 ID。表示用のタイトルは Room の DB が持ちます（任意のタイトル文字列に起因するファイル名の問題を全部回避するため）。アプリ専用領域なのでストレージ権限は不要で、アンインストールすれば全部消えます。

## 構成

| 層 | 使っているもの |
| --- | --- |
| UI | Jetpack Compose / Material 3 |
| DB | Room |
| ダウンロード | [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（yt-dlp + ffmpeg を同梱）+ フォアグラウンドサービス |
| 再生 | Media3 ExoPlayer + MediaSessionService |
| DI | 手書きのサービスロケータ（`AppContainer`） |

`minSdk 26` / `targetSdk 35`。ABI は `arm64-v8a` と `armeabi-v7a` の 2 つを別々の APK に分割しています（同梱バイナリが大きいため）。

## ビルド

Android SDK 込みのローカル環境があるなら:

```bash
cd tubevault
./gradlew assembleDebug
# app/build/outputs/apk/debug/ に APK が出ます
```

手元に SDK がない場合は GitHub Actions を使ってください。`claude/youtube-download-app-pkvbjq` ブランチへの push、または Actions タブの **Build YD APK** → *Run workflow* でビルドされます。

## インストール

ビルドが通るたび、`yd-latest` リリースの添付ファイルが最新版に差し替わります。スマホからこのリンクをタップすれば `.apk` がそのまま落ちてきます（Actions の成果物は必ず zip で配信されるため、直接インストールできません）。

- **arm64-v8a**（ここ 8 年ほどの端末はこちら）
  `https://github.com/tatsuyahanada-eng/MusicLyrics/releases/download/yd-latest/yd-arm64-v8a.apk`
- **armeabi-v7a**（32bit 端末用）
  `https://github.com/tatsuyahanada-eng/MusicLyrics/releases/download/yd-latest/yd-armeabi-v7a.apk`

インストール時に「提供元不明のアプリ」の許可、初回起動時に通知の許可（ダウンロード進捗の表示用）を求められます。

> リポジトリが public なので、このリリースも public です。誰でも辿れる場所に置きたくない場合は、リポジトリを private にすればリリースも同時に非公開になります。

デバッグ署名なので、Play ストア経由のアプリとは共存できますが、リリース署名版に上書き更新はできません。

## 動かなくなったら

YouTube 側の仕様変更で取得に失敗するようになるのは日常茶飯事です。**設定タブ → 「yt-dlp を更新」** を実行すると、アプリを再ビルドせずに yt-dlp 本体だけを最新版に差し替えられます。まずこれを試してください。

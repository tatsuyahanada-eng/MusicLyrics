# 音声文字起こしアプリ (VoiceTranscriber)

Android 端末のマイクで話した内容をリアルタイムに文字起こしする Android アプリです。
端末標準の音声認識エンジン（`android.speech.SpeechRecognizer`）を利用しているため、
外部 API キーやサーバーは不要です。

## 主な機能

### 2 つの文字起こしモード（ボタンを切り替え）

1. **押している間だけ文字起こし**
   ボタンを押している間だけマイクが有効になり、指を離すと停止します。
   一言だけサッと入力したいときに便利です。

2. **連続文字起こし**
   一度ボタンを押すと、もう一度押して停止するまで文字起こしを続けます。
   会議やメモの長時間入力向けです（無音で認識が途切れても自動的に再開します）。

### 文字起こし結果の活用

- **コピー**: 結果をクリップボードにコピー
- **保存**: テキストファイル (.txt) として端末に保存（ストレージ権限不要・SAF 利用）
- **メール**: メールアプリに本文として転送（共有先は選択可能）
- **クリア**: 結果を消去
- テキスト欄は手動編集も可能です

## 動作要件

- Android 8.0 (API 26) 以上
- Google 音声認識などの音声認識サービスが有効であること
- 初回起動時にマイク権限の許可が必要です

## APK のダウンロード（GitHub Actions で自動ビルド）

ローカルに Android 開発環境がなくても、GitHub 上で APK を自動ビルドできます。
ワークフロー定義: `.github/workflows/android-build.yml`

### 方法1: ビルド成果物（Artifact）からダウンロード

1. このリポジトリへ push すると、自動でビルドが走ります
   （手動実行する場合は GitHub の **Actions** タブ → *Build Android APK* → **Run workflow**）
2. 完了した実行ページ下部の **Artifacts** から `voice-transcriber-apk` をダウンロード
3. 中の `voice-transcriber.apk` を自分のサーバーにアップロードして配布

### 方法2: Release としてダウンロード用に公開

バージョンタグを push すると、APK が添付された Release が自動作成されます。

```bash
git tag v1.0.0
git push origin v1.0.0
```

→ リポジトリの **Releases** に `voice-transcriber.apk` が公開され、URL で直接配布できます。

> 配布したい APK をサーバーに置き、利用者は Android 端末の Chrome 等でその URL を開いて
> ダウンロード・インストールします（「提供元不明のアプリ」の許可が必要な場合があります）。

> 注: 現在の CI はデバッグ署名の APK を生成します（そのままインストール可能）。
> Google Play へ公開する場合は別途リリース署名（keystore）の設定が必要です。

## ローカルでビルドする場合

Android Studio で `VoiceTranscriber` フォルダを開くか、コマンドラインで:

```bash
cd VoiceTranscriber
./gradlew assembleDebug
```

生成された APK: `app/build/outputs/apk/debug/app-debug.apk`

> 注: ビルドには Android SDK (Platform 35 / Build-Tools) が必要です。
> Android Studio を使う場合は初回同期時に自動で取得されます。

## 技術構成

- Kotlin + Jetpack Compose (Material 3)
- `SpeechRecognizer` による音声認識（部分結果＝リアルタイム表示に対応）
- `ViewModel` + `StateFlow` による状態管理
- ファイル保存は Storage Access Framework（`ACTION_CREATE_DOCUMENT`）

## 主なソース

| ファイル | 役割 |
| --- | --- |
| `MainActivity.kt` | Compose UI（モード切替ボタン・結果表示・エクスポート） |
| `TranscriptionViewModel.kt` | 画面状態とロジックの保持 |
| `SpeechRecognizerManager.kt` | 音声認識のラッパー（押下中／連続モード） |

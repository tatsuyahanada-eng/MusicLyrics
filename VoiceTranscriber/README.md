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

## ビルド方法

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

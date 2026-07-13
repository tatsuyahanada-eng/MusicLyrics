# 無線チャンネル変更APP — iOS版（SwiftUI + ARKit）

Android版と同じ4機能（ID計算 / 距離 / 作図 / 結果）をiPhone向けにSwiftUIで実装したものです。
**無料のApple ID＋個人利用**を前提にしています（App Store非公開）。

> ⚠️ このコードはLinux環境で作成しており、iOSの実ビルドは行っていません。
> 初回ビルド時に軽微な調整が必要になる場合があります。何か出たら内容を送ってください。

---

## 必要なもの
- **Mac**（Xcode 15 以降）… iOSアプリのビルドはMacでのみ可能です
- **iPhone**（iOS 16 以降）とLightning/USB-Cケーブル
- **無料のApple ID**（既にお持ちのもの）
- 距離測定を使う端末はARKit対応（iPhone 6s以降はほぼ対応）

---

## 手順A：Xcodeで新規プロジェクトを作って取り込む（追加ツール不要・推奨）

1. **Xcode を開く** → `File > New > Project…`
2. **iOS > App** を選択して `Next`
   - Product Name: `MusiWireless`
   - Interface: **SwiftUI**、Language: **Swift**
   - `Next` → 保存場所を選んで作成
3. 自動生成された `ContentView.swift` と `〜App.swift` は**削除**します（ゴミ箱へ）。
4. この `ios/MusiWireless/` にある **`.swift` ファイルをすべて** Xcodeのプロジェクトへドラッグ＆ドロップ
   （`Copy items if needed` にチェック、`Add to targets: MusiWireless` にチェック）
   - `MusiWirelessApp.swift` / `IdData.swift` / `AppStore.swift`
   - `IdView.swift` / `DistanceView.swift` / `DrawView.swift` / `ResultView.swift`
5. **カメラ利用の説明を設定**（距離測定で必要）
   - プロジェクト設定 > `TARGETS: MusiWireless` > **Info** タブ
   - `+` で **Privacy - Camera Usage Description** を追加し、値に「距離測定と写真の取り込みでカメラを使用します。」
6. **署名（無料Apple ID）**
   - `TARGETS: MusiWireless` > **Signing & Capabilities**
   - `Automatically manage signing` にチェック
   - **Team** に自分のApple IDを追加（`Add an Account…` からApple IDでサインイン → Personal Team）
   - **Bundle Identifier** を世界で一意になるよう変更（例：`com.あなたの名前.musiwireless`）
7. **iPhoneを接続** → 画面上部の実行先を自分のiPhoneに変更 → **▶︎（Run）**
8. 初回はiPhone側で信頼設定：
   - iPhone `設定 > 一般 > VPNとデバイス管理 > (自分のApple ID) > 信頼`
   - 再度Xcodeから ▶︎ で起動

### 無料Apple IDの制限
- アプリは **7日で期限切れ** します。切れたら Xcode から再度 ▶︎ すればまた使えます（データは端末に残ります）。
- 1年間有効にしたい／ケーブル無しで配りたい場合は Apple Developer Program（年99ドル）が必要です。

---

## 手順B：XcodeGen で自動生成（コマンドに慣れている場合）

```bash
brew install xcodegen        # 未インストールなら
cd ios
xcodegen generate            # MusiWireless.xcodeproj が生成される
open MusiWireless.xcodeproj
```
あとは手順Aの 6〜8（署名・実行）と同じです。`project.yml` にカメラ権限・Bundle IDを設定済みです。

---

## 実装状況（Android版との対応）
| 機能 | iOS版の状態 |
|---|---|
| ID計算 | ◎ 対象機器・共通番号・10桁ID表・タップでコピー＆「変更後システムID」へ反映 |
| 距離測定 | ◯ ARKitで始点→終点の距離を計測、m/cm、記録一覧、名称＋数値の修正、コピー |
| 作図 | ◯ ペン（色）・アイテム配置・削除・取消・全消去・画像(PNG)共有（Android版より簡易） |
| 結果入力 | ◎ 変更後システムID/店舗名等・場所×Chグリッド・48ch既定/列の非表示・CSV・メール添付送信 |
| 初期化 | ◎ 右上「初期化」で全データ消去 |
| データ保存 | ◎ 端末内（UserDefaults）に自動保存 |

### iOS版で未対応・簡易な点（必要なら拡張します）
- 作図：図形（正方形/丸ほか）、点線/矢印/文字、写真背景、PDF出力、移動 は未実装（ペンとアイテムのみ）
- 距離のメール添付に作図PDFは含めていません（作図は「作図」タブの共有ボタンから個別に共有）
- 日付/時間は今のところテキスト入力（カレンダー/時刻ピッカーは今後追加可）

# FXシグナル (fx_signal_app)

ドル円をはじめとする複数の通貨ペアのトレンドをテクニカル指標で監視し、
売買シグナルが出た瞬間に **プッシュ通知** するAndroidアプリ（Flutter製）。

> ⚠️ **重要**: 本アプリのシグナルは過去データに基づく**参考情報**です。
> 未来の値動きや利益を保証するものではなく、**投資助言ではありません**。
> 売買の判断は必ずご自身の責任で行ってください。

---

## 特長

- 複数通貨ペア対応（USD/JPY, EUR/JPY, GBP/JPY, EUR/USD など）
- テクニカル指標による合議判定（SMAクロス + MACD + RSI + 価格位置）
- トレンド表示（↑上昇 / ↓下降 / →レンジ）
- バックグラウンド自動監視（WorkManager, 15分〜）
- 根拠つきローカル通知（例: 「USD/JPY 買いシグナル ★★ / 根拠: ゴールデンクロス, MACD強気」）
- シグナル履歴の保存・一覧
- **完全無料・サーバー不要**（端末内で計算、ランニングコスト0円）

## 必要なもの

1. **Flutter SDK**（3.x）と Android Studio
2. **Twelve Data の無料APIキー**
   - https://twelvedata.com/ で無料登録 → APIキーをコピー
   - 無料枠: 8 req/分・800 credit/日（4ペア×15分監視で約384 credit/日に収まります）

## セットアップ / 実行

```bash
cd fx_signal_app
flutter pub get
flutter run            # 実機 or エミュレータで起動
```

APKを作って自分の端末に入れる場合:

```bash
flutter build apk --release
# 生成物: build/app/outputs/flutter-apk/app-release.apk
```

初回起動後:
1. 「設定」タブを開く
2. Twelve Data のAPIキーを入力
3. 監視する通貨ペア・監視周期・通知を設定して「保存」
4. 通知権限を許可（Android 13+）

## 仕組み（シグナル判定）

`lib/domain/signal/signal_engine.dart` が中核。各足の終値から指標を計算し、
**「転換イベント」が起きた足で、確認条件が2つ以上そろったとき**にシグナルを出します。

- 買い: ゴールデンクロス or MACD上抜け（イベント）＋ MACD強気 / 価格が長期線上 / RSI>50（確認）
- 売り: デッドクロス or MACD下抜け（イベント）＋ MACD弱気 / 価格が長期線下 / RSI<50（確認）

強度（★1〜3）は一致した根拠の数。同一ペア・同一足の重複通知は抑制します。

## ディレクトリ構成

```
lib/
  domain/        指標計算・シグナル判定・エンティティ（純粋ロジック）
  data/          Twelve Data API / 設定 / 履歴の保存
  application/   Riverpod プロバイダ・監視サービス
  background/    通知・WorkManager
  presentation/  画面（ダッシュボード/詳細/履歴/設定）
test/            指標・シグナル判定の単体テスト
```

## 制限・注意

- WorkManager の最小周期は15分。これより短いリアルタイム監視は対象外（前面では手動更新可）。
- 端末の省電力(Doze)により通知が遅延・スキップされることがあります。
- APIキーは端末内（shared_preferences）にのみ保存し、外部送信はしません。
- 設計の詳細は `../docs/fx-signal-app-design.md` を参照。

## 今後の拡張候補

- 複数時間足の合議（15分 + 1時間 + 日足）
- 経済指標カレンダー連携（指標発表前後はシグナル抑制）
- バックテスト（過去データでの勝率可視化）
- iOS対応 / AIによる相場サマリー

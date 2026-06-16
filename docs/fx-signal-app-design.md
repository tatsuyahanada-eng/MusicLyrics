# FXシグナル通知アプリ 詳細設計書

> バージョン: 1.0
> 作成日: 2026-06-16
> 対象プラットフォーム: Android（Flutter / 将来的にiOS展開可能）

---

## 1. 目的とコンセプト

ドル円をはじめとする複数の通貨ペアのトレンドを常時監視し、テクニカル指標に基づいて
**「今、売り／買いのシグナルが出た」** タイミングをリアルタイムでプッシュ通知するアプリ。

### 1.1 重要な前提（必読）

- 本アプリは **未来の値動きを予言するものではない**。「何月何日の何時に売り」を事前に確定することは原理的に不可能。
- 本アプリが提供するのは **「監視している条件が成立した瞬間に通知する」** 機能。
- 出すのは **シグナル（参考情報）** であり、**投資助言ではない**。最終判断は必ず利用者本人が行う。
- 詳細は「12. リスク・免責・コンプライアンス」を参照。

### 1.2 解決したい課題

| 課題 | 本アプリの解決策 |
|---|---|
| チャートを常時見ていられない | バックグラウンドで自動監視し通知 |
| ドル円以外の好機を見逃す | 複数通貨ペアを同時監視 |
| なぜ今売り/買いなのか根拠が欲しい | どの指標がどう出たかを通知に併記 |
| 運用コストをかけたくない | 端末内完結・サーバー不要で月額0円 |

---

## 2. スコープ

### 2.1 MVP（本設計書の対象）

- 複数通貨ペアの監視（USD/JPY, EUR/JPY, EUR/USD, GBP/JPY など）
- テクニカル指標による売買シグナル判定（移動平均クロス, RSI, MACD）
- トレンド判定（上昇 / 下降 / レンジ）
- バックグラウンド定期監視（WorkManager）
- ローカルプッシュ通知（根拠つき）
- シグナル履歴の保存・一覧

### 2.2 非スコープ（MVPでは作らない）

- 自動売買・実発注（証券会社API連携）
- 未来予測・AI予測
- アカウント／クラウド同期
- 経済指標カレンダー連携（Phase 4の将来拡張）

---

## 3. 技術スタック

| 領域 | 採用技術 | 無料の根拠 |
|---|---|---|
| フレームワーク | Flutter (Dart) | OSS・無料 |
| 為替データ | Twelve Data 無料枠 | 8 req/分・800 credit/日、intraday対応、複数通貨ペア対応 |
| 為替データ予備 | Frankfurter API（ECB日次） | リクエスト無制限・無料（intradayは非対応のため日次バックアップ用途） |
| 判定ロジック | 端末内計算（Dart実装） | サーバー不要 |
| 定期実行 | workmanager パッケージ（Android WorkManager） | OS標準・無料 |
| 通知 | flutter_local_notifications | サーバー不要・無料 |
| ローカル保存 | shared_preferences / drift(SQLite) | OSS・無料 |
| 状態管理 | Riverpod | OSS・無料 |
| グラフ描画 | fl_chart | OSS・無料 |

### 3.1 主要パッケージ一覧（pubspec想定）

```yaml
dependencies:
  flutter_riverpod: ^2.x
  http: ^1.x
  workmanager: ^0.5.x
  flutter_local_notifications: ^17.x
  fl_chart: ^0.6x
  shared_preferences: ^2.x
  drift: ^2.x            # 履歴をSQLiteで保存する場合
  intl: ^0.19.x
```

---

## 4. アーキテクチャ

### 4.1 方針

- **端末内完結（サーバーレス）**。バックエンドを持たず運用コストを0円に保つ。
- レイヤードアーキテクチャ + Riverpod による依存注入。

```
┌─────────────────────────────────────────┐
│  Presentation 層 (UI / Widget / Screen)  │
│   - ダッシュボード / 詳細 / 設定 / 履歴   │
├─────────────────────────────────────────┤
│  Application 層 (Riverpod Provider)       │
│   - 監視オーケストレーション              │
│   - シグナル生成サービス                  │
├─────────────────────────────────────────┤
│  Domain 層                                │
│   - 指標計算 (MA/RSI/MACD)                │
│   - トレンド判定 / シグナルルール         │
│   - エンティティ (Candle, Signal, Pair)   │
├─────────────────────────────────────────┤
│  Data 層                                  │
│   - MarketDataRepository (Twelve Data)    │
│   - SignalHistoryRepository (SQLite)      │
│   - SettingsRepository (Prefs)            │
└─────────────────────────────────────────┘
       ▲                          ▲
       │                          │
 [Twelve Data API]      [WorkManager 定期タスク]
                               │
                        [Local Notification]
```

### 4.2 バックグラウンド監視フロー

```
WorkManager (例: 15分周期で起動)
        │
        ▼
  監視対象ペアをループ
        │
        ▼
  MarketDataRepository.fetchCandles(pair, interval)   ← Twelve Data
        │
        ▼
  指標計算 (MA / RSI / MACD) + トレンド判定
        │
        ▼
  SignalEngine.evaluate(...) → Signal? (なし/買い/売り)
        │
        ├─ シグナルあり & 前回と異なる → 通知 + 履歴保存
        └─ シグtrue なし or 重複 → 何もしない
```

---

## 5. データソース設計（Twelve Data）

### 5.1 利用エンドポイント

| 用途 | エンドポイント | 例 |
|---|---|---|
| ローソク足取得 | `GET /time_series` | `?symbol=USD/JPY&interval=15min&outputsize=100&apikey=KEY` |
| 現在値取得 | `GET /price` | `?symbol=USD/JPY&apikey=KEY` |

レスポンス例（time_series）:
```json
{
  "values": [
    {"datetime":"2026-06-16 14:30:00","open":"157.10","high":"157.25","low":"157.05","close":"157.20"},
    ...
  ]
}
```

### 5.2 無料枠を守る設計（重要）

- 無料枠は **8 req/分・800 credit/日**。`time_series` 1回 = 1 credit。
- 監視ペア4つ × 15分周期 = 1日あたり 4 × 96 = **384 credit**。800以内に収まる。
- 監視ペアを増やす／周期を短くする場合は credit を再計算（設定画面で上限警告）。
- レート制限対策:
  - ペアごとの取得は順次（並列で叩かない）、各リクエスト間に最小間隔を確保。
  - 429（レート超過）時は指数バックオフでスキップ、次周期に持ち越し。
  - APIキーはユーザー自身が取得・入力（設定画面）。キーはアプリにハードコードしない。

### 5.3 フォールバック

- Twelve Data 障害／枠超過時は Frankfurter（ECB日次）で当日の方向感のみ補助表示。intradayシグナルは出さない。

---

## 6. 判定アルゴリズム

### 6.1 計算する指標

| 指標 | パラメータ（デフォルト） | 用途 |
|---|---|---|
| SMA 短期 | 期間 9 | クロス判定の短期線 |
| SMA 長期 | 期間 21 | クロス判定の長期線 |
| RSI | 期間 14 | 買われ過ぎ/売られ過ぎ |
| MACD | 12, 26, 9 | モメンタム転換 |

すべてローソク足の終値（close）から端末内で計算する。計算式:

- **SMA(n)** = 直近n本の終値の単純平均
- **RSI(14)** = 100 − 100 / (1 + 平均上昇幅 / 平均下落幅)
- **MACD** = EMA(12) − EMA(26)、**シグナル線** = MACDのEMA(9)、**ヒストグラム** = MACD − シグナル線

### 6.2 トレンド判定

| 条件 | 判定 |
|---|---|
| 終値 > SMA21 かつ SMA9 > SMA21 | 上昇トレンド |
| 終値 < SMA21 かつ SMA9 < SMA21 | 下降トレンド |
| 上記以外 | レンジ |

### 6.3 シグナル生成ルール（MVP）

シグナルは **複数指標の合議** で生成し、誤シグナルを減らす。

**買いシグナル（BUY）** — 以下のうち2つ以上を満たす:
1. ゴールデンクロス: SMA9 が SMA21 を下から上に抜けた
2. MACD がシグナル線を下から上に抜けた（ヒストグラムが負→正）
3. RSI が 30 を下から上に回復（売られ過ぎからの反発）

**売りシグナル（SELL）** — 以下のうち2つ以上を満たす:
1. デッドクロス: SMA9 が SMA21 を上から下に抜けた
2. MACD がシグナル線を上から下に抜けた（ヒストグラムが正→負）
3. RSI が 70 を上から下に下落（買われ過ぎからの反落）

満たした指標数を **シグナル強度（1〜3 / 通知では★で表現）** とする。

### 6.4 重複・チャタリング防止

- 同一ペア・同一方向のシグナルは、状態が変わるまで再通知しない（直近シグナルを保持して比較）。
- 1本のローソク足確定ごとに1回のみ評価（足の datetime で判定）。

### 6.5 擬似コード

```dart
Signal? evaluate(List<Candle> candles, IndicatorConfig cfg, Signal? last) {
  final sma9  = sma(candles, cfg.smaShort);
  final sma21 = sma(candles, cfg.smaLong);
  final rsi   = rsiSeries(candles, cfg.rsiPeriod);
  final macd  = macd(candles, 12, 26, 9);

  int buyScore = 0, sellScore = 0;
  if (crossedUp(sma9, sma21))         buyScore++;
  if (crossedUp(macd.line, macd.sig)) buyScore++;
  if (crossedUp(rsi, const Threshold(30))) buyScore++;

  if (crossedDown(sma9, sma21))         sellScore++;
  if (crossedDown(macd.line, macd.sig)) sellScore++;
  if (crossedDown(rsi, const Threshold(70))) sellScore++;

  final candleTime = candles.last.datetime;
  if (buyScore >= 2 && !isDuplicate(last, Side.buy, candleTime)) {
    return Signal(side: Side.buy, strength: buyScore, reasons: ..., time: candleTime);
  }
  if (sellScore >= 2 && !isDuplicate(last, Side.sell, candleTime)) {
    return Signal(side: Side.sell, strength: sellScore, reasons: ..., time: candleTime);
  }
  return null;
}
```

---

## 7. データモデル

```dart
class Candle {
  final DateTime datetime;
  final double open, high, low, close;
}

enum Side { buy, sell }

class Signal {
  final String pair;          // "USD/JPY"
  final Side side;            // buy / sell
  final int strength;         // 1..3
  final List<String> reasons; // ["ゴールデンクロス","MACD上抜け"]
  final double price;         // 発生時価格
  final DateTime time;        // 足の確定時刻
}

class WatchPair {
  final String symbol;        // "USD/JPY"
  final String interval;      // "15min"
  final bool enabled;
}

class IndicatorConfig {
  final int smaShort, smaLong, rsiPeriod;
  // MACD は 12/26/9 固定（MVP）
}

class AppSettings {
  final String apiKey;
  final int pollMinutes;      // 監視周期（既定15分）
  final bool notifyEnabled;
  final List<WatchPair> pairs;
}
```

履歴は SQLite(drift) の `signals` テーブルに保存（pair, side, strength, reasons(JSON), price, time）。

---

## 8. 画面設計

| 画面 | 内容 |
|---|---|
| ① ダッシュボード | 監視中ペアのカード一覧。各カードに現在値・トレンド（↑/↓/→）・最新シグナルバッジ |
| ② ペア詳細 | ローソク足チャート(fl_chart) + SMA/RSI/MACD表示 + 直近シグナルと根拠 |
| ③ シグナル履歴 | 時系列でシグナルを一覧（フィルタ: ペア/方向） |
| ④ 設定 | APIキー入力 / 監視ペアの追加・削除 / 監視周期 / 通知ON-OFF / 指標パラメータ |

### 8.1 画面遷移

```
[ダッシュボード] ──tap カード──> [ペア詳細]
       │
       ├──tab──> [シグナル履歴]
       └──tab──> [設定]
```

### 8.2 通知の表示例

```
タイトル: USD/JPY 買いシグナル ★★
本文:    14:30 / 157.20  根拠: ゴールデンクロス, MACD上抜け
```
タップでアプリの該当ペア詳細を開く（ペイロードに symbol を載せる）。

---

## 9. 通知・バックグラウンド設計

- `workmanager` で `Periodic Task`（Android最小周期15分）を登録。
- バックグラウンド isolate 内で: データ取得 → 評価 → 通知 → 履歴保存。
- 端末再起動後もタスクが復活するよう `RESCHEDULE` 設定。
- Android 13+ は通知権限（`POST_NOTIFICATIONS`）を初回起動時にリクエスト。
- 電池最適化の影響を説明する案内（必要に応じて最適化除外を促す）。
- 通知チャンネル: `fx_signals`（重要度 High）。

### 9.1 制約・注意

- WorkManagerの最小周期は15分。これより短い監視はMVP対象外（前面起動中のみ手動更新で対応）。
- Doze/省電力で遅延する可能性あり → 通知に「遅延の可能性」を設計上許容（売買確定の責任は負わない前提）。

---

## 10. ディレクトリ構成（案）

```
lib/
  main.dart
  app.dart
  core/
    constants.dart
    result.dart
  data/
    market/
      twelve_data_api.dart
      market_repository.dart
    history/
      signal_dao.dart
      history_repository.dart
    settings/
      settings_repository.dart
  domain/
    entities/ (candle.dart, signal.dart, watch_pair.dart)
    indicators/ (sma.dart, rsi.dart, macd.dart, cross.dart)
    signal/ (signal_engine.dart, trend.dart)
  application/
    providers.dart
    monitor_service.dart
  background/
    worker.dart            # WorkManager callbackDispatcher
    notifier.dart          # ローカル通知ラッパ
  presentation/
    dashboard/ pair_detail/ history/ settings/
test/
  indicators_test.dart     # SMA/RSI/MACDの単体テスト（既知値で検証）
  signal_engine_test.dart
```

---

## 11. 開発フェーズ・マイルストーン

| Phase | 内容 | 完了条件 |
|---|---|---|
| 0 | プロジェクト雛形 + pubspec + ディレクトリ | `flutter run` で起動 |
| 1 | 指標計算（SMA/RSI/MACD）+ 単体テスト | テストグリーン |
| 2 | Twelve Data 連携 + 1ペアの詳細画面（チャート） | 実データ表示 |
| 3 | SignalEngine + ダッシュボード（複数ペア） | シグナルが画面に出る |
| 4 | WorkManager + ローカル通知 + 履歴保存 | バックグラウンド通知が届く |
| 5 | 設定画面（APIキー/ペア/周期/パラメータ） | 設定が永続化 |
| 6 | 仕上げ（権限/省電力案内/エラー処理/免責表示） | 実機で安定動作 |

---

## 12. リスク・免責・コンプライアンス

- **投資助言ではない**: 本アプリは情報提供ツール。日本では投資助言・代理業は登録制であり、本アプリは個別銘柄の売買推奨を「助言」として提供しない設計とする。アプリ内・初回起動時に免責事項を明示し同意を得る。
- **自己責任の明記**: 「シグナルは過去データに基づく参考情報であり、利益・的中を保証しない」旨を常時表示。
- **データ提供元の規約遵守**: Twelve Data の利用規約・クレジット表記要件を確認し遵守。
- **遅延・欠損**: 無料データ／省電力により遅延・欠損が起こり得ることを免責。
- **APIキー管理**: ユーザー自身のキーを端末内にのみ保存（クラウド送信しない）。

---

## 13. コスト試算

| 項目 | 月額 |
|---|---|
| Twelve Data 無料枠 | 0円 |
| WorkManager / 通知 / 端末内計算 | 0円 |
| サーバー | なし（0円） |
| Google Play 公開（任意） | 初回のみ登録料 $25（公開しない＝自分の端末で使うだけなら不要） |

**運用ランニングコスト: 0円**（Play公開する場合のみ初回 $25）。

---

## 14. 将来拡張（Phase 4以降）

- 経済指標カレンダー連携（指標発表前後はシグナル抑制 or 警告）
- 複数時間足の合議（15分 + 1時間 + 日足）
- バックテスト機能（過去データでルールの勝率を可視化）
- AIによる相場サマリー（Claude API 等でニュース要約・地合いコメント）
- iOS対応（Flutterのため追加コスト小）
- クラウド同期（FCMでサーバー集約通知へ移行する選択肢）

---

## 付録A: シグナル判定の検証方針

- 指標計算は**既知の入力に対する期待値**で単体テスト（例: 一定上昇データでRSI→100付近）。
- SignalEngine はクロス発生パターンを合成データで再現し、BUY/SELL/重複抑制を検証。
- 実運用前に過去データでドライラン（誤検知の頻度を確認）。

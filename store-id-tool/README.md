# 店舗IDツール (store-id-tool)

MONSTERA / Relier の店舗 ID（9 桁 + CD）を自動計算する Android アプリ。
元データ（Relier「ﾃﾞﾆｰｽﾞ」シート、MONSTERA「Sheet1」）の計算ロジックを再現しています。

## 計算ロジック

9 桁の並び（Excel の F〜N 列）と CD（O 列）:

| 部分 | 列 | 内容 | 入力/固定 |
|------|----|------|-----------|
| 規格 | F | MONSTERA=4 / Relier=5 | ブランドで固定 |
| Ch設定値 | G,H,I | ch 番号ごとの 3 桁コード | 固定（表） |
| 店舗番号 | J,K,L,M,N | 中央の「67200」 | **ユーザー入力（5 桁）**。全 ch 共通 |
| CD | O | `RIGHTB(SUM(F:N),1)` = 9 桁合計の末尾 1 桁 | 自動 |

例: Relier / ch1 / 店舗番号 67200
→ `5 1 0 1 6 7 2 0 0` 合計 22 → **CD = 2**

ch → Ch設定値 の対応表（両ブランド共通）:
`1→101, 6→106, 13→113, 36→001, 40→002, 44→003, 48→004, 100→009, 120→014, 124→015`

## 使い方（アプリ）

1. ブランド（MONSTERA / Relier）を選ぶ
2. 店名（空欄で可）を入力
3. 店舗番号 5 桁を入力すると、全 ch の ID と CD が表で自動計算される
4. 行をタップすると完成 ID をクリップボードにコピー

## APK の入手

`claude/**` ブランチへ push すると GitHub Actions が debug APK をビルドし、
リリース `store-id-tool-latest` に `app-debug.apk` を添付します。
端末のブラウザからその APK を直接ダウンロードしてインストールしてください。

## ローカルビルド

```
cd store-id-tool
./gradlew assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

## 今後の予定

- 部屋の中での始点→終点の距離測定機能（GPS 不使用）を同じアプリに追加予定

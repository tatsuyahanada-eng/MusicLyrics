# ネット調査ツール (NetDiag) — Android

個人・中小企業向けの **ネットワーク調査 / トラブルシュート** アプリです。
ネイティブ Kotlin + Jetpack Compose 製。**非root端末**で、合法・安全な範囲の
診断機能のみを実装しています。

> ⚠️ **利用上の注意（重要）**
> このアプリは **自分が管理する／許可されたネットワーク** での調査・診断にのみ
> 使用してください。他人のネットワーク・通信を無断で調査・傍受する行為は、
> 不正アクセス禁止法・電波法・電気通信事業法などに抵触するおそれがあります。
> 本アプリはパスワード解析や他端末の通信傍受は **意図的に実装していません**。

---

## 機能一覧（v1）

### 1. ネットワーク可視化（「何がどこにあるか」）
- 自分のIP / サブネット / ゲートウェイ / DNS の表示
- サブネット一括スキャン（ICMP + TCP プローブで生存確認）
- 逆引きDNS・mDNS(Bonjour) によるホスト名取得
- ARPキャッシュからのMAC取得（※Android 10+ では取得できないことが多い）
- 端末ごとのポートスキャン（TCP connect、主要ポート＋サービス名表示）

### 2. 導通・経路診断（「どこで途切れているか」）
- 高度なPing：連続送信・パケットロス率・RTT(最小/平均/最大)・**ジッター**
- Traceroute：TTLを増やしながら経路上のルーターを可視化
- DNS疎通チェック：システムDNSと **任意のDNSサーバー** を直接比較

### 3. Wi-Fi / 無線調査（「電波」のトラブル）
- 周辺APの一覧：SSID / BSSID / RSSI(dBm) / 信号品質バー
- バンド・チャンネル・チャンネル幅の表示
- **チャンネル混雑度**の可視化（バンド×チャンネル別AP数）
- セキュリティ規格の判定：WPA3 / WPA2 / WPA / **WEP(脆弱)** / オープン をリスク色分け
- 接続中ネットワークのRSSIをリアルタイム表示

### 4. トラフィック解析（「誰が帯域を使っているか」）
- 端末全体の上り/下りスループットをリアルタイム表示（暴走通信の早期発見）
- アプリ別 Wi-Fi 使用量ランキング（「使用状況へのアクセス」許可が必要）

---

## ビルド方法

Android SDK が必要です（このリポジトリにはSDKは含まれません）。

### Android Studio（推奨）
1. Android Studio で `android-netdiag/` フォルダを開く
2. Gradle Sync が完了したら **Run ▶** で実機へインストール
   （エミュレータではWi-Fi/サブネットスキャンは正しく動作しません。実機推奨）

### コマンドライン
```bash
cd android-netdiag
# 署名なしデバッグAPKをビルド
./gradlew assembleDebug
# 生成物: app/build/outputs/apk/debug/app-debug.apk
```
APKを端末に転送してインストール（提供元不明アプリの許可が必要）するか、
`./gradlew installDebug` で接続済み端末へ直接インストールできます。

- 動作環境: **Android 8.0 (API 26) 以上** / compileSdk 35
- Gradle Wrapper 同梱（8.14.3） / AGP 8.7.3 / Kotlin 2.0.21

---

## 必要な権限と理由

| 権限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Ping・スキャン・ネットワーク情報取得 |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Wi-Fiスキャンの実行と結果取得 |
| `ACCESS_FINE_LOCATION`（実行時許可） | **周辺APのスキャン結果取得（Android仕様で必須）** |
| `NEARBY_WIFI_DEVICES` | Android 13+ でのWi-Fiスキャン代替ゲート |
| `PACKAGE_USAGE_STATS`（設定で許可） | アプリ別データ使用量の集計 |

位置情報は周辺Wi-Fi一覧の取得にAndroidが要求するもので、位置の送信・記録は行いません。

---

## 非root端末での制限（設計上の割り切り）

| 項目 | 状況 |
|---|---|
| 他端末のMAC総ざらい | ❌ 不可（ARPは10+で読めない）。逆引き/mDNS/DHCPで補完 |
| Traceroute | △ `ping` のTTL操作で実装。環境により一部ホップが `* * *` |
| パケットスニファ（プロトコル割合） | ⏳ v1では未実装。下記ロードマップ参照 |
| Wi-Fiパスワード解析 | 🚫 **非対応**（違法・非倫理のため実装しない） |

### スニファー（プロトコル解析）について
非root端末で「他端末を含む通信」を傍受することは技術的に不可能であり、かつ
他者ネットワークでは違法です。自端末の通信を `VpnService` で取得して
HTTP/HTTPS/DNS/NTP などの割合を出す方式は **フェーズ2** として設計予定です
（接続を切らない転送実装が必要なため、v1では安全な統計ベースに留めています）。
v1 ではトラフィック面を **スループット監視＋アプリ別使用量** で代替しています。

---

## 構成

```
android-netdiag/
├─ app/src/main/AndroidManifest.xml
├─ app/src/main/java/com/netdiag/
│  ├─ MainActivity.kt
│  ├─ core/
│  │  ├─ net/      … NetworkInfoProvider, HostDiscovery, PortScanner,
│  │  │              PingTool, Traceroute, DnsTool, ArpTable, NetUtils, mdns/
│  │  ├─ wifi/     … WifiSurvey（RSSI・チャンネル・暗号化方式判定）
│  │  └─ traffic/  … TrafficMonitor（スループット・アプリ別使用量）
│  └─ ui/          … Compose 画面（可視化 / 診断 / Wi-Fi / 通信量）
└─ build.gradle.kts ほか Gradle 設定
```

## ロードマップ
- [ ] フェーズ2: `VpnService` ベースの自端末プロトコル解析（接続維持つき）
- [ ] スキャン結果・診断レポートの保存／共有（CSV・テキスト）
- [ ] DHCPリース照会によるMAC/ベンダー補完
- [ ] OUIデータベースによるMACベンダー名表示

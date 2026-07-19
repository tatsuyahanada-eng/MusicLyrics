# セットアップ手順

parking-finder スキルを動かすには、以下の 2 つを用意する。

---

## 1. Google Maps API キーの取得（← どこで取るか）

Google Maps のAPIキーは **Google Cloud Console の「Google Maps Platform」画面**で発行する。
取得場所（URL）と手順は次のとおり。

### 手順

1. **Google Cloud Console にアクセスしてログイン**
   👉 https://console.cloud.google.com/
   （このスキルの利用者本人の Google アカウントでログインする）

2. **プロジェクトを作成（または選択）**
   画面上部のプロジェクト選択メニュー →「新しいプロジェクト」→ 名前を付けて作成。
   👉 直接リンク: https://console.cloud.google.com/projectcreate

3. **請求（Billing）を有効化する**
   👉 https://console.cloud.google.com/billing
   クレジットカードの登録が必要。ただし **月間の無料利用枠**があり、個人利用の少量なら実質無料の範囲。
   （請求を有効化しないと Maps 系 API はキーがあってもエラーになる）

4. **必要な 3 つの API を「有効化」する**
   👉 API ライブラリ: https://console.cloud.google.com/apis/library
   検索して、それぞれ「有効にする」を押す。個別リンクは以下:

   | API 名 | 用途 | 直接リンク |
   |---|---|---|
   | **Geocoding API** | 住所→緯度経度の変換 | https://console.cloud.google.com/apis/library/geocoding-backend.googleapis.com |
   | **Places API (New)** | 近くの駐車場の検索 | https://console.cloud.google.com/apis/library/places.googleapis.com |
   | **Distance Matrix API** | 距離・所要時間の計算 | https://console.cloud.google.com/apis/library/distance-matrix-backend.googleapis.com |

   ※「Places API」と「Places API (New)」は別物。**必ず "(New)" の付いた `places.googleapis.com` を有効化**すること。

5. **APIキーを発行する（← ここがキーの取得場所）**
   👉 認証情報（Credentials）ページ: https://console.cloud.google.com/apis/credentials
   「＋認証情報を作成」→「APIキー」を選ぶと、キー文字列（`AIza...` で始まる）が表示される。これをコピーする。
   （Google Maps Platform 専用画面から入る場合はこちら 👉 https://console.cloud.google.com/google/maps-apis/credentials ）

6. **（推奨）キーを制限する**
   発行直後のキーは無制限で危険。上記 認証情報ページでキーを開き、
   「API の制限」→ 上の 3 つの API だけに限定する。アプリケーションの制限も可能なら設定する。

### 発行したキーを環境変数に設定する

スクリプトは環境変数 `GOOGLE_MAPS_API_KEY` からキーを読む。

- **Claude Code on the web を使っている場合**:
  環境（Environment）の設定画面で環境変数 `GOOGLE_MAPS_API_KEY` にキーを登録する。
  設定方法は公式ドキュメント参照 👉 https://code.claude.com/docs/en/claude-code-on-the-web

- **ローカルで動かす場合**:
  ```bash
  export GOOGLE_MAPS_API_KEY="AIza...（発行したキー）"
  ```
  シェルの設定ファイル（`~/.bashrc` / `~/.zshrc` 等）に書いておくと毎回不要。

> ⚠️ キーはパスワードと同じ。GitHub にコミットしたり、コードに直書きしたりしないこと。

### 動作確認

キー設定後、以下で動けば準備完了:
```bash
python3 .claude/skills/parking-finder/scripts/find_parking.py --address "東京駅" --radius 500 --max 3
```
`REQUEST_DENIED` や `API key not valid` が出る場合はキーが無効、
`... is not enabled` が出る場合はその API が未有効化なので、手順 4 を見直す。

---

## 2. Google カレンダーの接続

予定の**読み取り**と、結果を予定に**追記（書き込み）**するために、Google カレンダーのコネクタを接続する。

1. claude.ai の **設定 → コネクタ**（Connectors）を開く。
2. Google カレンダー系のコネクタを接続し、アクセスを許可する。
3. ⚠️ 予定への追記には**編集（書き込み）権限**が必要。接続時に読み取りだけでなく**予定の更新まで許可**すること。読み取り専用だと手順⑥の追記ができない。
4. このチャット（セッション）でコネクタが有効になっていることを確認する。接続済みでもチャットで無効だと、ツールが呼び出せない。

接続後、`ToolSearch` で `google calendar events` 等を検索すると、予定の一覧取得・取得・更新に使えるツールが現れる。

---

## つまずいたときの対処

| 症状 | 原因 | 対処 |
|---|---|---|
| `GOOGLE_MAPS_API_KEY is not set` | 環境変数が未設定 | 上記「1」の環境変数設定 |
| `REQUEST_DENIED` / `API key not valid` | キーが無効・制限で弾かれている | キーの値とAPI制限を確認 |
| `This API ... is not enabled` | 該当 API が未有効化 | 手順 4 で該当 API を有効化 |
| `You must enable Billing` | 請求未設定 | 手順 3 |
| カレンダー系ツールが出てこない | コネクタ未接続 or チャットで無効 | 上記「2」 |
| 予定を更新できない | 書き込み権限なし | コネクタを編集権限付きで接続し直す |

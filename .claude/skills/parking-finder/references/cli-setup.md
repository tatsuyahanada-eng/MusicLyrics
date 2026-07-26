# CLI 版セットアップ & マルチデバイス運用ガイド

parking-finder を「PC(CLI)」と「スマホ(アプリ/ブラウザ)」の両方から定期実行するための手順。

---

## 大前提:どこで動くか

このスキルは **Claude Code**（リポジトリ内スクリプト実行＋環境変数＋カレンダー連携）で動く。
**普通の claude.ai チャットでは動かない**（コード実行やリポジトリを持たないため）。

| 実行場所 | PC | スマホ |
|---|---|---|
| **Claude Code CLI**（ローカル） | ✅ 速い・安定 | ❌ スマホでは動かない |
| **Claude Code on the web**（クラウド） | ✅ 使える | ✅ **スマホはこれ**（アプリ/ブラウザ） |

→ **CLI は PC 専用**。スマホからは **Claude Code on the web**（Claude アプリ or モバイルブラウザ）を使う。
両者は**同じリポジトリのスキルを共有**するので、一度コミットしたスキルはどのデバイスでも使える。

---

## A. PC:Claude Code CLI のセットアップ

### 1. Claude Code をインストール
- **ネイティブ（推奨）**
  - macOS / Linux: `curl -fsSL https://claude.ai/install.sh | bash`
  - Windows(PowerShell): `irm https://claude.ai/install.ps1 | iex`
- **npm（代替。Node.js 18+ が必要）**: `npm install -g @anthropic-ai/claude-code`
- 初回起動 `claude` でブラウザが開き、Anthropic アカウントで OAuth ログイン。

### 2. リポジトリを取得
```bash
git clone https://github.com/tatsuyahanada-eng/MusicLyrics.git
cd MusicLyrics
# スキルは .claude/skills/parking-finder に入っている
```

### 3. Google Maps API キーを環境変数に設定（永続化）
```bash
echo 'export GOOGLE_MAPS_API_KEY="AIza...（自分のキー）"' >> ~/.zshrc   # bash なら ~/.bashrc
source ~/.zshrc
```

### 4. Google カレンダーを接続（どちらか）
- **かんたん:Claude Desktop のコネクタを使う**
  Claude Desktop の 設定 → コネクタ で Google Calendar を接続すると、その MCP がツールとして使える。
- **CLI で MCP サーバーを追加**（例）
  ```bash
  # 例:Google Workspace 系 MCP を追加（OAuth クレデンシャルが必要）
  claude mcp add google-workspace uvx workspace-mcp --tools calendar
  ```
  追加後、Claude に「Google カレンダーを認証して」と伝えると Magic Link 認証が走る。
  ※ MCP の種類・認証方法は変わりやすいので公式ドキュメントを参照:
  https://developers.google.com/workspace/calendar/api/guides/configure-mcp-server

### 5. 実行
`MusicLyrics` ディレクトリで `claude` を起動し、チャットに:
```
/parking-finder 7/23 その他業務
```
または「7/23 のその他業務の予定の駐車場を探して追記して」。

---

## B. スマホ:Claude Code on the web

CLI はスマホでは動かないので、スマホからは **Claude アプリ（または モバイルブラウザ）の Claude Code** を使う。

1. Claude アプリ / ブラウザで **claude.ai/code** を開く
2. このリポジトリ（MusicLyrics）の環境でセッションを開始
3. チャットに `/parking-finder 7/23 その他業務`（または自然文）

前提（web 環境で一度設定すれば全デバイス共通）:
- **環境変数 `GOOGLE_MAPS_API_KEY`** を web の Environment 設定に登録 → PC/スマホの web セッション全部に反映
- **Google カレンダー**をこのチャットで有効化（接続はアカウント共通。まれに接続が切れたら、セッションを開き直す）

---

## おすすめの運用

- **基本はスマホ・PC 共通で Claude Code on the web**。理由:
  - スキルはリポジトリ共有、Maps キーは環境変数で一度きり設定 → **PC でもスマホでも同じ 1 コマンド**で動く
  - スマホから唯一動くのも web
- **PC でガッツリ・高速に**やりたいときだけ **CLI** を追加で使う（安定・速い）。

つまり「**普段はスマホ/PC どちらも web、PC で腰を据えるときは CLI**」が、手間と速度のバランスが良い。

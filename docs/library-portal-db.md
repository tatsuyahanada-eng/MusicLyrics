# ライブラリポータル — 本番運用時のデータベース設計

## 1. データベースは必要か（結論）

**必要です。** 現在のサンプル画面は、データをブラウザごとの `localStorage` に保存しています。
これは 1 台の PC・1 つのブラウザの中だけで完結する保存領域のため、次の制約があります。

| 項目 | 現状（localStorage） | 本番（データベース） |
|---|---|---|
| 他の人の登録内容が見えるか | **見えない**（各PCで別々） | 全員で共有 |
| PC を替えた場合 | データが引き継がれない | どこからでも同じ内容 |
| 誰がいつ登録したかの証跡 | 残らない（自己申告のみ） | ログイン利用者を自動記録 |
| ブラウザのキャッシュ削除 | **データ消滅** | 影響なし |
| 検索・集計（部署別、期間別） | 件数が増えると重い | SQL で高速に処理 |

関連会社間で共有し、更新履歴を「記録として残す」ことが目的であるため、
**本番稼働にはデータベース（＋簡易なサーバー側 API）の用意が必須**となります。

なお「まずは試験的に社内で回覧したい」段階であれば、
共有サーバー上に HTML を置くだけでも *閲覧* は可能です（登録は各自の端末内に留まります）。

---

## 2. 推奨構成

```
[ブラウザ]  library.html / library.js
     │  fetch（JSON）
     ▼
[アプリサーバー]  API（PHP / Node.js / C# など既存資産に合わせて可）
     │  SQL
     ▼
[データベース]  MySQL 8.0 以上（または PostgreSQL 13 以上 / SQL Server）
```

- ファイルの実体（zip や PDF）は DB に格納せず、**従来どおり共有サーバーやファイルサーバーに置き、
  その URL だけを DB で管理**する構成を推奨します（DB 肥大化とバックアップ時間の増大を防ぐため）。

---

## 3. テーブル構成

3 テーブル構成です。「更新内容」と「修正したプログラム」を別テーブルに分けることで、
1 回の更新に複数のプログラム修正が紐づく形を正確に表現できます。

| テーブル | 役割 |
|---|---|
| `library_items` | アプリ・プログラム・資料そのもの（1 行 = 1 アイテム） |
| `library_updates` | 更新履歴（1 行 = 1 回の更新。日付・時間・対応者・対象機能） |
| `library_update_files` | その更新で修正したプログラム・ファイル（1 更新に複数行） |

### 3-1. DDL（MySQL 8.0）

```sql
CREATE DATABASE IF NOT EXISTS welsys_library
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_ja_0900_as_cs_ks;

USE welsys_library;

-- ============================================================
-- ライブラリ本体
-- ============================================================
CREATE TABLE library_items (
  item_id        VARCHAR(20)  NOT NULL COMMENT '管理ID（例：APP-001）',
  name           VARCHAR(120) NOT NULL COMMENT '名称',
  category       VARCHAR(20)  NOT NULL COMMENT '種別：アプリ／プログラム／資料／マニュアル',
  owner_dept     VARCHAR(60)  NOT NULL COMMENT '管理部署',
  description    TEXT             NULL COMMENT '説明文',
  download_url   VARCHAR(500)     NULL COMMENT 'ダウンロード先URL',
  created_date   DATE         NOT NULL COMMENT '作成日（アプリ・資料の作成日）',
  is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=公開中／0=廃止（論理削除）',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (item_id),
  KEY idx_items_category (category, is_active),
  KEY idx_items_dept     (owner_dept)
) ENGINE=InnoDB COMMENT='共有ライブラリのアイテム';

-- ============================================================
-- 更新履歴（画面の1行を開いたときに表示される履歴）
-- ============================================================
CREATE TABLE library_updates (
  update_id      BIGINT       NOT NULL AUTO_INCREMENT,
  item_id        VARCHAR(20)  NOT NULL COMMENT '対象アイテム',
  updated_on     DATE         NOT NULL COMMENT '更新日',
  updated_at_time TIME        NOT NULL COMMENT '更新時間',
  author         VARCHAR(60)  NOT NULL COMMENT '対応者（氏名）',
  author_user_id VARCHAR(40)      NULL COMMENT '対応者のログインID（認証連携時に自動セット）',
  update_kind    VARCHAR(20)  NOT NULL COMMENT '区分：機能追加／不具合修正／改善／資料改訂／初版公開',
  version        VARCHAR(20)      NULL COMMENT '版数（例：v2.3.1）',
  summary        VARCHAR(500) NOT NULL COMMENT '更新内容',
  target_feature VARCHAR(200) NOT NULL COMMENT '対象機能（例：CSV出力機能）',
  ticket_no      VARCHAR(30)      NULL COMMENT '管理番号・チケット番号',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時（システム側の記録）',
  PRIMARY KEY (update_id),
  KEY idx_upd_item (item_id, updated_on DESC, updated_at_time DESC),
  KEY idx_upd_date (updated_on DESC),
  KEY idx_upd_author (author),
  CONSTRAINT fk_upd_item FOREIGN KEY (item_id)
    REFERENCES library_items (item_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='更新履歴';

-- ============================================================
-- その更新で修正したプログラム・ファイル
--   → 「更新内容」と「実際に直したプログラム」の紐づけを担うテーブル
-- ============================================================
CREATE TABLE library_update_files (
  file_id        BIGINT       NOT NULL AUTO_INCREMENT,
  update_id      BIGINT       NOT NULL,
  file_path      VARCHAR(300) NOT NULL COMMENT '修正したファイル・プログラム名',
  change_note    VARCHAR(300)     NULL COMMENT '修正内容（例：buildRow() に deptCode を追加）',
  sort_no        INT          NOT NULL DEFAULT 0 COMMENT '表示順',
  PRIMARY KEY (file_id),
  KEY idx_file_update (update_id, sort_no),
  CONSTRAINT fk_file_update FOREIGN KEY (update_id)
    REFERENCES library_updates (update_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='更新に紐づく修正プログラム';
```

### 3-2. PostgreSQL を使う場合の差分

- `TINYINT(1)` → `BOOLEAN`、`BIGINT AUTO_INCREMENT` → `BIGSERIAL`
- `ENGINE=InnoDB` と `COMMENT=` は不要（コメントは `COMMENT ON COLUMN ...` で付与）
- `updated_at` の自動更新はトリガーで実装

### 3-3. 初期データ投入例

```sql
INSERT INTO library_items
  (item_id, name, category, owner_dept, description, download_url, created_date)
VALUES
  ('APP-001', '交通費精算ツール', 'アプリ', '管理部',
   '訪問先の住所から交通費を自動計算し、月次の精算書を出力するツール。',
   'https://share.welsys.example.co.jp/apps/expense-tool/v2.3.1/setup.zip', '2024-04-18');

INSERT INTO library_updates
  (item_id, updated_on, updated_at_time, author, update_kind, version, summary, target_feature, ticket_no)
VALUES
  ('APP-001', '2025-08-21', '14:30:00', '花田 達也', '機能追加', 'v2.3.1',
   'CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化',
   'CSV出力機能 / 月次精算書出力', 'WLS-1042');

INSERT INTO library_update_files (update_id, file_path, change_note, sort_no) VALUES
  (LAST_INSERT_ID(), 'src/export/csvExporter.js', 'buildRow() に deptCode を追加', 1),
  (LAST_INSERT_ID(), 'db/schema/expense.sql',     'dept_code カラムを追加',        2);
```

### 3-4. 一覧表示用の SQL（画面の1行分を取得）

```sql
SELECT i.item_id, i.name, i.category, i.owner_dept, i.description,
       i.download_url, i.created_date,
       u.updated_on, u.updated_at_time, u.author, u.update_kind,
       u.version, u.summary, u.target_feature
FROM library_items i
LEFT JOIN library_updates u
       ON u.update_id = (
            SELECT update_id FROM library_updates
             WHERE item_id = i.item_id
             ORDER BY updated_on DESC, updated_at_time DESC, update_id DESC
             LIMIT 1)
WHERE i.is_active = 1
ORDER BY u.updated_on DESC, u.updated_at_time DESC;
```

---

## 4. 必要な API（画面が呼び出す 3 本）

| メソッド | パス | 用途 |
|---|---|---|
| GET | `/api/library/items` | 一覧＋各アイテムの更新履歴をまとめて取得 |
| POST | `/api/library/items/{itemId}/updates` | 更新履歴を 1 件登録（画面の「更新を登録」） |
| POST | `/api/library/items` | 新しいアイテムを登録（管理者のみ・任意） |

### GET `/api/library/items` の応答形式（この形で返せば画面は無改修で動作します）

```json
[
  {
    "id": "APP-001",
    "name": "交通費精算ツール",
    "category": "アプリ",
    "owner": "管理部",
    "createdAt": "2024-04-18",
    "downloadUrl": "https://share.welsys.example.co.jp/apps/expense-tool/v2.3.1/setup.zip",
    "description": "訪問先の住所から交通費を自動計算し…",
    "history": [
      {
        "date": "2025-08-21",
        "time": "14:30",
        "author": "花田 達也",
        "kind": "機能追加",
        "version": "v2.3.1",
        "summary": "CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化",
        "target": "CSV出力機能 / 月次精算書出力",
        "files": [
          "src/export/csvExporter.js : buildRow() に deptCode を追加",
          "db/schema/expense.sql : dept_code カラムを追加"
        ],
        "ticket": "WLS-1042"
      }
    ]
  }
]
```

`history` は **更新日時の降順**（新しい順）で返してください。

### POST `/api/library/items/{itemId}/updates` の要求形式

```json
{
  "date": "2025-09-04", "time": "10:15", "author": "山田 太郎",
  "kind": "不具合修正", "version": "v2.3.2",
  "summary": "…", "target": "…",
  "files": ["path : 修正内容"], "ticket": "WLS-1150",
  "downloadUrl": "（URL が変わった場合のみ）"
}
```

---

## 5. 画面側の切り替え手順

`library.js` の先頭 3 行を書き換えるだけで DB 参照に切り替わります。

```js
const DATA_SOURCE = 'api';                                  // 'sample' → 'api'
const API_BASE = 'https://portal.welsys.co.jp/api/library'; // 実際のエンドポイント
```

`localStorage` への保存処理は自動的に呼ばれなくなります。

---

## 6. 運用にあたっての確認事項

以下は方針を決めていただく必要があります。決まり次第、テーブル・API に反映します。

1. **利用者の認証** — 既存の社内アカウント（Active Directory / Microsoft 365 など）と連携しますか。
   連携できれば「対応者」欄は手入力ではなくログイン利用者を自動記録でき、記入漏れ・なりすましを防げます。
2. **登録・編集の権限** — 全員が登録可能とするか、部署ごとの担当者のみとするか。
3. **更新履歴の訂正** — 誤登録時に修正・削除を許可するか（推奨：**物理削除はせず、訂正履歴を残す運用**）。
4. **ファイルの置き場所** — 現行の共有サーバーの URL をそのまま使うか、ポータル側でアップロードも受け付けるか。
5. **サーバー環境** — 既存の社内 Web サーバーに相乗りするか、新規に用意するか（DB の種類もこれに合わせます）。
6. **対象範囲** — 関連会社ごとにデータを分離するか、全社共通の 1 つのライブラリとするか。

上記のうち 1・2・5 が決まれば、API 側の実装（言語は既存環境に合わせて選択）まで進められます。

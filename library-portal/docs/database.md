# データベース設計（MySQL）

実際に実行する DDL は **`sql/schema.sql`** が正本です。この文書はその補足説明です。

## 1. なぜデータベースが必要か

関連会社間で共有し、更新履歴を「記録として残す」ことが目的のため、
ブラウザ内保存（localStorage）では成立しません。

| 項目 | ブラウザ内保存 | データベース |
|---|---|---|
| 他の人の登録内容が見えるか | 見えない（PC ごとに別） | 全員で共有 |
| PC を替えた場合 | 引き継がれない | どこからでも同じ内容 |
| 誰がいつ登録したかの証跡 | 残らない | ログイン利用者を自動記録 |
| ブラウザのキャッシュ削除 | データ消滅 | 影響なし |
| 検索・集計 | 件数が増えると重い | SQL で高速 |

## 2. テーブル一覧

| テーブル | 役割 |
|---|---|
| `lp_users` | 利用者。`role` が `admin`（フルコントロール）か `viewer`（閲覧のみ） |
| `lp_items` | アプリ・プログラム・資料そのもの（1 行 = 1 アイテム） |
| `lp_updates` | 更新履歴（1 行 = 1 回の更新。日付・時間・対応者・区分・対象機能） |
| `lp_update_files` | その更新で修正したプログラム・ファイル（1 更新に複数行） |
| `lp_audit_log` | 操作ログ（ログイン・登録・利用者変更） |

```
lp_items 1 ──< lp_updates 1 ──< lp_update_files
                    │
                    └── author_user_id ──> lp_users
```

**更新内容とプログラム修正の紐づけ**は、`lp_updates`（何を・いつ・誰が・どの機能を）と
`lp_update_files`（実際に直したファイルと修正内容）を親子で持つことで表現しています。
1 回の更新に複数のファイル修正がぶら下がる形が、実際の作業と一致します。

## 3. 権限（role）

| role | ライブラリ閲覧・ダウンロード | アイテム登録 | 更新履歴登録 | 利用者管理 |
|---|:--:|:--:|:--:|:--:|
| `admin`（管理者） | ○ | ○ | ○ | ○ |
| `viewer`（閲覧のみ） | ○ | × | × | × |

権限は画面側でボタンを隠すだけでなく、**API 側（`api/*.php`）でも毎回検証**しています。
閲覧のみの利用者が URL を直接叩いても 403 で拒否されます。

安全策として、次の操作はできません。

- 自分自身の権限変更・停止・削除（誤操作による締め出しの防止）
- 管理者が 0 人になる変更（最後の管理者の降格・停止・削除）

## 4. API

| メソッド | パス | 権限 | 用途 |
|---|---|---|---|
| GET | `api/items.php` | ログイン必須 | 一覧＋更新履歴の取得 |
| POST | `api/items.php` | 管理者 | アイテムの新規登録 |
| POST | `api/updates.php` | 管理者 | 更新履歴を 1 件登録 |
| GET | `api/users.php` | 管理者 | 利用者一覧 |
| POST | `api/users.php` | 管理者 | 利用者の追加 |
| PATCH | `api/users.php?id=N` | 管理者 | 権限・氏名・状態・パスワードの変更 |
| DELETE | `api/users.php?id=N` | 管理者 | 利用者の削除 |
| POST | `api/password.php` | ログイン必須 | 自分のパスワード変更 |

書き込み系はすべて `X-CSRF-Token` ヘッダーを検証します（不一致は 419）。

### `GET api/items.php` の応答

```json
[
  {
    "id": "APP-001",
    "name": "交通費精算ツール",
    "category": "アプリ",
    "owner": "管理部",
    "createdAt": "2024-04-18",
    "downloadUrl": "https://share.example.co.jp/apps/expense-tool/v2.3.1/setup.zip",
    "description": "訪問先の住所から交通費を自動計算し…",
    "history": [
      {
        "date": "2025-08-21", "time": "14:30", "author": "花田 達也",
        "kind": "機能追加", "version": "v2.3.1",
        "summary": "CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化",
        "target": "CSV出力機能 / 月次精算書出力",
        "files": ["src/export/csvExporter.js : buildRow() に deptCode を追加"],
        "ticket": "WLS-1042"
      }
    ]
  }
]
```

`history` は更新日時の降順です。

### 修正ファイルの保存形式

画面では 1 行に `ファイル名 : 修正内容` の形式で入力します。
サーバー側で `ファイル名`（`file_path`）と `修正内容`（`change_note`）に分割して保存し、
取得時に再び 1 行へ結合して返します。区切りは **半角スペース + コロン + 半角スペース** です。

## 5. よく使う SQL

一覧表示（各アイテムの最新更新のみ）：

```sql
SELECT i.item_id, i.name, i.category, i.owner_dept, i.download_url, i.created_date,
       u.updated_on, u.updated_time, u.author, u.update_kind, u.version,
       u.summary, u.target_feature
FROM lp_items i
LEFT JOIN lp_updates u
       ON u.update_id = (
            SELECT update_id FROM lp_updates
             WHERE item_id = i.item_id
             ORDER BY updated_on DESC, updated_time DESC, update_id DESC
             LIMIT 1)
WHERE i.is_active = 1
ORDER BY u.updated_on DESC, u.updated_time DESC;
```

対応者別の更新件数（月次の振り返り用）：

```sql
SELECT author, COUNT(*) AS 件数
FROM lp_updates
WHERE updated_on >= '2026-09-01' AND updated_on < '2026-10-01'
GROUP BY author ORDER BY 件数 DESC;
```

ある機能に関する修正履歴を追う：

```sql
SELECT u.updated_on, u.author, u.summary, f.file_path, f.change_note
FROM lp_updates u
LEFT JOIN lp_update_files f ON f.update_id = u.update_id
WHERE u.item_id = 'APP-001' AND u.target_feature LIKE '%CSV出力%'
ORDER BY u.updated_on DESC;
```

## 6. 共通ユーザーデータベースを使う場合

複数の Web アプリで利用者を共通化する場合、`lp_users` の代わりに
共通DB（`auth_users` / `auth_app_roles`）を参照する運用に切り替えられます。
`config.php` の `auth_mode` を `'central'` にするだけで、画面・API の権限判定は
そのまま機能します（検証済み）。詳細は `sso-central-users.md`、DDL は
`sql/schema_central_auth.sql`、移行は `sql/migrate_local_to_central.sql` を参照。

なお `lp_updates.author_user_id` には**外部キー制約を張っていません**。
共通DB運用時は参照先が別データベースになるためです。

## 7. 今後の拡張余地（現時点では未実装）

- 更新履歴の訂正・取り消し（物理削除ではなく訂正履歴を残す方式を推奨）
- 関連会社ごとのデータ分離（`lp_items` に会社コード列を追加し、利用者の所属で絞り込む）
- 社内アカウント（Microsoft 365 / Active Directory）との認証連携
- ファイル本体のアップロード対応（現在は共有サーバー等の URL を登録する方式）

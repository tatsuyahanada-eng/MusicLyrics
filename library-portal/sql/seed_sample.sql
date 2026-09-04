-- ============================================================
--  ライブラリポータル — 動作確認用サンプルデータ（任意）
--  本番データを投入する前の表示確認用です。不要であれば実行しないでください。
--  削除する場合： DELETE FROM lp_items WHERE item_id IN ('APP-001','APP-002','PRG-101','DOC-201');
--  （更新履歴と修正ファイルは外部キーの連鎖削除で一緒に消えます）
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO lp_items (item_id, name, category, created_by, description, download_url, created_date) VALUES
('APP-001', '交通費精算ツール', 'アプリ', '花田 達也',
 '訪問先の住所から交通費を自動計算し、月次の精算書（Excel / CSV）を出力する Windows 向けツール。関連会社共通で利用。',
 'https://share.welsys.example.co.jp/apps/expense-tool/v2.3.1/setup.zip', '2024-04-18'),
('APP-002', '駐車場検索アシスタント', 'アプリ', '田中 亮',
 'カレンダーの予定の住所から近隣のコインパーキングを検索し、予定の説明欄へ自動追記するアシスタント。',
 'https://share.welsys.example.co.jp/apps/parking-finder/v1.4.0/parking-finder.zip', '2025-02-10'),
('PRG-101', '受注データ取込バッチ', 'プログラム', '鈴木 健一',
 '取引先から受領した受注CSVを基幹システムへ取り込む夜間バッチ。文字コード変換・重複チェック・エラーログ出力を行う。',
 'https://share.welsys.example.co.jp/programs/order-import/v3.1.2/order-import.tar.gz', '2023-11-06'),
('DOC-201', '共有サーバー運用手順書', '資料', '井上 由紀',
 '関連会社共通の共有サーバーについて、フォルダ構成・権限申請・バックアップ手順をまとめた運用手順書（PDF）。',
 'https://share.welsys.example.co.jp/docs/server-operation/v4/server-operation_v4.pdf', '2024-01-22');

INSERT INTO lp_updates (item_id, updated_on, updated_time, author, update_kind, version, summary, target_feature, ticket_no) VALUES
('APP-001', '2025-08-21', '14:30:00', '花田 達也', '機能追加', 'v2.3.1',
 'CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化', 'CSV出力機能 / 月次精算書出力', 'WLS-1042');
INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES
(LAST_INSERT_ID(), 'src/export/csvExporter.js', 'buildRow() に deptCode を追加', 1),
(LAST_INSERT_ID(), 'db/schema/expense.sql', 'dept_code カラムを追加（NOT NULL, 既定値 000）', 2);

INSERT INTO lp_updates (item_id, updated_on, updated_time, author, update_kind, version, summary, target_feature, ticket_no) VALUES
('APP-001', '2025-06-03', '10:05:00', '佐藤 美咲', '不具合修正', 'v2.3.0',
 '月をまたぐ出張で日付が前月に集計されてしまう不具合を修正', '月次集計処理', 'WLS-0987');
INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES
(LAST_INSERT_ID(), 'src/calc/monthlyAggregator.js', '集計基準日を出発日→帰着日に変更', 1),
(LAST_INSERT_ID(), 'test/monthlyAggregator.test.js', '月跨ぎケースのテストを追加', 2);

INSERT INTO lp_updates (item_id, updated_on, updated_time, author, update_kind, version, summary, target_feature, ticket_no) VALUES
('APP-002', '2025-08-28', '17:12:00', '田中 亮', '改善', 'v1.4.0',
 '検索対象を時間貸し（コインパーキング）のみに限定し、月極を除外', '駐車場検索ロジック', 'WLS-1101');
INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES
(LAST_INSERT_ID(), 'skills/parking_finder/search.py', 'filter_coin_parking() を追加', 1),
(LAST_INSERT_ID(), 'skills/parking_finder/SKILL.md', '検索条件の説明を更新', 2);

INSERT INTO lp_updates (item_id, updated_on, updated_time, author, update_kind, version, summary, target_feature, ticket_no) VALUES
('PRG-101', '2025-08-05', '22:45:00', '鈴木 健一', '不具合修正', 'v3.1.2',
 'Shift_JIS の機種依存文字が混在した場合に取込が中断する不具合を修正', '文字コード変換処理', 'WLS-1055');
INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES
(LAST_INSERT_ID(), 'batch/import/encoding.py', 'cp932 フォールバックを追加', 1),
(LAST_INSERT_ID(), 'batch/import/errorLogger.py', '変換失敗行を警告ログへ出力', 2);

INSERT INTO lp_updates (item_id, updated_on, updated_time, author, update_kind, version, summary, target_feature, ticket_no) VALUES
('DOC-201', '2025-07-30', '13:20:00', '井上 由紀', '資料改訂', 'v4.0',
 'バックアップ世代を 3 世代 → 7 世代へ変更した内容を反映', '第5章 バックアップ運用', 'WLS-1030');
INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES
(LAST_INSERT_ID(), 'server-operation_v4.pdf', '第5章 P.18-21 を改訂', 1),
(LAST_INSERT_ID(), 'backup/rotate.sh', '保持世代の設定値を 7 に変更（プログラム側の対応）', 2);

-- ============================================================
--  ライブラリポータル — データベースの更新（まとめ）
--
--  ============================================================
--  ★ 手順は2つだけです ★
--
--   【1】 下の «ここにデータベース名» を、実際の名前に書き換える
--
--         データベース名は、サーバー上の includes/config.php の
--           'db_name' => 'アカウント名_library',
--         に書いてある値です（phpMyAdmin の画面左の一覧にも出ています）。
--
--         例）データベース名が welsys_library の場合
--             USE `welsys_library`;
--
--   【2】 phpMyAdmin の「SQL」タブに貼り付けて実行する
--
--         USE を書いているので、サーバーの画面・データベースの画面の
--         どちらから実行しても通ります。
--  ============================================================
--
--  このファイルは「何度実行しても安全」に作ってあります。
--  すでに追加済みの項目は自動的に読み飛ばすので、
--  add_series.sql を実行済みかどうか分からない場合も、これを流せば大丈夫です。
--
--  追加される項目：
--    lp_items.series      … シリーズ（関連する資料をまとめる名前）
--    lp_updates.bump_type … 更新の大きさ（通常の更新 / 微修正）。版数の自動採番に使います
--
--  エラーが出る場合：
--    #1049 Unknown database
--        → USE に書いた名前が違います。config.php の db_name を確認してください
--    #1046 データベースが選択されていません
--        → 下の USE 行を書き換え忘れているか、行頭に -- が付いたままです
-- ============================================================

SET NAMES utf8mb4;

-- ↓↓↓ ここを実際のデータベース名に書き換えてください ↓↓↓
USE `«ここにデータベース名»`;
-- ↑↑↑ ここを実際のデータベース名に書き換えてください ↑↑↑


-- ------------------------------------------------------------
-- 1. lp_items.series（シリーズ）
-- ------------------------------------------------------------
SET @add_series = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_items' AND COLUMN_NAME = 'series') = 0,
  'ALTER TABLE lp_items ADD COLUMN series VARCHAR(60) NULL COMMENT ''シリーズ（関連する資料をまとめる名前）'' AFTER category',
  'DO 0');
PREPARE s FROM @add_series; EXECUTE s; DEALLOCATE PREPARE s;

SET @add_series_idx = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_items' AND INDEX_NAME = 'idx_items_series') = 0,
  'ALTER TABLE lp_items ADD INDEX idx_items_series (series)',
  'DO 0');
PREPARE s FROM @add_series_idx; EXECUTE s; DEALLOCATE PREPARE s;


-- ------------------------------------------------------------
-- 2. lp_updates.bump_type（更新の大きさ）
--    'minor'    … 通常の更新（1.1 → 1.2）
--    'revision' … 微修正（1.1 → 1.11）
-- ------------------------------------------------------------
SET @add_bump = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_updates' AND COLUMN_NAME = 'bump_type') = 0,
  'ALTER TABLE lp_updates ADD COLUMN bump_type VARCHAR(10) NOT NULL DEFAULT ''minor'' COMMENT ''minor=通常の更新 / revision=微修正'' AFTER update_kind',
  'DO 0');
PREPARE s FROM @add_bump; EXECUTE s; DEALLOCATE PREPARE s;


-- ------------------------------------------------------------
-- 確認（2行とも「あり」と表示されれば完了です）
-- ------------------------------------------------------------
SELECT 'lp_items.series' AS 項目,
       IF(COUNT(*) > 0, 'あり', 'なし') AS 状態
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_items' AND COLUMN_NAME = 'series'
UNION ALL
SELECT 'lp_updates.bump_type',
       IF(COUNT(*) > 0, 'あり', 'なし')
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_updates' AND COLUMN_NAME = 'bump_type';

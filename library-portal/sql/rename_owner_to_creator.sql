-- ============================================================
--  既存の運用中データベース向け：owner_dept → created_by への移行
--
--  「管理部署」欄を廃止し、「作成者」欄に変更したことに伴う移行です。
--  すでに sql/schema.sql（旧版）でテーブルを作成済みで、データも
--  入っている場合はこちらを実行してください（値はそのまま引き継がれます）。
--
--  まだテーブルを作っていない／データが無い場合は、この移行は不要です。
--  現在の sql/schema.sql をそのままインポートすれば created_by で作成されます。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE lp_items
  CHANGE COLUMN owner_dept created_by VARCHAR(60) NOT NULL COMMENT '作成者';

ALTER TABLE lp_items
  DROP INDEX idx_items_dept,
  ADD INDEX idx_items_creator (created_by);

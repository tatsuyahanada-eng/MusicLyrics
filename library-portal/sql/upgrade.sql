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
--         画面下の「デリミタ」欄は、既定の ; のままで構いません
--         （このファイルの中で自動的に切り替えています）。
--  ============================================================
--
--  このファイルは「何度実行しても安全」に作ってあります。
--  すでに追加済みの項目は自動的に読み飛ばすので、
--  以前に一部だけ実行していても、これを流せば大丈夫です。
--
--  追加される項目：
--    lp_items.series      … シリーズ（関連する資料をまとめる名前）
--    lp_updates.bump_type … 更新の大きさ（通常の更新 / 微修正）。版数の自動採番に使います
--    lp_updates.file_*    … 更新ごとに添付できるファイル（画像・PDF・ZIP）の情報
--    lp_categories        … カテゴリ（種別）の管理テーブル。設定画面から追加・削除できます
--
--  ※ さくらのレンタルサーバの契約・バージョンによっては、
--       #1044 ...への アクセスは拒否されました（information_schema 関連）
--       #1064 ... 'IF NOT EXISTS ...' 付近に構文エラー
--     のいずれかが出ることがあります。このファイルはそのどちらにも
--     頼らない、最も古くから使える方法（一時的な手続き＝ストアドプロシージャの
--     中で「もう追加済み」というエラーだけを無視する方法）で作ってあります。
--
--  エラーが出る場合：
--    #1049 Unknown database
--        → USE に書いた名前が違います。config.php の db_name を確認してください
--    #1046 データベースが選択されていません
--        → 下の USE 行を書き換え忘れているか、行頭に -- が付いたままです
--    上記以外のエラー
--        → エラーの文面をそのままお知らせください
-- ============================================================

SET NAMES utf8mb4;

-- ↓↓↓ ここを実際のデータベース名に書き換えてください ↓↓↓
USE `«ここにデータベース名»`;
-- ↑↑↑ ここを実際のデータベース名に書き換えてください ↑↑↑


-- ------------------------------------------------------------
-- 足りない列・索引だけを追加する（一時的な手続きの中で実行する）
--
--   1060 = Duplicate column name（その列はもう追加済み）
--   1061 = Duplicate key name   （その索引はもう追加済み）
-- という「もう出来ている」ことによるエラーだけを無視して、
-- 次へ進むようにしてあります。それ以外のエラー（権限不足など）は
-- 通常どおり表示されます。
--
-- CREATE PROCEDURE の中は ; を使うため、その間だけ区切り文字を $$ に
-- 変えています（DELIMITER 行）。手作業で書き換える必要はありません。
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS lp_upgrade_20260911;

DELIMITER $$
CREATE PROCEDURE lp_upgrade_20260911()
BEGIN
  DECLARE CONTINUE HANDLER FOR 1060, 1061 BEGIN END;

  -- 1. lp_items.series（シリーズ：関連する資料をまとめる名前）
  ALTER TABLE lp_items
    ADD COLUMN series VARCHAR(60) NULL
    COMMENT 'シリーズ（関連する資料をまとめる名前）' AFTER category;
  ALTER TABLE lp_items ADD INDEX idx_items_series (series);

  -- 2. lp_updates.bump_type（更新の大きさ）
  --    minor    … 通常の更新（1.1 → 1.2）
  --    revision … 微修正（1.1 → 1.11）
  ALTER TABLE lp_updates
    ADD COLUMN bump_type VARCHAR(10) NOT NULL DEFAULT 'minor'
    COMMENT 'minor=通常の更新 / revision=微修正' AFTER update_kind;

  -- 3. lp_updates.file_*（添付ファイル：画像・PDF・ZIP）
  ALTER TABLE lp_updates
    ADD COLUMN file_path VARCHAR(255) NULL
    COMMENT '添付ファイルの保存パス（uploads/ からの相対パス）' AFTER ticket_no;
  ALTER TABLE lp_updates
    ADD COLUMN file_name VARCHAR(255) NULL
    COMMENT '添付ファイルの元のファイル名' AFTER file_path;
  ALTER TABLE lp_updates
    ADD COLUMN file_size INT UNSIGNED NULL
    COMMENT '添付ファイルのサイズ（バイト）' AFTER file_name;
  ALTER TABLE lp_updates
    ADD COLUMN file_mime VARCHAR(100) NULL
    COMMENT '添付ファイルのMIMEタイプ' AFTER file_size;
END$$
DELIMITER ;

CALL lp_upgrade_20260911();
DROP PROCEDURE lp_upgrade_20260911;


-- ------------------------------------------------------------
-- 確認（information_schema を使わない方法。3つとも1行ずつ表示されれば完了です。
--       空欄（0 Rows）になっている項目があれば、そこだけ追加できていません）
-- ------------------------------------------------------------
SHOW COLUMNS FROM lp_items   LIKE 'series';
SHOW COLUMNS FROM lp_updates LIKE 'bump_type';
SHOW COLUMNS FROM lp_updates LIKE 'file_path';


-- ============================================================
--  追記（2回目）：カテゴリ（種別）の管理テーブル
--
--  設定画面から「アプリ」などの種別を自由に追加・削除できるようにするため、
--  今まで固定だった種別（アプリ／プログラム／資料／マニュアル）を
--  テーブルに持つようにします。CREATE TABLE IF NOT EXISTS と
--  ON DUPLICATE KEY UPDATE を使っているので、これも何度実行しても安全です。
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_categories (
  category_id  INT          NOT NULL AUTO_INCREMENT,
  code         VARCHAR(10)  NOT NULL COMMENT '管理IDの接頭辞（例：APP）。作成後は変更しません',
  label        VARCHAR(40)  NOT NULL COMMENT '表示名（例：アプリ）',
  color        VARCHAR(20)  NOT NULL DEFAULT 'graphite' COMMENT '配色キー（assets/library.js の PALETTE）',
  icon         VARCHAR(20)  NOT NULL DEFAULT 'folder' COMMENT 'アイコンキー（assets/library.js の ICONS）',
  sort_no      INT          NOT NULL DEFAULT 0 COMMENT '一覧・チップでの並び順',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (category_id),
  UNIQUE KEY uk_categories_code (code),
  UNIQUE KEY uk_categories_label (label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資料の種別（カテゴリ）';

INSERT INTO lp_categories (code, label, color, icon, sort_no) VALUES
  ('APP', 'アプリ',     'navy',     'app',    1),
  ('PRG', 'プログラム', 'graphite', 'code',   2),
  ('DOC', '資料',       'tan',      'doc',    3),
  ('MAN', 'マニュアル', 'maroon',   'book',   4)
ON DUPLICATE KEY UPDATE label = VALUES(label);

-- 確認（1行表示されれば完了です）
SHOW TABLES LIKE 'lp_categories';
SELECT code, label, color, icon, sort_no FROM lp_categories ORDER BY sort_no;

-- ============================================================
--  ライブラリポータル — MySQL スキーマ
--  対象：さくらのレンタルサーバ（MySQL 8.0）/ MariaDB 10.4 以上
--  文字コード：utf8mb4（絵文字・機種依存文字も安全に保存）
--
--  実行方法：
--    さくらのコントロールパネル → データベース → phpMyAdmin
--    → 作成済みデータベースを選択 → 「インポート」でこのファイルを実行
--    （CREATE DATABASE 行はレンタルサーバでは実行できないためコメントアウト済み）
-- ============================================================

-- ローカル環境などで新規にデータベースから作る場合のみ有効化してください。
-- CREATE DATABASE IF NOT EXISTS welsys_library
--   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- USE welsys_library;

SET NAMES utf8mb4;

-- ============================================================
-- 1. 利用者
--    role = 'admin'  … フルコントロール（登録・編集・利用者管理）
--    role = 'viewer' … 閲覧のみ
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_users (
  user_id        INT          NOT NULL AUTO_INCREMENT,
  login_id       VARCHAR(64)  NOT NULL COMMENT 'ログインID',
  display_name   VARCHAR(60)  NOT NULL COMMENT '表示名（氏名）',
  email          VARCHAR(120)     NULL COMMENT 'メールアドレス',
  dept           VARCHAR(60)      NULL COMMENT '所属',
  role           VARCHAR(10)  NOT NULL DEFAULT 'viewer' COMMENT 'admin / editor / viewer',
  password_hash  VARCHAR(255) NOT NULL COMMENT 'password_hash() の値',
  is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=有効 / 0=停止',
  must_change_pw TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=次回ログイン時にパスワード変更必須',
  failed_count   INT          NOT NULL DEFAULT 0 COMMENT '連続ログイン失敗回数',
  locked_until   DATETIME         NULL COMMENT 'この時刻までログイン不可',
  last_login_at  DATETIME         NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_users_login (login_id),
  KEY idx_users_role (role, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='利用者';

-- ============================================================
-- 2. カテゴリ（種別）。アプリ・プログラム・資料・マニュアルは初期データとして
--    登録するだけで、設定画面から自由に追加・削除できます。
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_categories (
  category_id  INT          NOT NULL AUTO_INCREMENT,
  code         VARCHAR(10)  NOT NULL COMMENT '管理IDの接頭辞（例：APP）。作成後は変更しません',
  label        VARCHAR(40)  NOT NULL COMMENT '表示名（例：アプリ）',
  color        VARCHAR(20)  NOT NULL DEFAULT 'graphite' COMMENT '配色キー（PALETTEのキー、または #rrggbb のカスタム色）',
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

-- ============================================================
-- 3. ライブラリ本体（アプリ・プログラム・資料・マニュアル）
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_items (
  item_id        VARCHAR(20)  NOT NULL COMMENT '管理ID（例：APP-001）',
  name           VARCHAR(120) NOT NULL COMMENT '名称',
  category       VARCHAR(20)  NOT NULL COMMENT 'アプリ / プログラム / 資料 / マニュアル',
  series         VARCHAR(60)      NULL COMMENT 'シリーズ（関連する資料をまとめる名前）',
  created_by     VARCHAR(60)  NOT NULL COMMENT '作成者',
  description    TEXT             NULL COMMENT '説明文',
  download_url   VARCHAR(500)     NULL COMMENT 'アプリの入口となるURL',
  created_date   DATE         NOT NULL COMMENT '作成日',
  is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=公開中 / 0=廃止',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (item_id),
  KEY idx_items_category (category, is_active),
  KEY idx_items_series (series),
  KEY idx_items_creator (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='共有ライブラリのアイテム';

-- ============================================================
-- 4. 更新履歴
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_updates (
  update_id       BIGINT       NOT NULL AUTO_INCREMENT,
  item_id         VARCHAR(20)  NOT NULL,
  updated_on      DATE         NOT NULL COMMENT '更新日',
  updated_time    TIME         NOT NULL COMMENT '更新時間',
  author          VARCHAR(60)  NOT NULL COMMENT '対応者（表示名）',
  author_user_id  INT              NULL COMMENT '登録した利用者のID（共通ユーザーDB運用時は共通側のID）',
  update_kind     VARCHAR(20)  NOT NULL COMMENT '機能追加 / 不具合修正 / 改善 / 資料改訂 / 初版公開',
  version         VARCHAR(20)      NULL COMMENT '版数（旧方式。現在は使いません／過去データ保持用）',
  bump_type       VARCHAR(10)  NOT NULL DEFAULT 'minor' COMMENT 'minor=通常の更新（1.1→1.2） / revision=微修正（1.1→1.11）',
  summary         VARCHAR(500) NOT NULL COMMENT '更新内容',
  target_feature  VARCHAR(200) NOT NULL COMMENT '対象機能',
  ticket_no       VARCHAR(30)      NULL COMMENT '管理番号',
  file_path       VARCHAR(255)     NULL COMMENT 'アップロードされた添付ファイルの保存パス（uploads/ からの相対パス）',
  file_name       VARCHAR(255)     NULL COMMENT '添付ファイルの元のファイル名（表示・ダウンロード時に使用）',
  file_size       INT UNSIGNED     NULL COMMENT '添付ファイルのサイズ（バイト）',
  file_mime       VARCHAR(100)     NULL COMMENT '添付ファイルのMIMEタイプ',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時（システム記録）',
  PRIMARY KEY (update_id),
  KEY idx_upd_item (item_id, updated_on DESC, updated_time DESC),
  KEY idx_upd_date (updated_on DESC),
  KEY idx_upd_author (author),
  CONSTRAINT fk_upd_item FOREIGN KEY (item_id)
    REFERENCES lp_items (item_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='更新履歴';

-- ============================================================
-- 5. その更新で修正したプログラム・ファイル
--    「更新内容」と「実際に直したプログラム」を紐づけるテーブル
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_update_files (
  file_id     BIGINT       NOT NULL AUTO_INCREMENT,
  update_id   BIGINT       NOT NULL,
  file_path   VARCHAR(300) NOT NULL COMMENT '修正したファイル・プログラム名',
  change_note VARCHAR(300)     NULL COMMENT '修正内容',
  sort_no     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (file_id),
  KEY idx_file_update (update_id, sort_no),
  CONSTRAINT fk_file_update FOREIGN KEY (update_id)
    REFERENCES lp_updates (update_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='更新に紐づく修正プログラム';

-- ============================================================
-- 6. 操作ログ（誰がいつ何を登録・変更したか）
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_audit_log (
  log_id     BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    INT             NULL,
  login_id   VARCHAR(64)     NULL,
  action     VARCHAR(40) NOT NULL COMMENT 'login / item.create / update.create / user.create など',
  target     VARCHAR(120)    NULL COMMENT '対象（item_id や login_id）',
  detail     VARCHAR(500)    NULL,
  ip_address VARCHAR(45)     NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (log_id),
  KEY idx_log_created (created_at DESC),
  KEY idx_log_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作ログ';

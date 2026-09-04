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
  role           VARCHAR(10)  NOT NULL DEFAULT 'viewer' COMMENT 'admin / viewer',
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
-- 2. ライブラリ本体（アプリ・プログラム・資料・マニュアル）
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_items (
  item_id        VARCHAR(20)  NOT NULL COMMENT '管理ID（例：APP-001）',
  name           VARCHAR(120) NOT NULL COMMENT '名称',
  category       VARCHAR(20)  NOT NULL COMMENT 'アプリ / プログラム / 資料 / マニュアル',
  owner_dept     VARCHAR(60)  NOT NULL COMMENT '管理部署',
  description    TEXT             NULL COMMENT '説明文',
  download_url   VARCHAR(500)     NULL COMMENT 'ダウンロード先URL',
  created_date   DATE         NOT NULL COMMENT '作成日',
  is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=公開中 / 0=廃止',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (item_id),
  KEY idx_items_category (category, is_active),
  KEY idx_items_dept (owner_dept)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='共有ライブラリのアイテム';

-- ============================================================
-- 3. 更新履歴
-- ============================================================
CREATE TABLE IF NOT EXISTS lp_updates (
  update_id       BIGINT       NOT NULL AUTO_INCREMENT,
  item_id         VARCHAR(20)  NOT NULL,
  updated_on      DATE         NOT NULL COMMENT '更新日',
  updated_time    TIME         NOT NULL COMMENT '更新時間',
  author          VARCHAR(60)  NOT NULL COMMENT '対応者（表示名）',
  author_user_id  INT              NULL COMMENT '登録した利用者のID（共通ユーザーDB運用時は共通側のID）',
  update_kind     VARCHAR(20)  NOT NULL COMMENT '機能追加 / 不具合修正 / 改善 / 資料改訂 / 初版公開',
  version         VARCHAR(20)      NULL COMMENT '版数',
  summary         VARCHAR(500) NOT NULL COMMENT '更新内容',
  target_feature  VARCHAR(200) NOT NULL COMMENT '対象機能',
  ticket_no       VARCHAR(30)      NULL COMMENT '管理番号',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時（システム記録）',
  PRIMARY KEY (update_id),
  KEY idx_upd_item (item_id, updated_on DESC, updated_time DESC),
  KEY idx_upd_date (updated_on DESC),
  KEY idx_upd_author (author),
  CONSTRAINT fk_upd_item FOREIGN KEY (item_id)
    REFERENCES lp_items (item_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='更新履歴';

-- ============================================================
-- 4. その更新で修正したプログラム・ファイル
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
-- 5. 操作ログ（誰がいつ何を登録・変更したか）
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

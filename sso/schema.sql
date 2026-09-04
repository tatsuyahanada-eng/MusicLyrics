-- =====================================================================
--  WELSYS User Management / Single Sign-On  -- MySQL schema
--  MySQL 5.7.8+ / MariaDB 10.2+ (utf8mb4, InnoDB)
--
--  導入:  mysql -u root -p < schema.sql
--         （DB とユーザーは事前に作成しておくこと。下の例を参照）
--
--    CREATE DATABASE welsys_sso
--      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--    CREATE USER 'welsys_sso'@'localhost' IDENTIFIED BY '********';
--    GRANT SELECT, INSERT, UPDATE, DELETE ON welsys_sso.* TO 'welsys_sso'@'localhost';
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- users : 全アプリ共通のユーザーマスタ
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id                   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  username             VARCHAR(64)  NOT NULL COMMENT 'ログインID',
  email                VARCHAR(190) DEFAULT NULL,
  password_hash        VARCHAR(255) NOT NULL COMMENT 'password_hash() の出力',
  display_name         VARCHAR(120) NOT NULL DEFAULT '',
  department           VARCHAR(120) NOT NULL DEFAULT '',
  status               ENUM('active','suspended') NOT NULL DEFAULT 'active',
  is_admin             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '管理コンソールの利用可否',
  must_change_password TINYINT(1)   NOT NULL DEFAULT 0,
  failed_attempts      INT UNSIGNED NOT NULL DEFAULT 0,
  locked_until         DATETIME     DEFAULT NULL,
  last_login_at        DATETIME     DEFAULT NULL,
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_username (username),
  UNIQUE KEY uq_users_email (email),
  KEY idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- apps : SSO に参加するウェブアプリケーション
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS apps (
  id             INT UNSIGNED NOT NULL AUTO_INCREMENT,
  app_key        VARCHAR(64)  NOT NULL COMMENT 'アプリ識別子 (英数字と - _)',
  name           VARCHAR(120) NOT NULL,
  description    VARCHAR(255) NOT NULL DEFAULT '',
  base_url       VARCHAR(255) NOT NULL COMMENT '戻り先URLはこの前方一致でのみ許可',
  app_secret     VARCHAR(128) NOT NULL COMMENT 'チケット検証用の共有秘密鍵',
  default_policy ENUM('deny','allow') NOT NULL DEFAULT 'deny'
                 COMMENT '個別設定が無いユーザーの既定の扱い',
  status         ENUM('active','disabled') NOT NULL DEFAULT 'active',
  sort_order     INT          NOT NULL DEFAULT 100,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_apps_key (app_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- app_permissions : ユーザー × アプリ の閲覧許可 / 拒否
--   行が無い場合は apps.default_policy が適用される
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_permissions (
  user_id    INT UNSIGNED NOT NULL,
  app_id     INT UNSIGNED NOT NULL,
  effect     ENUM('allow','deny') NOT NULL DEFAULT 'allow',
  granted_by INT UNSIGNED DEFAULT NULL,
  note       VARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, app_id),
  KEY idx_perm_app (app_id),
  KEY idx_perm_granted_by (granted_by),
  CONSTRAINT fk_perm_user    FOREIGN KEY (user_id)    REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_perm_app     FOREIGN KEY (app_id)     REFERENCES apps  (id) ON DELETE CASCADE,
  CONSTRAINT fk_perm_granter FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- app_user_links : 既存アプリが持っている「そのアプリ内のユーザーID」との対応表
--   稼働中のアプリを止めずに移行するための橋渡し。
--   validate 応答に external_user_id として返るので、アプリ側は
--   自前のユーザーテーブルをそのまま使い続けられる。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user_links (
  app_id           INT UNSIGNED NOT NULL,
  user_id          INT UNSIGNED NOT NULL,
  external_user_id VARCHAR(190) NOT NULL COMMENT 'アプリ側の既存ユーザーID',
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (app_id, user_id),
  UNIQUE KEY uq_link_external (app_id, external_user_id),
  KEY idx_link_user (user_id),
  CONSTRAINT fk_link_app  FOREIGN KEY (app_id)  REFERENCES apps  (id) ON DELETE CASCADE,
  CONSTRAINT fk_link_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- sso_sessions : 認証サーバー側のログインセッション（SSO の実体）
--   id にはトークンそのものではなく sha256 ハッシュを保存する
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sso_sessions (
  id           CHAR(64)     NOT NULL COMMENT 'sha256(セッショントークン)',
  user_id      INT UNSIGNED NOT NULL,
  ip           VARCHAR(45)  NOT NULL DEFAULT '',
  user_agent   VARCHAR(255) NOT NULL DEFAULT '',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   DATETIME     NOT NULL,
  revoked_at   DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_sess_user (user_id),
  KEY idx_sess_expires (expires_at),
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- sso_tickets : 使い捨てチケット（アプリへの受け渡し用・寿命は数十秒）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sso_tickets (
  id           CHAR(64)     NOT NULL COMMENT 'sha256(チケット)',
  app_id       INT UNSIGNED NOT NULL,
  user_id      INT UNSIGNED NOT NULL,
  session_id   CHAR(64)     NOT NULL,
  redirect_url VARCHAR(512) NOT NULL,
  ip           VARCHAR(45)  NOT NULL DEFAULT '',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   DATETIME     NOT NULL,
  consumed_at  DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ticket_expires (expires_at),
  KEY idx_ticket_app (app_id),
  KEY idx_ticket_user (user_id),
  CONSTRAINT fk_ticket_app  FOREIGN KEY (app_id)  REFERENCES apps  (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- audit_logs : 監査ログ（ログイン・権限変更・ユーザー追加削除など）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_logs (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id       INT UNSIGNED DEFAULT NULL COMMENT '操作した人',
  target_user_id INT UNSIGNED DEFAULT NULL COMMENT '操作対象のユーザー',
  app_id         INT UNSIGNED DEFAULT NULL,
  action         VARCHAR(64)  NOT NULL,
  detail         TEXT         DEFAULT NULL COMMENT 'JSON',
  ip             VARCHAR(45)  NOT NULL DEFAULT '',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_created (created_at),
  KEY idx_audit_action (action),
  KEY idx_audit_actor (actor_id),
  KEY idx_audit_target (target_user_id),
  KEY idx_audit_app (app_id),
  CONSTRAINT fk_audit_actor  FOREIGN KEY (actor_id)       REFERENCES users (id) ON DELETE SET NULL,
  CONSTRAINT fk_audit_target FOREIGN KEY (target_user_id) REFERENCES users (id) ON DELETE SET NULL,
  CONSTRAINT fk_audit_app    FOREIGN KEY (app_id)         REFERENCES apps  (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

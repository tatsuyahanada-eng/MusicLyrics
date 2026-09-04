-- ============================================================
--  共通ユーザーデータベース（複数の Web アプリで共有）
--  データベース名の例：welsys_auth
--
--  役割分担
--    auth_users     … 「誰か」（本人確認）※全アプリ共通
--    auth_app_roles … 「どのアプリで何ができるか」（権限）※アプリ単位
--    auth_sessions  … シングルサインオンのセッション（任意・後述）
--
--  権限をアプリごとの行として持つことで、
--  「ライブラリポータルでは管理者、別アプリでは閲覧のみ」を表現できます。
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 1. 利用者（本人確認の情報のみ。アプリ固有の情報は持たせない）
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_users (
  user_id        INT          NOT NULL AUTO_INCREMENT,
  login_id       VARCHAR(64)  NOT NULL COMMENT 'ログインID（全社で一意）',
  display_name   VARCHAR(60)  NOT NULL COMMENT '表示名（氏名）',
  email          VARCHAR(120)     NULL,
  company        VARCHAR(60)      NULL COMMENT '所属会社（関連会社の区別）',
  dept           VARCHAR(60)      NULL COMMENT '所属部署',
  password_hash  VARCHAR(255) NOT NULL COMMENT 'password_hash() の値',
  is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0=退職・停止（全アプリで即ログイン不可）',
  must_change_pw TINYINT(1)   NOT NULL DEFAULT 0,
  failed_count   INT          NOT NULL DEFAULT 0,
  locked_until   DATETIME         NULL,
  last_login_at  DATETIME         NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_auth_login (login_id),
  KEY idx_auth_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='共通利用者';

-- ============================================================
-- 2. アプリごとの権限
--    app_key : アプリの識別子（例：library / expense / attendance）
--    role    : そのアプリでの権限（library では admin / viewer）
--    行が無い利用者は「そのアプリの利用権限なし」として扱えます
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_app_roles (
  app_key    VARCHAR(30) NOT NULL,
  user_id    INT         NOT NULL,
  role       VARCHAR(20) NOT NULL COMMENT 'admin / viewer など、アプリごとの権限名',
  granted_by INT             NULL COMMENT '付与した利用者',
  granted_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (app_key, user_id),
  KEY idx_role_user (user_id),
  KEY idx_role_app (app_key, role),
  CONSTRAINT fk_role_user FOREIGN KEY (user_id)
    REFERENCES auth_users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='アプリ別の権限';

-- ============================================================
-- 3. アプリ登録（任意。共通の利用者管理画面で一覧に使う）
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_apps (
  app_key    VARCHAR(30)  NOT NULL,
  app_name   VARCHAR(60)  NOT NULL,
  app_url    VARCHAR(255)     NULL,
  roles_json VARCHAR(255)     NULL COMMENT 'そのアプリで選択できる権限の一覧（例：["admin","viewer"]）',
  is_active  TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (app_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連携アプリ';

INSERT INTO auth_apps (app_key, app_name, app_url, roles_json) VALUES
  ('library', 'ライブラリポータル', '/library/', '["admin","viewer"]')
ON DUPLICATE KEY UPDATE app_name = VALUES(app_name);

-- ============================================================
-- 4. シングルサインオン用のセッション（任意）
--    共通のログイン画面で認証し、各アプリはこの表を見て本人を判定します。
--    「ログアウトを全アプリへ即時反映」したい場合に有効です。
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_sessions (
  session_id   CHAR(64)    NOT NULL COMMENT 'ランダムな識別子（クッキーに保存）',
  user_id      INT         NOT NULL,
  issued_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   DATETIME    NOT NULL,
  ip_address   VARCHAR(45)     NULL,
  user_agent   VARCHAR(255)    NULL,
  revoked_at   DATETIME        NULL COMMENT 'ログアウト時刻。入っていれば無効',
  PRIMARY KEY (session_id),
  KEY idx_sess_user (user_id, expires_at),
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id)
    REFERENCES auth_users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSO セッション';

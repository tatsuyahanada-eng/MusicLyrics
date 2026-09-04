-- ============================================================
--  既存のライブラリポータル利用者（lp_users）を
--  共通ユーザーデータベース（auth_users / auth_app_roles）へ移す手順
--
--  前提：同じ MySQL サーバー上に 2 つのデータベースがあること
--        共通DB   = welsys_auth
--        アプリDB = welsys_library
--  データベース名は実際の名前（さくらでは「アカウント名_〜」）に置き換えてください。
-- ============================================================

-- 1) 利用者を共通DBへコピー（パスワードのハッシュもそのまま移せます）
INSERT INTO welsys_auth.auth_users
  (login_id, display_name, email, dept, password_hash, is_active, must_change_pw, last_login_at, created_at)
SELECT login_id, display_name, email, dept, password_hash, is_active, must_change_pw, last_login_at, created_at
FROM welsys_library.lp_users
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

-- 2) ライブラリポータルでの権限を、アプリ別権限として登録
INSERT INTO welsys_auth.auth_app_roles (app_key, user_id, role)
SELECT 'library', a.user_id, l.role
FROM welsys_library.lp_users l
JOIN welsys_auth.auth_users a ON a.login_id = l.login_id
ON DUPLICATE KEY UPDATE role = VALUES(role);

-- 3) 更新履歴の対応者IDを共通DBのIDへ付け替える
--    （lp_updates.author_user_id はアプリ内のIDを保持しているため）
UPDATE welsys_library.lp_updates u
JOIN welsys_library.lp_users l ON l.user_id = u.author_user_id
JOIN welsys_auth.auth_users a  ON a.login_id = l.login_id
SET u.author_user_id = a.user_id;

-- 4) 外部キー制約を外す（対応者IDの参照先が共通DBに移るため）
--    制約名が異なる場合は SHOW CREATE TABLE lp_updates; で確認してください。
ALTER TABLE welsys_library.lp_updates DROP FOREIGN KEY fk_upd_user;

-- 5) 確認
SELECT a.login_id, a.display_name, r.app_key, r.role
FROM welsys_auth.auth_users a
LEFT JOIN welsys_auth.auth_app_roles r ON r.user_id = a.user_id
ORDER BY a.login_id;

-- 6) 移行後、アプリ側の includes/config.php を
--      'auth_mode' => 'central'
--    に切り替えます。lp_users は当面残しておき（ロールバック用）、
--    問題がないことを確認してから削除してください。

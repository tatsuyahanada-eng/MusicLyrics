-- ============================================================
--  初期管理者アカウントの作成（本番投入用）
--
--  ログインID： welsys
--  パスワード： Password   ※必ずログイン後すぐに変更してください
--
--  実行方法：schema.sql のインポート後、続けてこのファイルをインポートします。
--  実行すると setup.php は「既に利用者が登録されている」として自動的に
--  無効化されるため、setup.php を実行する必要はありません
--  （そのまま login.php からログインできます）。
--
--  既に同じログインIDが存在する場合はエラーになりますが、データが
--  二重に作られることはありません（安全に再実行できます）。
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO lp_users
  (login_id, display_name, role, password_hash, is_active, must_change_pw)
VALUES
  ('welsys', 'ウェルシス管理者', 'admin',
   '$2y$12$DHa1wN2QcIoOwk10.PYOn.GRyh1rp58fsqn9cm8hQc4SgNBOK7JzC',
   1, 1)
ON DUPLICATE KEY UPDATE login_id = login_id;

<?php
/* ============================================================
   Case By Case — db.php
   PDO によるDB接続とスキーマ初期化。
   config.php の DB_DRIVER で mysql / sqlite / pgsql を切替。
   ロリポップでは mysql を使用します。
   ============================================================ */

function cbc_pdo() {
  static $pdo = null;
  if ($pdo !== null) return $pdo;

  $driver = defined('DB_DRIVER') ? DB_DRIVER : 'mysql';
  $opts = array(
    PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    PDO::ATTR_EMULATE_PREPARES   => false,
  );

  if ($driver === 'sqlite') {
    $path = defined('DB_SQLITE_PATH') ? DB_SQLITE_PATH : (__DIR__ . '/data/manual.sqlite');
    $dir = dirname($path);
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    $pdo = new PDO('sqlite:' . $path, null, null, $opts);
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('PRAGMA foreign_keys = ON');
  } elseif ($driver === 'pgsql') {
    $dsn = sprintf('pgsql:host=%s;port=%d;dbname=%s',
      DB_HOST, defined('DB_PORT') ? DB_PORT : 5432, DB_NAME);
    $pdo = new PDO($dsn, DB_USER, DB_PASS, $opts);
  } else { // mysql
    $charset = defined('DB_CHARSET') ? DB_CHARSET : 'utf8mb4';
    $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=%s',
      DB_HOST, defined('DB_PORT') ? DB_PORT : 3306, DB_NAME, $charset);
    $pdo = new PDO($dsn, DB_USER, DB_PASS, $opts);
  }

  cbc_init_schema($pdo, $driver);
  return $pdo;
}

/* テーブルが無ければ作成（初回アクセス時に自動実行） */
function cbc_init_schema($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS nodes (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        parent_id  VARCHAR(40)  NULL,
        sort_order INT          NOT NULL DEFAULT 0,
        title      VARCHAR(255) NOT NULL,
        body       MEDIUMTEXT   NULL,
        updated_at BIGINT       NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0,
        INDEX idx_parent (parent_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS nodes (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        parent_id  VARCHAR(40)  NULL,
        sort_order INTEGER      NOT NULL DEFAULT 0,
        title      VARCHAR(255) NOT NULL,
        body       TEXT         NULL,
        updated_at BIGINT       NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_parent ON nodes (parent_id)");
  }
}

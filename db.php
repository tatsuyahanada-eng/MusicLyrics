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

/* テーブルが無ければ作成（初回アクセス時に自動実行）。
   既存テーブルには不足カラムを自動追加（記入者カラム等の後方互換）。 */
function cbc_init_schema($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS nodes (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        parent_id  VARCHAR(40)  NULL,
        sort_order INT          NOT NULL DEFAULT 0,
        title      VARCHAR(255) NOT NULL,
        body       MEDIUMTEXT   NULL,
        created_by VARCHAR(120) NULL,
        updated_by VARCHAR(120) NULL,
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
        created_by VARCHAR(120) NULL,
        updated_by VARCHAR(120) NULL,
        updated_at BIGINT       NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_parent ON nodes (parent_id)");
  }
  cbc_ensure_columns($pdo, $driver);
}

/* 既存DBに不足しているカラムを追加（記入者カラムの後付け対応） */
function cbc_ensure_columns($pdo, $driver) {
  try {
    if ($driver === 'sqlite') {
      $rows = $pdo->query("PRAGMA table_info(nodes)")->fetchAll();
      $cols = array();
      foreach ($rows as $r) { $cols[] = strtolower($r['name']); }
    } else {
      $sql = "SELECT COLUMN_NAME AS c FROM information_schema.columns WHERE table_name = 'nodes'";
      if ($driver === 'mysql') $sql .= " AND table_schema = DATABASE()";
      $rows = $pdo->query($sql)->fetchAll();
      $cols = array();
      foreach ($rows as $r) { $cols[] = strtolower($r['c']); }
    }
    foreach (array('created_by', 'updated_by') as $col) {
      if (!in_array($col, $cols, true)) {
        $pdo->exec("ALTER TABLE nodes ADD COLUMN $col VARCHAR(120) NULL");
      }
    }
  } catch (Throwable $e) { /* 追加できなくても致命ではない */ }
}

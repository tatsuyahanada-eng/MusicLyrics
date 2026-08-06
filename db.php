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
        lock_hash  VARCHAR(255) NULL,
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
        lock_hash  VARCHAR(255) NULL,
        updated_at BIGINT       NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_parent ON nodes (parent_id)");
  }
  cbc_ensure_columns($pdo, $driver);
  cbc_init_inventory($pdo, $driver);
  cbc_init_settings($pdo, $driver);
  cbc_init_auth($pdo, $driver);
}

/* アプリ内ログイン用。users=アカウント（許可カテゴリを保持）、sessions=ログインセッション。 */
function cbc_init_auth($pdo, $driver) {
  $text = ($driver === 'mysql') ? 'MEDIUMTEXT' : 'TEXT';
  $eng  = ($driver === 'mysql') ? ' ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci' : '';
  $pdo->exec(
    "CREATE TABLE IF NOT EXISTS users (
      username     VARCHAR(64)  NOT NULL PRIMARY KEY,
      display_name VARCHAR(120) NULL,
      pass_hash    VARCHAR(255) NULL,
      is_admin     INT          NOT NULL DEFAULT 0,
      allowed      $text        NULL,
      created_at   BIGINT       NOT NULL DEFAULT 0,
      updated_at   BIGINT       NOT NULL DEFAULT 0
    )$eng"
  );
  // 既存の users テーブルに display_name が無ければ後付けする。
  try {
    if ($driver === 'sqlite') {
      $cols = array();
      foreach ($pdo->query("PRAGMA table_info(users)")->fetchAll() as $r) { $cols[] = strtolower($r['name']); }
    } else {
      $sql = "SELECT COLUMN_NAME AS c FROM information_schema.columns WHERE table_name = 'users'";
      if ($driver === 'mysql') $sql .= " AND table_schema = DATABASE()";
      $cols = array();
      foreach ($pdo->query($sql)->fetchAll() as $r) { $cols[] = strtolower($r['c']); }
    }
    if (!in_array('display_name', $cols, true)) {
      $pdo->exec("ALTER TABLE users ADD COLUMN display_name VARCHAR(120) NULL");
    }
  } catch (Throwable $e) { /* 追加できなくても致命ではない */ }
  $pdo->exec(
    "CREATE TABLE IF NOT EXISTS sessions (
      token      VARCHAR(64)  NOT NULL PRIMARY KEY,
      username   VARCHAR(64)  NULL,
      is_admin   INT          NOT NULL DEFAULT 0,
      created_at BIGINT       NOT NULL DEFAULT 0,
      expires_at BIGINT       NOT NULL DEFAULT 0
    )$eng"
  );
}

/* アプリ共通設定（キー/値）。ピン留め（全端末で共有）などを保存する。 */
function cbc_init_settings($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS app_settings (
        k VARCHAR(64)  NOT NULL PRIMARY KEY,
        v MEDIUMTEXT   NULL
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS app_settings (
        k VARCHAR(64) NOT NULL PRIMARY KEY,
        v TEXT        NULL
      )"
    );
  }
}

/* 在庫管理テーブル（初回アクセス時に自動作成）。
   inv_items : 商品マスタ（商品名・型番・現在個数）
   inv_logs  : 使用履歴（持ち出し／返却／使用／調整、いつ・何個・誰が） */
function cbc_init_inventory($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS inv_items (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        name       VARCHAR(255) NOT NULL,
        model      VARCHAR(255) NULL,
        qty        INT          NOT NULL DEFAULT 0,
        note       VARCHAR(255) NULL,
        sort_order INT          NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0,
        updated_at BIGINT       NOT NULL DEFAULT 0
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS inv_logs (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        item_id    VARCHAR(40)  NOT NULL,
        action     VARCHAR(20)  NOT NULL,
        qty        INT          NOT NULL DEFAULT 0,
        balance    INT          NOT NULL DEFAULT 0,
        person     VARCHAR(120) NULL,
        note       VARCHAR(255) NULL,
        created_at BIGINT       NOT NULL DEFAULT 0,
        INDEX idx_inv_item (item_id)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS inv_items (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        name       VARCHAR(255) NOT NULL,
        model      VARCHAR(255) NULL,
        qty        INTEGER      NOT NULL DEFAULT 0,
        note       VARCHAR(255) NULL,
        sort_order INTEGER      NOT NULL DEFAULT 0,
        created_at BIGINT       NOT NULL DEFAULT 0,
        updated_at BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS inv_logs (
        id         VARCHAR(40)  NOT NULL PRIMARY KEY,
        item_id    VARCHAR(40)  NOT NULL,
        action     VARCHAR(20)  NOT NULL,
        qty        INTEGER      NOT NULL DEFAULT 0,
        balance    INTEGER      NOT NULL DEFAULT 0,
        person     VARCHAR(120) NULL,
        note       VARCHAR(255) NULL,
        created_at BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_inv_item ON inv_logs (item_id)");
  }
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
    if (!in_array('lock_hash', $cols, true)) {
      $pdo->exec("ALTER TABLE nodes ADD COLUMN lock_hash VARCHAR(255) NULL");
    }
  } catch (Throwable $e) { /* 追加できなくても致命ではない */ }
}

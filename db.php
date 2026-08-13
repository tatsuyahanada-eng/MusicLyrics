<?php
/* ============================================================
   Case By Case — db.php
   PDO によるDB接続とスキーマ初期化。
   config.php の DB_DRIVER で mysql / sqlite / pgsql を切替。
   ロリポップでは mysql を使用します。
   ============================================================ */

/* スキーマのバージョン。テーブル定義（列の追加など）を変えたら必ず上げる。
   これが変わると、各サーバーで初回アクセス時に一度だけ初期化/マイグレーションが走る。 */
if (!defined('CBC_SCHEMA_VERSION')) define('CBC_SCHEMA_VERSION', '2026-08-13-vectors2');

// 接続だけを1回試みる（スキーマ初期化はしない）。
function cbc_connect_once($driver, $opts) {
  if ($driver === 'sqlite') {
    $path = defined('DB_SQLITE_PATH') ? DB_SQLITE_PATH : (__DIR__ . '/data/manual.sqlite');
    $dir = dirname($path);
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    $pdo = new PDO('sqlite:' . $path, null, null, $opts);
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('PRAGMA foreign_keys = ON');
    return $pdo;
  } elseif ($driver === 'pgsql') {
    $dsn = sprintf('pgsql:host=%s;port=%d;dbname=%s',
      DB_HOST, defined('DB_PORT') ? DB_PORT : 5432, DB_NAME);
    return new PDO($dsn, DB_USER, DB_PASS, $opts);
  }
  // mysql
  $charset = defined('DB_CHARSET') ? DB_CHARSET : 'utf8mb4';
  $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=%s',
    DB_HOST, defined('DB_PORT') ? DB_PORT : 3306, DB_NAME, $charset);
  return new PDO($dsn, DB_USER, DB_PASS, $opts);
}

// 「接続数が一時的に上限」系のエラーか（1040=Too many connections / 1203 なども）。
function cbc_is_busy_error($e) {
  $msg = $e->getMessage();
  if (stripos($msg, 'Too many connections') !== false) return true;
  if (stripos($msg, 'max_connections') !== false) return true;
  if (stripos($msg, 'max_user_connections') !== false) return true;
  return false;
}

function cbc_pdo() {
  static $pdo = null;
  if ($pdo !== null) return $pdo;

  $driver = defined('DB_DRIVER') ? DB_DRIVER : 'mysql';
  $opts = array(
    PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    PDO::ATTR_EMULATE_PREPARES   => false,
    PDO::ATTR_TIMEOUT            => 5,
  );

  // 同時接続が一時的に上限に達しているとき（[1040] Too many connections）は
  // 少しだけ待って数回リトライする。瞬間的なアクセス集中を吸収してエラー画面を減らす。
  $attempt = 0; $max = 4;
  while (true) {
    try { $pdo = cbc_connect_once($driver, $opts); break; }
    catch (PDOException $e) {
      if ($attempt < $max && cbc_is_busy_error($e)) {
        $attempt++;
        usleep(120000 * $attempt); // 120ms, 240ms, 360ms, 480ms
        continue;
      }
      throw $e;
    }
  }

  cbc_maybe_init_schema($pdo, $driver); // 初回（バージョン変化時）だけ初期化。通常は何もしない。
  return $pdo;
}

// スキーマ初期化済みを記録するマーカーファイルのパス（書き込めない環境では null）。
function cbc_schema_marker_path() {
  $dir = __DIR__ . '/data';
  if (defined('DB_DRIVER') && DB_DRIVER === 'sqlite') {
    $p = defined('DB_SQLITE_PATH') ? DB_SQLITE_PATH : (__DIR__ . '/data/manual.sqlite');
    $dir = dirname($p);
  }
  if (!is_dir($dir)) @mkdir($dir, 0775, true);
  return (is_dir($dir) && is_writable($dir)) ? ($dir . '/.schema-version') : null;
}

// スキーマ初期化を「初回（＝バージョンが変わったとき）だけ」実行する。
// これにより通常のリクエストでは十数個のDDL/情報スキーマ照会が走らず、
// 各リクエストが軽くなって接続を握る時間が短くなる（＝同時接続の山を下げる）。
function cbc_maybe_init_schema($pdo, $driver) {
  $marker = cbc_schema_marker_path();
  if ($marker !== null) {
    $cur = @file_get_contents($marker);
    if ($cur !== false && trim($cur) === CBC_SCHEMA_VERSION) return; // 既に最新版で初期化済み
  }
  cbc_init_schema($pdo, $driver);
  if ($marker !== null) { @file_put_contents($marker, CBC_SCHEMA_VERSION, LOCK_EX); }
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
  cbc_init_trips($pdo, $driver);
  cbc_init_vectors($pdo, $driver);
  cbc_init_qvectors($pdo, $driver);
}

/* 交通費（オンサイト案件の移動費）記録。ユーザーごとに登録し、管理者が月別/ユーザー別に集計。 */
function cbc_init_trips($pdo, $driver) {
  $intType = ($driver === 'mysql') ? 'INT' : 'INTEGER';
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS trips (
        id           VARCHAR(40)  NOT NULL PRIMARY KEY,
        username     VARCHAR(64)  NULL,
        display_name VARCHAR(120) NULL,
        trip_date    VARCHAR(10)  NULL,
        case_name    VARCHAR(255) NULL,
        mode         VARCHAR(10)  NOT NULL DEFAULT 'car',
        origin       VARCHAR(255) NULL,
        destination  VARCHAR(1000) NULL,
        one_way_km   DECIMAL(8,2) NOT NULL DEFAULT 0,
        round_trip   INT          NOT NULL DEFAULT 1,
        gas_rate     INT          NOT NULL DEFAULT 18,
        gas_cost     INT          NOT NULL DEFAULT 0,
        fare_cost    INT          NOT NULL DEFAULT 0,
        toll_cost    INT          NOT NULL DEFAULT 0,
        parking_cost INT          NOT NULL DEFAULT 0,
        other_cost   INT          NOT NULL DEFAULT 0,
        total        INT          NOT NULL DEFAULT 0,
        cost_details MEDIUMTEXT   NULL,
        note         VARCHAR(500) NULL,
        created_at   BIGINT       NOT NULL DEFAULT 0,
        updated_at   BIGINT       NOT NULL DEFAULT 0,
        INDEX idx_trip_user_date (username, trip_date)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS trips (
        id           VARCHAR(40)  NOT NULL PRIMARY KEY,
        username     VARCHAR(64)  NULL,
        display_name VARCHAR(120) NULL,
        trip_date    VARCHAR(10)  NULL,
        case_name    VARCHAR(255) NULL,
        mode         VARCHAR(10)  NOT NULL DEFAULT 'car',
        origin       VARCHAR(255) NULL,
        destination  VARCHAR(1000) NULL,
        one_way_km   DECIMAL(8,2) NOT NULL DEFAULT 0,
        round_trip   INTEGER      NOT NULL DEFAULT 1,
        gas_rate     INTEGER      NOT NULL DEFAULT 18,
        gas_cost     INTEGER      NOT NULL DEFAULT 0,
        fare_cost    INTEGER      NOT NULL DEFAULT 0,
        toll_cost    INTEGER      NOT NULL DEFAULT 0,
        parking_cost INTEGER      NOT NULL DEFAULT 0,
        other_cost   INTEGER      NOT NULL DEFAULT 0,
        total        INTEGER      NOT NULL DEFAULT 0,
        cost_details TEXT         NULL,
        note         VARCHAR(500) NULL,
        created_at   BIGINT       NOT NULL DEFAULT 0,
        updated_at   BIGINT       NOT NULL DEFAULT 0
      )"
    );
    $pdo->exec("CREATE INDEX IF NOT EXISTS idx_trip_user_date ON trips (username, trip_date)");
  }
  // 既存の trips テーブルに origin 列が無ければ後付けする。
  try {
    if ($driver === 'sqlite') {
      $cols = array();
      foreach ($pdo->query("PRAGMA table_info(trips)")->fetchAll() as $r) { $cols[] = strtolower($r['name']); }
    } else {
      $sql = "SELECT COLUMN_NAME AS c FROM information_schema.columns WHERE table_name = 'trips'";
      if ($driver === 'mysql') $sql .= " AND table_schema = DATABASE()";
      $cols = array();
      foreach ($pdo->query($sql)->fetchAll() as $r) { $cols[] = strtolower($r['c']); }
    }
    if (!in_array('origin', $cols, true)) {
      $pdo->exec("ALTER TABLE trips ADD COLUMN origin VARCHAR(255) NULL");
    }
    if (!in_array('other_cost', $cols, true)) {
      $pdo->exec("ALTER TABLE trips ADD COLUMN other_cost $intType NOT NULL DEFAULT 0");
    }
    if (!in_array('cost_details', $cols, true)) {
      $pdo->exec("ALTER TABLE trips ADD COLUMN cost_details " . (($driver === 'mysql') ? 'MEDIUMTEXT' : 'TEXT') . " NULL");
    }
    // 車／電車の区分と、電車の運賃。既存レコードはすべて「車」として扱う。
    if (!in_array('mode', $cols, true)) {
      $pdo->exec("ALTER TABLE trips ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'car'");
    }
    if (!in_array('fare_cost', $cols, true)) {
      $pdo->exec("ALTER TABLE trips ADD COLUMN fare_cost $intType NOT NULL DEFAULT 0");
    }
  } catch (Throwable $e) { /* 追加できなくても致命ではない */ }
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

/* AI検索（意味で探す）用の索引。各項目の文章をGeminiで数値ベクトル（埋め込み）に変換して保存する。
   検索のたびに全項目をAIへ送る必要がなくなり、質問だけをベクトル化してサーバー内の計算で照合できる
   （＝速い・AIの利用回数が少ない）。text_hash は元の文章のハッシュで、変わったときだけ作り直す。 */
function cbc_init_vectors($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS node_vectors (
        node_id    VARCHAR(40)  NOT NULL PRIMARY KEY,
        text_hash  VARCHAR(64)  NOT NULL,
        dim        INT          NOT NULL DEFAULT 0,
        vec        MEDIUMTEXT   NULL,
        text       MEDIUMTEXT   NULL,
        updated_at BIGINT       NOT NULL DEFAULT 0
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS node_vectors (
        node_id    VARCHAR(40) NOT NULL PRIMARY KEY,
        text_hash  VARCHAR(64) NOT NULL,
        dim        INTEGER     NOT NULL DEFAULT 0,
        vec        TEXT        NULL,
        text       TEXT        NULL,
        updated_at BIGINT      NOT NULL DEFAULT 0
      )"
    );
  }
  // すでに node_vectors がある環境向け：後から追加した text 列を足す
  // （索引作成時の整形済みテキストを持たせ、検索のたびに本文のHTML除去をやり直さないため）
  try {
    if ($driver === 'sqlite') {
      $cols = array();
      foreach ($pdo->query("PRAGMA table_info(node_vectors)")->fetchAll() as $r) $cols[] = strtolower($r['name']);
    } else {
      $sql = "SELECT COLUMN_NAME AS c FROM information_schema.columns WHERE table_name = 'node_vectors'";
      if ($driver === 'mysql') $sql .= " AND table_schema = DATABASE()";
      $cols = array();
      foreach ($pdo->query($sql)->fetchAll() as $r) $cols[] = strtolower($r['c']);
    }
    if ($cols && !in_array('text', $cols, true)) {
      $pdo->exec("ALTER TABLE node_vectors ADD COLUMN text " . ($driver === 'mysql' ? 'MEDIUMTEXT' : 'TEXT') . " NULL");
    }
  } catch (Throwable $e) { /* 追加できなくても致命ではない（本文から都度作る動作に戻るだけ） */ }
}

/* 質問文のベクトルの使い回し用。同じ質問で再検索したときにAIへの問い合わせを省ける
   （キャッシュが効くと、その1回分の通信時間がまるごと無くなる）。 */
function cbc_init_qvectors($pdo, $driver) {
  if ($driver === 'mysql') {
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS query_vectors (
        q_hash     VARCHAR(64) NOT NULL PRIMARY KEY,
        vec        MEDIUMTEXT  NULL,
        created_at BIGINT      NOT NULL DEFAULT 0
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    );
  } else { // sqlite / pgsql
    $pdo->exec(
      "CREATE TABLE IF NOT EXISTS query_vectors (
        q_hash     VARCHAR(64) NOT NULL PRIMARY KEY,
        vec        TEXT        NULL,
        created_at BIGINT      NOT NULL DEFAULT 0
      )"
    );
  }
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

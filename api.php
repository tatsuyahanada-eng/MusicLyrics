<?php
/**
 * VertiCale — データベースへの保存と、画面でのログイン
 *
 * schedule.html と同じフォルダに置いてください。はじめて開いたときに、画面の「初期設定」で
 * データベースの種類と管理者アカウントを決めます（このファイルを書き換える必要はありません）。
 *
 *   かんたん（SQLite） … 設定不要。同じフォルダの data/ にデータベースのファイルを作ります。
 *   MySQL / MariaDB   … サーバのデータベース名・ユーザー名・パスワードを入力します。
 *
 * 設定は同じフォルダの config.php に保存されます。別のサーバへ移すとき・人に譲るときは、
 * config.sample.php を参考に config.php を用意しておくこともできます。
 *
 * PHP 7.2 以降（PDO と、pdo_sqlite か pdo_mysql）で動作します。
 */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Robots-Tag: noindex, nofollow');

define('VC_CONFIG_FILE', __DIR__ . '/config.php');
define('VC_DATA_DIR', __DIR__ . '/data');
define('VC_COOKIE', 'vc_sess');
define('VC_MAX_BODY_BYTES', 6291456);          // 6MB
define('VC_SESSION_LONG', 90 * 86400);         // 「ログインしたままにする」の期間
define('VC_SESSION_SHORT', 12 * 3600);
define('VC_EPOCH0', '1970-01-01T00:00:00.000Z');
define('VC_SCHEMA', '2');                        // テーブル構成の版（増えたテーブルは自動で作る）

/* ------------------------------------------------------------------
   共通
   ------------------------------------------------------------------ */

function vc_out($status, $body)
{
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function vc_fail($status, $message, $extra = array())
{
    vc_out($status, array_merge(array('ok' => false, 'error' => $message), $extra));
}

/** 送信されたJSONを読む（空の {} と [] を区別できるよう、連想配列ではなくオブジェクトで受ける） */
function vc_body()
{
    $raw = file_get_contents('php://input');
    if ($raw === false) {
        vc_fail(400, '送信内容を読み取れませんでした。');
    }
    if (strlen($raw) > VC_MAX_BODY_BYTES) {
        vc_fail(413, 'データが大きすぎます。');
    }
    $body = json_decode($raw);
    if (!is_object($body)) {
        vc_fail(400, '送信内容の形式が正しくありません。');
    }
    return $body;
}

function vc_str($obj, $key, $default = '')
{
    return (is_object($obj) && isset($obj->$key) && is_scalar($obj->$key)) ? (string) $obj->$key : $default;
}

/** 文字数で切り詰める（列の長さに収めるため。元の内容は data 列にすべて残す） */
function vc_cut($s, $n)
{
    $s = (string) $s;
    if (preg_match('/^.{0,' . (int) $n . '}/us', $s, $m)) {
        return $m[0];
    }
    return '';
}

function vc_is_https()
{
    if (!empty($_SERVER['HTTPS']) && strtolower($_SERVER['HTTPS']) !== 'off') {
        return true;
    }
    return isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && strtolower($_SERVER['HTTP_X_FORWARDED_PROTO']) === 'https';
}

function vc_client_ip()
{
    return isset($_SERVER['REMOTE_ADDR']) ? substr((string) $_SERVER['REMOTE_ADDR'], 0, 64) : '';
}

/** データを書き換える通信は、この画面からの通信であることを確かめる（他サイトからの送信を防ぐ） */
function vc_require_app_request()
{
    $h = isset($_SERVER['HTTP_X_REQUESTED_WITH']) ? $_SERVER['HTTP_X_REQUESTED_WITH'] : '';
    if ($h !== 'VertiCale') {
        vc_fail(403, 'この操作は画面から行ってください。');
    }
}

/* ------------------------------------------------------------------
   設定ファイルとデータベース
   ------------------------------------------------------------------ */

function vc_config()
{
    if (!is_file(VC_CONFIG_FILE)) {
        return null;
    }
    $cfg = include VC_CONFIG_FILE;
    return (is_array($cfg) && isset($cfg['db']) && is_array($cfg['db'])) ? $cfg : null;
}

function vc_write_config($cfg)
{
    $php = "<?php\n// VertiCale の設定（初期設定で自動作成）。このファイルは外部に公開しないでください。\nreturn " . var_export($cfg, true) . ";\n";
    if (@file_put_contents(VC_CONFIG_FILE, $php, LOCK_EX) === false) {
        vc_fail(500, '設定ファイル（config.php）を作成できませんでした。フォルダの書き込み権限を確認するか、config.sample.php を参考に config.php を作成してください。');
    }
    @chmod(VC_CONFIG_FILE, 0600);
    if (function_exists('opcache_invalidate')) {
        @opcache_invalidate(VC_CONFIG_FILE, true);
    }
}

/** data/ フォルダを用意し、外部から直接読めないようにする */
function vc_prepare_data_dir()
{
    if (!is_dir(VC_DATA_DIR)) {
        if (!@mkdir(VC_DATA_DIR, 0700, true) && !is_dir(VC_DATA_DIR)) {
            vc_fail(500, 'データの保存先フォルダ（data）を作成できませんでした。サーバの書き込み権限をご確認ください。');
        }
    }
    if (!is_writable(VC_DATA_DIR)) {
        vc_fail(500, 'データの保存先フォルダ（data）に書き込みできません。サーバの権限設定をご確認ください。');
    }
    $htaccess = VC_DATA_DIR . '/.htaccess';
    if (!file_exists($htaccess)) {
        $rules = "<IfModule mod_authz_core.c>\n  Require all denied\n</IfModule>\n"
               . "<IfModule !mod_authz_core.c>\n  Order allow,deny\n  Deny from all\n</IfModule>\n";
        @file_put_contents($htaccess, $rules);
    }
    if (!file_exists(VC_DATA_DIR . '/index.html')) {
        @file_put_contents(VC_DATA_DIR . '/index.html', '');
    }
}

function vc_driver($cfg)
{
    return (isset($cfg['db']['driver']) && $cfg['db']['driver'] === 'mysql') ? 'mysql' : 'sqlite';
}

function vc_connect($cfg)
{
    $db = $cfg['db'];
    $opts = array(
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    );
    if (vc_driver($cfg) === 'mysql') {
        $dsn = 'mysql:host=' . (isset($db['host']) ? $db['host'] : 'localhost')
             . (empty($db['port']) ? '' : ';port=' . (int) $db['port'])
             . ';dbname=' . (isset($db['name']) ? $db['name'] : '')
             . ';charset=utf8mb4';
        return new PDO($dsn, isset($db['user']) ? $db['user'] : '', isset($db['pass']) ? $db['pass'] : '', $opts);
    }
    $path = isset($db['path']) ? $db['path'] : 'data/verticale.sqlite';
    if ($path === '' || ($path[0] !== '/' && !preg_match('/^[A-Za-z]:[\\\\\\/]/', $path))) {
        $path = __DIR__ . '/' . $path;   // 相対パスはこのフォルダからの位置（フォルダごと移しても動く）
    }
    $pdo = new PDO('sqlite:' . $path, null, null, $opts);
    $pdo->exec('PRAGMA foreign_keys = ON');
    $pdo->exec('PRAGMA busy_timeout = 5000');
    return $pdo;
}

/** テーブルを作る（すでにあれば何もしない） */
function vc_schema($pdo, $driver)
{
    $my   = $driver === 'mysql';
    $pk   = $my ? 'INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY' : 'INTEGER PRIMARY KEY AUTOINCREMENT';
    $txt  = $my ? 'MEDIUMTEXT' : 'TEXT';
    $tail = $my ? ' ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin' : '';
    $jobIdx = $my ? ', KEY vc_jobs_date (user_id, job_date)' : '';

    $ddl = array(
        "CREATE TABLE IF NOT EXISTS vc_meta (k VARCHAR(64) NOT NULL PRIMARY KEY, v TEXT)$tail",
        "CREATE TABLE IF NOT EXISTS vc_users (
            id $pk,
            login VARCHAR(64) NOT NULL UNIQUE,
            pass_hash VARCHAR(255) NOT NULL,
            name VARCHAR(100) NOT NULL DEFAULT '',
            role VARCHAR(16) NOT NULL DEFAULT 'user',
            created_at VARCHAR(40) NOT NULL,
            updated_at VARCHAR(40) NOT NULL
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_sessions (
            token_hash CHAR(64) NOT NULL PRIMARY KEY,
            user_id INT NOT NULL,
            keep_login INT NOT NULL DEFAULT 1,
            created_at INT NOT NULL,
            expires_at INT NOT NULL
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_login_attempts (
            id $pk,
            login VARCHAR(64) NOT NULL,
            ip VARCHAR(64) NOT NULL,
            tried_at INT NOT NULL
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_user_state (
            user_id INT NOT NULL PRIMARY KEY,
            rev INT NOT NULL DEFAULT 0,
            updated_at VARCHAR(40),
            settings $txt,
            settings_at VARCHAR(40)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_jobs (
            user_id INT NOT NULL,
            id VARCHAR(64) NOT NULL,
            job_date VARCHAR(10) NOT NULL,
            all_day INT NOT NULL DEFAULT 0,
            start_time VARCHAR(5) NOT NULL DEFAULT '',
            end_time VARCHAR(5) NOT NULL DEFAULT '',
            title VARCHAR(255) NOT NULL DEFAULT '',
            subject VARCHAR(255) NOT NULL DEFAULT '',
            work_type VARCHAR(255) NOT NULL DEFAULT '',
            status VARCHAR(16) NOT NULL DEFAULT 'confirmed',
            updated_at VARCHAR(40) NOT NULL,
            data $txt NOT NULL,
            PRIMARY KEY (user_id, id)$jobIdx
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_job_tombstones (
            user_id INT NOT NULL, id VARCHAR(64) NOT NULL, updated_at VARCHAR(40) NOT NULL,
            PRIMARY KEY (user_id, id)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_wishes (
            user_id INT NOT NULL,
            wish_date VARCHAR(10) NOT NULL,
            wish VARCHAR(16) NOT NULL DEFAULT '',
            updated_at VARCHAR(40) NOT NULL,
            PRIMARY KEY (user_id, wish_date)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_todos (
            user_id INT NOT NULL,
            id VARCHAR(64) NOT NULL,
            title VARCHAR(255) NOT NULL DEFAULT '',
            category VARCHAR(16) NOT NULL DEFAULT 'work',
            due_date VARCHAR(10) NOT NULL DEFAULT '',
            done INT NOT NULL DEFAULT 0,
            updated_at VARCHAR(40) NOT NULL,
            data $txt NOT NULL,
            PRIMARY KEY (user_id, id)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_todo_tombstones (
            user_id INT NOT NULL, id VARCHAR(64) NOT NULL, updated_at VARCHAR(40) NOT NULL,
            PRIMARY KEY (user_id, id)
        )$tail",
        // ルーチン（繰り返しの作業）と、その日ごとのチェックの記録
        "CREATE TABLE IF NOT EXISTS vc_routines (
            user_id INT NOT NULL,
            id VARCHAR(64) NOT NULL,
            title VARCHAR(255) NOT NULL DEFAULT '',
            kind VARCHAR(16) NOT NULL DEFAULT 'daily',
            updated_at VARCHAR(40) NOT NULL,
            data $txt NOT NULL,
            PRIMARY KEY (user_id, id)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_routine_tombstones (
            user_id INT NOT NULL, id VARCHAR(64) NOT NULL, updated_at VARCHAR(40) NOT NULL,
            PRIMARY KEY (user_id, id)
        )$tail",
        "CREATE TABLE IF NOT EXISTS vc_routine_log (
            user_id INT NOT NULL,
            item_key VARCHAR(100) NOT NULL,
            log_date VARCHAR(10) NOT NULL,
            status VARCHAR(8) NOT NULL DEFAULT '',
            updated_at VARCHAR(40) NOT NULL,
            PRIMARY KEY (user_id, item_key)
        )$tail",
    );
    foreach ($ddl as $sql) {
        $pdo->exec($sql);
    }
    if (!$my) {
        $pdo->exec('CREATE INDEX IF NOT EXISTS vc_jobs_date ON vc_jobs (user_id, job_date)');
    }
}

function vc_meta_get($pdo, $key)
{
    $st = $pdo->prepare('SELECT v FROM vc_meta WHERE k = ?');
    $st->execute(array($key));
    $v = $st->fetchColumn();
    return $v === false ? null : $v;
}

function vc_meta_set($pdo, $key, $value)
{
    $pdo->prepare('DELETE FROM vc_meta WHERE k = ?')->execute(array($key));
    $pdo->prepare('INSERT INTO vc_meta (k, v) VALUES (?, ?)')->execute(array($key, $value));
}

function vc_user_count($pdo)
{
    return (int) $pdo->query('SELECT COUNT(*) FROM vc_users')->fetchColumn();
}

/* ------------------------------------------------------------------
   ログイン（Cookie にはランダムな値だけを入れ、データベースにはそのハッシュを保存する）
   ------------------------------------------------------------------ */

function vc_cookie_path()
{
    $dir = isset($_SERVER['SCRIPT_NAME']) ? str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'])) : '/';
    return rtrim($dir, '/') . '/';
}

function vc_set_cookie($value, $maxAge)
{
    $parts = array(VC_COOKIE . '=' . $value, 'Path=' . vc_cookie_path(), 'HttpOnly', 'SameSite=Lax');
    if ($maxAge !== null) {
        $parts[] = 'Max-Age=' . (int) $maxAge;
    }
    if (vc_is_https()) {
        $parts[] = 'Secure';
    }
    header('Set-Cookie: ' . implode('; ', $parts), false);
}

function vc_public_user($row)
{
    return array('id' => (int) $row['id'], 'login' => $row['login'], 'name' => $row['name'], 'role' => $row['role']);
}

function vc_start_session($pdo, $userId, $persistent)
{
    $token = bin2hex(random_bytes(32));
    $ttl = $persistent ? VC_SESSION_LONG : VC_SESSION_SHORT;
    $pdo->prepare('INSERT INTO vc_sessions (token_hash, user_id, keep_login, created_at, expires_at) VALUES (?, ?, ?, ?, ?)')
        ->execute(array(hash('sha256', $token), (int) $userId, $persistent ? 1 : 0, time(), time() + $ttl));
    vc_set_cookie($token, $persistent ? $ttl : null);
}

/** ログイン中のユーザー（いなければ null）。期限が近づいたら延長する */
function vc_current_user($pdo)
{
    if (empty($_COOKIE[VC_COOKIE]) || !is_string($_COOKIE[VC_COOKIE]) || !preg_match('/^[0-9a-f]{64}$/', $_COOKIE[VC_COOKIE])) {
        return null;
    }
    $token = $_COOKIE[VC_COOKIE];
    $hash = hash('sha256', $token);
    $st = $pdo->prepare('SELECT s.keep_login, s.expires_at, u.id, u.login, u.name, u.role
                           FROM vc_sessions s JOIN vc_users u ON u.id = s.user_id
                          WHERE s.token_hash = ? AND s.expires_at > ?');
    $st->execute(array($hash, time()));
    $row = $st->fetch();
    if (!$row) {
        return null;
    }
    $ttl = ((int) $row['keep_login']) ? VC_SESSION_LONG : VC_SESSION_SHORT;
    if ((int) $row['expires_at'] - time() < $ttl / 2) {
        $pdo->prepare('UPDATE vc_sessions SET expires_at = ? WHERE token_hash = ?')->execute(array(time() + $ttl, $hash));
        vc_set_cookie($token, ((int) $row['keep_login']) ? $ttl : null);
    }
    return vc_public_user($row);
}

function vc_require_user($pdo)
{
    $user = vc_current_user($pdo);
    if (!$user) {
        vc_fail(401, 'ログインが必要です。', array('code' => 'login_required'));
    }
    return $user;
}

function vc_require_admin($pdo)
{
    $user = vc_require_user($pdo);
    if ($user['role'] !== 'admin') {
        vc_fail(403, 'この操作は管理者だけが行えます。');
    }
    return $user;
}

function vc_clean_login($login)
{
    $login = strtolower(trim((string) $login));
    if (!preg_match('/^[a-z0-9][a-z0-9._@-]{2,63}$/', $login)) {
        vc_fail(400, 'ログインIDは、半角の英数字と . _ - @ で3〜64文字にしてください。');
    }
    return $login;
}

function vc_check_password($password)
{
    if (!is_string($password) || strlen($password) < 8) {
        vc_fail(400, 'パスワードは8文字以上にしてください。');
    }
    if (strlen($password) > 200) {
        vc_fail(400, 'パスワードが長すぎます。');
    }
    return $password;
}

function vc_now_iso()
{
    return gmdate('Y-m-d\TH:i:s') . '.000Z';
}

function vc_create_user($pdo, $login, $password, $name, $role)
{
    $now = vc_now_iso();
    $pdo->prepare('INSERT INTO vc_users (login, pass_hash, name, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)')
        ->execute(array($login, password_hash($password, PASSWORD_DEFAULT), vc_cut($name, 100), $role === 'admin' ? 'admin' : 'user', $now, $now));
    return (int) $pdo->lastInsertId();
}

/* ------------------------------------------------------------------
   予定データの読み書き
   ------------------------------------------------------------------ */

function vc_state_row($pdo, $userId)
{
    $st = $pdo->prepare('SELECT rev, updated_at, settings, settings_at FROM vc_user_state WHERE user_id = ?');
    $st->execute(array($userId));
    $row = $st->fetch();
    return $row ? $row : array('rev' => 0, 'updated_at' => null, 'settings' => null, 'settings_at' => null);
}

/** データベースの内容を、画面が扱う形（1つのJSON）に組み立てる */
function vc_load_state($pdo, $userId)
{
    $meta = vc_state_row($pdo, $userId);
    $rev = (int) $meta['rev'];
    if ($rev === 0) {
        return array('rev' => 0, 'updatedAt' => null, 'jobs' => 0, 'data' => null);
    }

    $jobs = array();
    $st = $pdo->prepare('SELECT data FROM vc_jobs WHERE user_id = ? ORDER BY job_date, start_time, id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $job = json_decode($row['data']);
        if (is_object($job)) {
            $jobs[] = $job;
        }
    }

    $todos = array();
    $st = $pdo->prepare('SELECT data FROM vc_todos WHERE user_id = ? ORDER BY id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $todo = json_decode($row['data']);
        if (is_object($todo)) {
            $todos[] = $todo;
        }
    }

    $wishes = new stdClass();
    $wishMeta = new stdClass();
    $st = $pdo->prepare('SELECT wish_date, wish, updated_at FROM vc_wishes WHERE user_id = ? ORDER BY wish_date');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $d = $row['wish_date'];
        $wishMeta->$d = $row['updated_at'];
        if ($row['wish'] !== '') {
            $wishes->$d = $row['wish'];
        }
    }

    $tombs = array();
    $st = $pdo->prepare('SELECT id, updated_at FROM vc_job_tombstones WHERE user_id = ? ORDER BY id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $tombs[] = array('id' => $row['id'], 'updatedAt' => $row['updated_at']);
    }

    $todoTombs = array();
    $st = $pdo->prepare('SELECT id, updated_at FROM vc_todo_tombstones WHERE user_id = ? ORDER BY id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $todoTombs[] = array('id' => $row['id'], 'updatedAt' => $row['updated_at']);
    }

    $routines = array();
    $st = $pdo->prepare('SELECT data FROM vc_routines WHERE user_id = ? ORDER BY id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $r = json_decode($row['data']);
        if (is_object($r)) {
            $routines[] = $r;
        }
    }

    $routineTombs = array();
    $st = $pdo->prepare('SELECT id, updated_at FROM vc_routine_tombstones WHERE user_id = ? ORDER BY id');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $routineTombs[] = array('id' => $row['id'], 'updatedAt' => $row['updated_at']);
    }

    $routineLog = new stdClass();
    $st = $pdo->prepare('SELECT item_key, status, updated_at FROM vc_routine_log WHERE user_id = ? ORDER BY item_key');
    $st->execute(array($userId));
    foreach ($st as $row) {
        $k = $row['item_key'];
        $routineLog->$k = array('s' => $row['status'], 'at' => $row['updated_at']);
    }

    $settings = $meta['settings'] !== null ? json_decode($meta['settings']) : null;
    $data = array(
        'version'        => 2,
        'settings'       => is_object($settings) ? $settings : new stdClass(),
        'settingsAt'     => $meta['settings_at'] ? $meta['settings_at'] : VC_EPOCH0,
        'wishes'         => $wishes,
        'wishMeta'       => $wishMeta,
        'jobs'           => $jobs,
        'tombstones'     => $tombs,
        'todos'          => $todos,
        'todoTombstones' => $todoTombs,
        'routines'       => $routines,
        'routineTombstones' => $routineTombs,
        'routineLog'     => $routineLog,
    );
    return array('rev' => $rev, 'updatedAt' => $meta['updated_at'], 'jobs' => count($jobs), 'data' => $data);
}

function vc_valid_id($v)
{
    return is_string($v) && $v !== '' && strlen($v) <= 64;
}

function vc_valid_date($v)
{
    return is_string($v) && preg_match('/^\d{4}-\d{2}-\d{2}$/', $v);
}

function vc_time_col($v)
{
    return (is_string($v) && preg_match('/^\d{1,2}:\d{2}$/', $v)) ? str_pad($v, 5, '0', STR_PAD_LEFT) : '';
}

function vc_stamp($v)
{
    return (is_string($v) && $v !== '' && strlen($v) <= 40) ? $v : VC_EPOCH0;
}

function vc_begin($pdo, $driver)
{
    if ($driver === 'mysql') {
        $pdo->beginTransaction();
    } else {
        $pdo->exec('BEGIN IMMEDIATE');   // 読み取りから書き込みまで、ほかの保存を待たせる
    }
}

function vc_commit($pdo, $driver)
{
    if ($driver === 'mysql') {
        $pdo->commit();
    } else {
        $pdo->exec('COMMIT');
    }
}

function vc_rollback($pdo, $driver)
{
    try {
        if ($driver === 'mysql') {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
        } else {
            $pdo->exec('ROLLBACK');
        }
    } catch (Exception $e) {
        // すでに終わっている場合は何もしない
    }
}

/** そのユーザーの予定データをすべて消す（アカウントは残す） */
function vc_delete_user_rows($pdo, $userId)
{
    foreach (array('vc_jobs', 'vc_job_tombstones', 'vc_wishes', 'vc_todos', 'vc_todo_tombstones', 'vc_routines', 'vc_routine_tombstones', 'vc_routine_log') as $table) {
        $pdo->prepare("DELETE FROM $table WHERE user_id = ?")->execute(array($userId));
    }
}

/**
 * 画面から届いた内容を保存する。
 * 画面が最後に読んだ版（baseRev）とデータベースの版が違えば、他の端末が先に保存しているので
 * 保存せずに最新の内容を返す（画面側で突き合わせてから、もう一度送ってくる）。
 */
function vc_save_state($pdo, $driver, $userId, $body)
{
    if (!isset($body->data) || !is_object($body->data)) {
        vc_fail(400, '保存するデータがありません。');
    }
    $data = $body->data;
    $baseRev = isset($body->baseRev) ? (int) $body->baseRev : 0;
    $force = !empty($body->force);

    vc_begin($pdo, $driver);
    try {
        $st = $pdo->prepare('SELECT COUNT(*) FROM vc_user_state WHERE user_id = ?');
        $st->execute(array($userId));
        if (!(int) $st->fetchColumn()) {
            $pdo->prepare('INSERT INTO vc_user_state (user_id, rev) VALUES (?, 0)')->execute(array($userId));
        }
        $st = $pdo->prepare('SELECT rev FROM vc_user_state WHERE user_id = ?' . ($driver === 'mysql' ? ' FOR UPDATE' : ''));
        $st->execute(array($userId));
        $rev = (int) $st->fetchColumn();

        if (!$force && $rev !== $baseRev) {
            $current = vc_load_state($pdo, $userId);
            vc_commit($pdo, $driver);
            vc_out(409, array_merge(array('ok' => false, 'error' => 'conflict'), $current));
        }

        // ルーチンを知らない古い版の画面から届いたときは、ルーチンの記録は消さずに残す
        $hasRoutines = property_exists($data, 'routines');
        foreach (array('vc_jobs', 'vc_job_tombstones', 'vc_wishes', 'vc_todos', 'vc_todo_tombstones') as $table) {
            $pdo->prepare("DELETE FROM $table WHERE user_id = ?")->execute(array($userId));
        }
        if ($hasRoutines) {
            foreach (array('vc_routines', 'vc_routine_tombstones', 'vc_routine_log') as $table) {
                $pdo->prepare("DELETE FROM $table WHERE user_id = ?")->execute(array($userId));
            }
        }

        $jobs = (isset($data->jobs) && is_array($data->jobs)) ? $data->jobs : array();
        $ins = $pdo->prepare('INSERT INTO vc_jobs (user_id, id, job_date, all_day, start_time, end_time, title, subject, work_type, status, updated_at, data)
                              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $seen = array();
        foreach ($jobs as $job) {
            if (!is_object($job) || !isset($job->id, $job->date) || !vc_valid_id($job->id) || !vc_valid_date($job->date) || isset($seen[$job->id])) {
                continue;
            }
            $seen[$job->id] = true;
            $ins->execute(array(
                $userId, $job->id, $job->date, empty($job->allDay) ? 0 : 1,
                vc_time_col(isset($job->start) ? $job->start : ''), vc_time_col(isset($job->end) ? $job->end : ''),
                vc_cut(vc_str($job, 'title'), 255), vc_cut(vc_str($job, 'subject'), 255), vc_cut(vc_str($job, 'workType'), 255),
                vc_str($job, 'status') === 'tentative' ? 'tentative' : 'confirmed',
                vc_stamp(isset($job->updatedAt) ? $job->updatedAt : null),
                json_encode($job, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            ));
        }

        $todos = (isset($data->todos) && is_array($data->todos)) ? $data->todos : array();
        $ins = $pdo->prepare('INSERT INTO vc_todos (user_id, id, title, category, due_date, done, updated_at, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
        $seen = array();
        foreach ($todos as $todo) {
            if (!is_object($todo) || !isset($todo->id) || !vc_valid_id($todo->id) || isset($seen[$todo->id])) {
                continue;
            }
            $seen[$todo->id] = true;
            $due = vc_str($todo, 'dueDate');
            $ins->execute(array(
                $userId, $todo->id, vc_cut(vc_str($todo, 'title'), 255),
                vc_str($todo, 'category') === 'personal' ? 'personal' : 'work',
                vc_valid_date($due) ? $due : '', empty($todo->done) ? 0 : 1,
                vc_stamp(isset($todo->updatedAt) ? $todo->updatedAt : null),
                json_encode($todo, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            ));
        }

        // 希望（稼働可／休み希望）。未定に戻した日も、更新時刻だけ残す
        $wishes = (isset($data->wishes) && is_object($data->wishes)) ? get_object_vars($data->wishes) : array();
        $wishMeta = (isset($data->wishMeta) && is_object($data->wishMeta)) ? get_object_vars($data->wishMeta) : array();
        $ins = $pdo->prepare('INSERT INTO vc_wishes (user_id, wish_date, wish, updated_at) VALUES (?, ?, ?, ?)');
        foreach (array_unique(array_merge(array_keys($wishMeta), array_keys($wishes))) as $d) {
            $d = (string) $d;
            if (!vc_valid_date($d)) {
                continue;
            }
            $w = isset($wishes[$d]) ? $wishes[$d] : '';
            $w = ($w === 'available' || $w === 'off') ? $w : '';
            $ins->execute(array($userId, $d, $w, vc_stamp(isset($wishMeta[$d]) ? $wishMeta[$d] : null)));
        }

        foreach (array('tombstones' => 'vc_job_tombstones', 'todoTombstones' => 'vc_todo_tombstones') as $key => $table) {
            $list = (isset($data->$key) && is_array($data->$key)) ? $data->$key : array();
            $ins = $pdo->prepare("INSERT INTO $table (user_id, id, updated_at) VALUES (?, ?, ?)");
            $seen = array();
            foreach ($list as $t) {
                if (!is_object($t) || !isset($t->id) || !vc_valid_id($t->id) || isset($seen[$t->id])) {
                    continue;
                }
                $seen[$t->id] = true;
                $ins->execute(array($userId, $t->id, vc_stamp(isset($t->updatedAt) ? $t->updatedAt : null)));
            }
        }

        if ($hasRoutines) {
            $list = is_array($data->routines) ? $data->routines : array();
            $ins = $pdo->prepare('INSERT INTO vc_routines (user_id, id, title, kind, updated_at, data) VALUES (?, ?, ?, ?, ?, ?)');
            $seen = array();
            foreach ($list as $r) {
                if (!is_object($r) || !isset($r->id) || !vc_valid_id($r->id) || isset($seen[$r->id])) {
                    continue;
                }
                $seen[$r->id] = true;
                $ins->execute(array(
                    $userId, $r->id, vc_cut(vc_str($r, 'title'), 255), vc_cut(vc_str($r, 'kind', 'daily'), 16),
                    vc_stamp(isset($r->updatedAt) ? $r->updatedAt : null),
                    json_encode($r, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
                ));
            }
            $list = (isset($data->routineTombstones) && is_array($data->routineTombstones)) ? $data->routineTombstones : array();
            $ins = $pdo->prepare('INSERT INTO vc_routine_tombstones (user_id, id, updated_at) VALUES (?, ?, ?)');
            $seen = array();
            foreach ($list as $t) {
                if (!is_object($t) || !isset($t->id) || !vc_valid_id($t->id) || isset($seen[$t->id])) {
                    continue;
                }
                $seen[$t->id] = true;
                $ins->execute(array($userId, $t->id, vc_stamp(isset($t->updatedAt) ? $t->updatedAt : null)));
            }
            $log = (isset($data->routineLog) && is_object($data->routineLog)) ? get_object_vars($data->routineLog) : array();
            $ins = $pdo->prepare('INSERT INTO vc_routine_log (user_id, item_key, log_date, status, updated_at) VALUES (?, ?, ?, ?, ?)');
            foreach ($log as $k => $v) {
                $k = (string) $k;
                if (strlen($k) > 100 || !vc_valid_date(substr($k, 0, 10)) || !is_object($v)) {
                    continue;
                }
                $s = vc_str($v, 's');
                $ins->execute(array($userId, $k, substr($k, 0, 10), ($s === 'done' || $s === 'skip') ? $s : '', vc_stamp(isset($v->at) ? $v->at : null)));
            }
        }

        $settings = (isset($data->settings) && is_object($data->settings)) ? $data->settings : new stdClass();
        $now = vc_now_iso();
        $pdo->prepare('UPDATE vc_user_state SET rev = ?, updated_at = ?, settings = ?, settings_at = ? WHERE user_id = ?')
            ->execute(array(
                $rev + 1, $now, json_encode($settings, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
                vc_stamp(isset($data->settingsAt) ? $data->settingsAt : null), $userId,
            ));

        vc_commit($pdo, $driver);
        vc_out(200, array('ok' => true, 'rev' => $rev + 1, 'updatedAt' => $now));
    } catch (Exception $e) {
        vc_rollback($pdo, $driver);
        throw $e;
    }
}

/* ------------------------------------------------------------------
   ここから処理
   ------------------------------------------------------------------ */

$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';
$action = isset($_GET['action']) && is_string($_GET['action']) ? $_GET['action'] : 'status';

$drivers = class_exists('PDO') ? PDO::getAvailableDrivers() : array();
$hasSqlite = in_array('sqlite', $drivers, true);
$hasMysql = in_array('mysql', $drivers, true);

try {
    $cfg = vc_config();
    $pdo = null;
    $driver = 'sqlite';
    $installed = false;
    if ($cfg) {
        $driver = vc_driver($cfg);
        $pdo = vc_connect($cfg);
        try {
            $installed = vc_user_count($pdo) > 0;
        } catch (PDOException $e) {
            vc_schema($pdo, $driver);   // まだテーブルが無い（config.php を先に用意した場合など）
            $installed = vc_user_count($pdo) > 0;
        }
        // 前の版から更新したときは、新しく増えたテーブルを作る（既存のデータはそのまま）
        if ($installed && vc_meta_get($pdo, 'schema') !== VC_SCHEMA) {
            vc_schema($pdo, $driver);
            vc_meta_set($pdo, 'schema', VC_SCHEMA);
        }
    }

    /* ---- 状態の確認（ログイン画面を出すか、初期設定を出すかを画面が決める） ---- */
    if ($action === 'status') {
        $user = ($pdo && $installed) ? vc_current_user($pdo) : null;
        vc_out(200, array(
            'ok'        => true,
            'app'       => 'verticale',
            'installed' => $installed,
            'hasConfig' => (bool) $cfg,
            'drivers'   => array('sqlite' => $hasSqlite, 'mysql' => $hasMysql),
            'loggedIn'  => (bool) $user,
            'user'      => $user,
            'site'      => ($pdo && $installed) ? vc_meta_get($pdo, 'site') : null,
        ));
    }

    /* ---- 初期設定（まだ誰も登録されていないときだけ） ---- */
    if ($action === 'setup') {
        if ($method !== 'POST') {
            vc_fail(405, '対応していない通信方法です。');
        }
        vc_require_app_request();
        if ($installed) {
            vc_fail(403, '初期設定はすでに完了しています。');
        }
        $body = vc_body();
        $login = vc_clean_login(vc_str($body, 'login'));
        $password = vc_check_password(isset($body->password) ? $body->password : null);
        $name = trim(vc_str($body, 'name'));

        if (!$cfg) {
            $want = (isset($body->db) && is_object($body->db)) ? $body->db : new stdClass();
            if (vc_str($want, 'driver') === 'mysql') {
                if (!$hasMysql) {
                    vc_fail(400, 'このサーバでは MySQL に接続できません（pdo_mysql がありません）。');
                }
                $newCfg = array('db' => array(
                    'driver' => 'mysql',
                    'host'   => vc_str($want, 'host', 'localhost'),
                    'port'   => (int) vc_str($want, 'port', '0'),
                    'name'   => vc_str($want, 'name'),
                    'user'   => vc_str($want, 'user'),
                    'pass'   => vc_str($want, 'pass'),
                ));
                if ($newCfg['db']['name'] === '' || $newCfg['db']['user'] === '') {
                    vc_fail(400, 'データベース名とユーザー名を入力してください。');
                }
            } else {
                if (!$hasSqlite) {
                    vc_fail(400, 'このサーバでは SQLite が使えません。MySQL を選んでください。');
                }
                vc_prepare_data_dir();
                $newCfg = array('db' => array('driver' => 'sqlite', 'path' => 'data/vc-' . bin2hex(random_bytes(12)) . '.sqlite'));
            }
            try {
                $driver = vc_driver($newCfg);
                $pdo = vc_connect($newCfg);
                vc_schema($pdo, $driver);
            } catch (Exception $e) {
                vc_fail(400, 'データベースに接続できませんでした。入力内容をご確認ください。（' . $e->getMessage() . '）');
            }
            if (vc_user_count($pdo) > 0) {
                vc_fail(403, 'このデータベースには、すでにアカウントが登録されています。config.php を用意して、登録済みのアカウントでログインしてください。');
            }
            vc_write_config($newCfg);
            $cfg = $newCfg;
        }

        $userId = vc_create_user($pdo, $login, $password, $name !== '' ? $name : $login, 'admin');
        vc_meta_set($pdo, 'site', bin2hex(random_bytes(8)));
        vc_meta_set($pdo, 'schema', VC_SCHEMA);
        vc_start_session($pdo, $userId, true);
        $st = $pdo->prepare('SELECT id, login, name, role FROM vc_users WHERE id = ?');
        $st->execute(array($userId));
        vc_out(200, array('ok' => true, 'user' => vc_public_user($st->fetch()), 'site' => vc_meta_get($pdo, 'site'), 'driver' => $driver));
    }

    if (!$pdo || !$installed) {
        vc_fail(503, '初期設定がまだ終わっていません。', array('code' => 'setup_required'));
    }

    // たまに、期限切れのログイン情報と古い失敗の記録を片付ける
    if (mt_rand(1, 25) === 1) {
        $pdo->prepare('DELETE FROM vc_sessions WHERE expires_at < ?')->execute(array(time()));
        $pdo->prepare('DELETE FROM vc_login_attempts WHERE tried_at < ?')->execute(array(time() - 86400));
    }

    /* ---- ログイン ---- */
    if ($action === 'login') {
        if ($method !== 'POST') {
            vc_fail(405, '対応していない通信方法です。');
        }
        vc_require_app_request();
        $body = vc_body();
        $login = strtolower(trim(vc_str($body, 'login')));
        $password = isset($body->password) && is_string($body->password) ? $body->password : '';
        $ip = vc_client_ip();

        // 続けて間違えたときは、しばらく受け付けない（総当たりを防ぐ）
        $since = time() - 900;
        $st = $pdo->prepare('SELECT COUNT(*) FROM vc_login_attempts WHERE login = ? AND tried_at > ?');
        $st->execute(array(substr($login, 0, 64), $since));
        $byLogin = (int) $st->fetchColumn();
        $st = $pdo->prepare('SELECT COUNT(*) FROM vc_login_attempts WHERE ip = ? AND tried_at > ?');
        $st->execute(array($ip, $since));
        $byIp = (int) $st->fetchColumn();
        if ($byLogin >= 8 || $byIp >= 40) {
            vc_fail(429, 'ログインの失敗が続いたため、一時的に受け付けを止めています。15分ほど待ってからお試しください。');
        }

        $st = $pdo->prepare('SELECT id, login, name, role, pass_hash FROM vc_users WHERE login = ?');
        $st->execute(array($login));
        $row = $st->fetch();
        // 登録のないIDでも同じだけ時間をかけて、IDの有無を応答の速さから推測されないようにする
        $hash = $row ? $row['pass_hash'] : '$2y$10$usesomesillystringfore7hnbRJHxXVLeakoG8K30oukPsA.ztMG';
        $ok = password_verify($password, $hash) && $row;
        if (!$ok) {
            $pdo->prepare('INSERT INTO vc_login_attempts (login, ip, tried_at) VALUES (?, ?, ?)')->execute(array(substr($login, 0, 64), $ip, time()));
            vc_fail(401, 'ログインIDかパスワードが違います。', array('code' => 'bad_credentials'));
        }
        $pdo->prepare('DELETE FROM vc_login_attempts WHERE login = ?')->execute(array($login));
        if (password_needs_rehash($row['pass_hash'], PASSWORD_DEFAULT)) {
            $pdo->prepare('UPDATE vc_users SET pass_hash = ? WHERE id = ?')->execute(array(password_hash($password, PASSWORD_DEFAULT), $row['id']));
        }
        vc_start_session($pdo, $row['id'], !isset($body->remember) || !empty($body->remember));
        vc_out(200, array('ok' => true, 'user' => vc_public_user($row), 'site' => vc_meta_get($pdo, 'site')));
    }

    /* ---- ログアウト ---- */
    if ($action === 'logout') {
        if ($method !== 'POST') {
            vc_fail(405, '対応していない通信方法です。');
        }
        vc_require_app_request();
        if (!empty($_COOKIE[VC_COOKIE]) && is_string($_COOKIE[VC_COOKIE])) {
            $pdo->prepare('DELETE FROM vc_sessions WHERE token_hash = ?')->execute(array(hash('sha256', $_COOKIE[VC_COOKIE])));
        }
        vc_set_cookie('', 0);
        vc_out(200, array('ok' => true));
    }

    /* ---- パスワードの変更（ほかの端末はログアウトさせる） ---- */
    if ($action === 'password') {
        if ($method !== 'POST') {
            vc_fail(405, '対応していない通信方法です。');
        }
        vc_require_app_request();
        $user = vc_require_user($pdo);
        $body = vc_body();
        $next = vc_check_password(isset($body->next) ? $body->next : null);
        $st = $pdo->prepare('SELECT pass_hash FROM vc_users WHERE id = ?');
        $st->execute(array($user['id']));
        if (!password_verify(isset($body->current) && is_string($body->current) ? $body->current : '', (string) $st->fetchColumn())) {
            vc_fail(400, '現在のパスワードが違います。');
        }
        $pdo->prepare('UPDATE vc_users SET pass_hash = ?, updated_at = ? WHERE id = ?')
            ->execute(array(password_hash($next, PASSWORD_DEFAULT), vc_now_iso(), $user['id']));
        $pdo->prepare('DELETE FROM vc_sessions WHERE user_id = ? AND token_hash <> ?')
            ->execute(array($user['id'], hash('sha256', $_COOKIE[VC_COOKIE])));
        vc_out(200, array('ok' => true));
    }

    /* ---- 予定データ ---- */
    if ($action === 'state') {
        $user = vc_require_user($pdo);
        if ($method === 'GET') {
            vc_out(200, array_merge(array('ok' => true), vc_load_state($pdo, $user['id'])));
        }
        vc_require_app_request();
        if ($method === 'POST') {
            vc_save_state($pdo, $driver, $user['id'], vc_body());
        }
        if ($method === 'DELETE') {
            vc_begin($pdo, $driver);
            try {
                vc_delete_user_rows($pdo, $user['id']);
                $pdo->prepare('DELETE FROM vc_user_state WHERE user_id = ?')->execute(array($user['id']));
                vc_commit($pdo, $driver);
            } catch (Exception $e) {
                vc_rollback($pdo, $driver);
                throw $e;
            }
            vc_out(200, array('ok' => true, 'rev' => 0, 'updatedAt' => null));
        }
        vc_fail(405, '対応していない通信方法です。');
    }

    /* ---- ユーザーの管理（管理者のみ） ---- */
    if ($action === 'users') {
        $admin = vc_require_admin($pdo);
        if ($method === 'GET') {
            $rows = $pdo->query('SELECT u.id, u.login, u.name, u.role, u.created_at,
                                        (SELECT COUNT(*) FROM vc_jobs j WHERE j.user_id = u.id) AS jobs
                                   FROM vc_users u ORDER BY u.id')->fetchAll();
            $list = array();
            foreach ($rows as $r) {
                $list[] = array('id' => (int) $r['id'], 'login' => $r['login'], 'name' => $r['name'], 'role' => $r['role'],
                                'createdAt' => $r['created_at'], 'jobs' => (int) $r['jobs']);
            }
            vc_out(200, array('ok' => true, 'users' => $list));
        }
        if ($method !== 'POST') {
            vc_fail(405, '対応していない通信方法です。');
        }
        vc_require_app_request();
        $body = vc_body();
        $op = vc_str($body, 'op');

        if ($op === 'add') {
            $login = vc_clean_login(vc_str($body, 'login'));
            $password = vc_check_password(isset($body->password) ? $body->password : null);
            $st = $pdo->prepare('SELECT COUNT(*) FROM vc_users WHERE login = ?');
            $st->execute(array($login));
            if ((int) $st->fetchColumn()) {
                vc_fail(400, 'そのログインIDは、すでに使われています。');
            }
            $name = trim(vc_str($body, 'name'));
            $id = vc_create_user($pdo, $login, $password, $name !== '' ? $name : $login, vc_str($body, 'role') === 'admin' ? 'admin' : 'user');
            vc_out(200, array('ok' => true, 'id' => $id));
        }

        $targetId = isset($body->id) ? (int) $body->id : 0;
        $st = $pdo->prepare('SELECT id, role FROM vc_users WHERE id = ?');
        $st->execute(array($targetId));
        $target = $st->fetch();
        if (!$target) {
            vc_fail(404, 'そのユーザーは見つかりません。');
        }

        if ($op === 'reset') {
            $password = vc_check_password(isset($body->password) ? $body->password : null);
            $pdo->prepare('UPDATE vc_users SET pass_hash = ?, updated_at = ? WHERE id = ?')
                ->execute(array(password_hash($password, PASSWORD_DEFAULT), vc_now_iso(), $targetId));
            if ($targetId !== $admin['id']) {
                $pdo->prepare('DELETE FROM vc_sessions WHERE user_id = ?')->execute(array($targetId));
            }
            vc_out(200, array('ok' => true));
        }

        if ($op === 'delete') {
            if ($targetId === $admin['id']) {
                vc_fail(400, '自分自身のアカウントは削除できません。');
            }
            vc_begin($pdo, $driver);
            try {
                vc_delete_user_rows($pdo, $targetId);
                $pdo->prepare('DELETE FROM vc_user_state WHERE user_id = ?')->execute(array($targetId));
                $pdo->prepare('DELETE FROM vc_sessions WHERE user_id = ?')->execute(array($targetId));
                $pdo->prepare('DELETE FROM vc_users WHERE id = ?')->execute(array($targetId));
                vc_commit($pdo, $driver);
            } catch (Exception $e) {
                vc_rollback($pdo, $driver);
                throw $e;
            }
            vc_out(200, array('ok' => true));
        }

        vc_fail(400, '対応していない操作です。');
    }

    vc_fail(404, '対応していない操作です。');
} catch (PDOException $e) {
    error_log('VertiCale api.php: ' . $e->getMessage());
    vc_fail(500, 'データベースの処理に失敗しました。時間をおいてもう一度お試しください。');
} catch (Exception $e) {
    error_log('VertiCale api.php: ' . $e->getMessage());
    vc_fail(500, 'サーバの処理に失敗しました。');
}

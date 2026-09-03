<?php
/* ============================================================
   Case By Case — backup.php
   完全バックアップ（JSON）の作成まわり。
   api.php（アクセス時の自動バックアップ・手動エクスポート）と
   backup-cron.php（さくら等のcronで時刻を決めて実行）の両方から使う。
   どちらから作っても中身がまったく同じになるよう、ここに1本化している。
   ============================================================ */

if (!defined('BACKUP_DIR')) define('BACKUP_DIR', __DIR__ . '/backups'); // 保存先
// アクセス時（誰かが画面を開いたとき）の自動バックアップを行うか。
// cronで確実に走るようにしたあとは、config.php に
//   define('AUTO_BACKUP_ON_ACCESS', false);
// と書けば止められる。既定は true（従来どおり）。
if (!defined('AUTO_BACKUP_ON_ACCESS')) define('AUTO_BACKUP_ON_ACCESS', true);
// 何日分の自動バックアップを残すか。0 は「消さない」（既定）。
// 例）define('BACKUP_KEEP_DAYS', 90); で90日より古いものを自動削除。
if (!defined('BACKUP_KEEP_DAYS')) define('BACKUP_KEEP_DAYS', 0);

// 現在時刻（ミリ秒）。api.php にも同じものがあるので、無いときだけ用意する
// （cronから backup.php だけを読み込んだ場合のため）。
if (!function_exists('now_ms')) {
  function now_ms() { return (int) round(microtime(true) * 1000); }
}

/* ---------- 完全バックアップ（手動エクスポート／自動バックアップ 共通） ---------- */
// 色・文字サイズ・入力欄（本文HTML）・ユーザー権限（パスワードハッシュ含む）・
// 在庫・交通費・設定を、無加工でそのまま書き出す。
function cbc_build_backup_array($pdo) {
  $nodes    = $pdo->query('SELECT id, parent_id, sort_order, title, body, created_by, updated_by, updated_at, created_at, lock_hash FROM nodes ORDER BY parent_id, sort_order, created_at')->fetchAll();
  $users    = $pdo->query('SELECT username, display_name, pass_hash, is_admin, allowed, created_at, updated_at FROM users ORDER BY username')->fetchAll();
  $invItems = $pdo->query('SELECT id, name, model, qty, note, sort_order, created_at, updated_at FROM inv_items ORDER BY sort_order, created_at')->fetchAll();
  $invLogs  = $pdo->query('SELECT id, item_id, action, qty, balance, person, note, created_at FROM inv_logs ORDER BY created_at, id')->fetchAll();
  $trips    = array();
  try {
    $trips = $pdo->query('SELECT id, username, display_name, trip_date, case_name, mode, origin, destination, one_way_km, round_trip, gas_rate, gas_cost, fare_cost, toll_cost, parking_cost, other_cost, total, cost_details, note, created_at, updated_at FROM trips ORDER BY trip_date, created_at')->fetchAll();
  } catch (Throwable $e) { /* trips 未作成でも致命ではない */ }
  $settings = $pdo->query('SELECT k, v FROM app_settings')->fetchAll();
  return array(
    'app'         => 'case-by-case',
    'version'     => 1,
    'exported_at' => now_ms(),
    'nodes'       => $nodes,
    'users'       => $users,
    'inv_items'   => $invItems,
    'inv_logs'    => $invLogs,
    'trips'       => $trips,
    'settings'    => $settings,
  );
}

/* ---------- 自動バックアップ（backups/backup-YYYY-MM-DD.json） ---------- */
function cbc_backup_filename($ymd) { return 'backup-' . $ymd . '.json'; }
function cbc_backup_path($ymd) { return rtrim(BACKUP_DIR, '/') . '/' . cbc_backup_filename($ymd); }

// その日ぶんのバックアップを1つ作る。
//   $force = false … すでにその日のファイルがあれば何もしない
//   $force = true  … あっても作り直す（cronの手動実行用）
// 戻り値: array(できたか(bool), メッセージ(string), ファイルのパス(string))
// 複数の処理がほぼ同時に走っても二重に作らないよう、簡易ロックをかける。
function cbc_write_backup($pdo, $ymd = null, $force = false) {
  if ($ymd === null) $ymd = date('Y-m-d');
  $path = cbc_backup_path($ymd);
  if (!$force && file_exists($path)) return array(false, 'すでに本日ぶんがあります', $path);
  if (!is_dir(BACKUP_DIR)) @mkdir(BACKUP_DIR, 0775, true);
  if (!is_dir(BACKUP_DIR)) return array(false, '保存先フォルダを作れません: ' . BACKUP_DIR, $path);

  $lockPath = rtrim(BACKUP_DIR, '/') . '/.lock';
  $fh = @fopen($lockPath, 'c');
  if (!$fh) return array(false, 'ロックファイルを作れません', $path);
  if (!flock($fh, LOCK_EX | LOCK_NB)) { fclose($fh); return array(false, '他の処理が実行中です', $path); }

  $done = false; $msg = '';
  try {
    if ($force || !file_exists($path)) {
      $data = cbc_build_backup_array($pdo);
      $data['auto'] = true;
      $json = json_encode($data, JSON_UNESCAPED_UNICODE);
      if ($json === false) {
        $msg = 'JSONへの変換に失敗しました';
      } else if (@file_put_contents($path, $json, LOCK_EX) === false) {
        $msg = 'ファイルを書き込めません: ' . $path;
      } else {
        $done = true; $msg = '作成しました（' . number_format(strlen($json)) . ' バイト）';
      }
    } else {
      $msg = 'すでに本日ぶんがあります';
    }
  } catch (Throwable $e) {
    $msg = 'エラー: ' . $e->getMessage();
  }
  flock($fh, LOCK_UN);
  fclose($fh);
  return array($done, $msg, $path);
}

// 古い自動バックアップを削除する（BACKUP_KEEP_DAYS 日より前のもの）。
// 0 のときは何も消さない。戻り値: 消したファイル名の配列。
function cbc_prune_backups($keepDays = null) {
  if ($keepDays === null) $keepDays = (int) BACKUP_KEEP_DAYS;
  $keepDays = (int) $keepDays;
  if ($keepDays <= 0 || !is_dir(BACKUP_DIR)) return array();
  $limit = date('Y-m-d', strtotime('-' . $keepDays . ' days'));
  $removed = array();
  foreach (scandir(BACKUP_DIR) as $f) {
    if (!preg_match('/^backup-(\d{4}-\d{2}-\d{2})\.json$/', $f, $m)) continue;
    if (strcmp($m[1], $limit) >= 0) continue; // 期間内は残す
    if (@unlink(rtrim(BACKUP_DIR, '/') . '/' . $f)) $removed[] = $f;
  }
  return $removed;
}

// 誰かが画面を開いたときに呼ばれる、従来からの自動バックアップ。
// 今日ぶんがまだ無く、正午（サーバー時刻）を過ぎていれば作成する。
// アクセスのたびに呼ばれる想定のため、通常は file_exists のチェックのみで即戻る（軽量）。
// cron で先に作られていれば、ここは何もせずに戻る。
// 失敗してもアプリの動作に影響させない（ベストエフォート）。
function cbc_maybe_auto_backup($pdo) {
  try {
    if (!AUTO_BACKUP_ON_ACCESS) return;   // cronに任せる設定のときは何もしない
    if ((int)date('G') < 12) return;      // 正午（12時）より前は対象外
    if (file_exists(cbc_backup_path(date('Y-m-d')))) return;
    cbc_write_backup($pdo);
    cbc_prune_backups();
  } catch (Throwable $e) { /* 自動バックアップの失敗はアプリ動作に影響させない */ }
}

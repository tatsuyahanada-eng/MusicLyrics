<?php
/** データベース接続（PDO / MySQL） */
declare(strict_types=1);

function lp_config(): array
{
    static $config = null;
    if ($config === null) {
        $path = __DIR__ . '/config.php';
        if (!is_file($path)) {
            http_response_code(500);
            exit('設定ファイル includes/config.php がありません。config.sample.php をコピーして作成してください。');
        }
        $config = require $path;
    }
    return $config;
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $c = lp_config();

    // 'db_dsn' が設定されていればそれを優先（動作検証用。本番では未設定のままにします）
    $dsn = $c['db_dsn'] ?? sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
        $c['db_host'],
        (int)($c['db_port'] ?? 3306),
        $c['db_name']
    );

    try {
        $pdo = new PDO($dsn, $c['db_user'] ?? null, $c['db_pass'] ?? null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,
        ]);
    } catch (PDOException $e) {
        error_log('[library-portal] DB connect failed: ' . $e->getMessage());
        http_response_code(500);
        exit('データベースに接続できません。includes/config.php の設定をご確認ください。');
    }
    return $pdo;
}

/**
 * その列がテーブルにあるかどうか。
 *
 * ファイルだけ新しくしてデータベースの更新（sql/upgrade.sql）をまだ流していない、
 * という状態でも画面が真っ白にならないように、後から足した列は
 * 「あれば使う」扱いにしています。1リクエストにつき1回だけ調べます。
 */
function lp_has_column(string $table, string $column): bool
{
    static $cache = [];
    $key = $table . '.' . $column;
    if (!array_key_exists($key, $cache)) {
        try {
            $st = db()->prepare(
                'SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?'
            );
            $st->execute([$table, $column]);
            $cache[$key] = (int)$st->fetchColumn() > 0;
        } catch (PDOException $e) {
            error_log('[library-portal] column check failed: ' . $e->getMessage());
            $cache[$key] = false;
        }
    }
    return $cache[$key];
}

/** 画面を出すのに足りない列があれば、その一覧を返す（空なら更新済み） */
function lp_missing_columns(): array
{
    $need = [
        'lp_items.series'      => ['lp_items', 'series'],
        'lp_updates.bump_type' => ['lp_updates', 'bump_type'],
    ];
    $missing = [];
    foreach ($need as $label => [$table, $column]) {
        if (!lp_has_column($table, $column)) {
            $missing[] = $label;
        }
    }
    return $missing;
}

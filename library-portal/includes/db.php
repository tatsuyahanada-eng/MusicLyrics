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

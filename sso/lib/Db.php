<?php
declare(strict_types=1);

/** PDO の薄いラッパー。クエリは全てプリペアドステートメントで実行する。 */
final class Db
{
    private static ?PDO $pdo = null;

    public static function init(array $cfg): void
    {
        self::$pdo = null;
        self::$cfg = $cfg;
    }

    /** @var array<string,mixed> */
    private static array $cfg = [];

    public static function pdo(): PDO
    {
        if (self::$pdo === null) {
            self::$pdo = new PDO(
                (string) (self::$cfg['dsn'] ?? ''),
                (string) (self::$cfg['user'] ?? ''),
                (string) (self::$cfg['pass'] ?? ''),
                [
                    PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
                    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                    PDO::ATTR_EMULATE_PREPARES   => false,
                ] + (array) (self::$cfg['options'] ?? [])
            );

            // DB の時刻を PHP のタイムゾーンに合わせる。
            // これをしないと NOW() と PHP 側で作った日時がずれ、
            // セッションの有効期限判定が狂う。
            try {
                $offset = (new DateTimeImmutable('now'))->format('P');   // 例: +09:00
                self::$pdo->exec("SET time_zone = '{$offset}'");
            } catch (PDOException $e) {
                // タイムゾーンテーブル未整備などで失敗しても致命傷ではない
                error_log('[welsys-sso] failed to set db time_zone: ' . $e->getMessage());
            }
        }
        return self::$pdo;
    }

    public static function run(string $sql, array $params = []): PDOStatement
    {
        $stmt = self::pdo()->prepare($sql);
        $stmt->execute($params);
        return $stmt;
    }

    /** @return array<string,mixed>|null */
    public static function one(string $sql, array $params = []): ?array
    {
        $row = self::run($sql, $params)->fetch();
        return $row === false ? null : $row;
    }

    /** @return list<array<string,mixed>> */
    public static function all(string $sql, array $params = []): array
    {
        return self::run($sql, $params)->fetchAll();
    }

    /** @return mixed */
    public static function value(string $sql, array $params = [])
    {
        $v = self::run($sql, $params)->fetchColumn();
        return $v === false ? null : $v;
    }

    public static function insertId(): int
    {
        return (int) self::pdo()->lastInsertId();
    }

    /**
     * トランザクション。入れ子で呼ばれた場合は、外側のトランザクションに相乗りする
     * （PDO は入れ子の beginTransaction を許さないため）。
     */
    public static function transaction(callable $fn)
    {
        $pdo = self::pdo();
        if ($pdo->inTransaction()) {
            return $fn($pdo);
        }
        $pdo->beginTransaction();
        try {
            $result = $fn($pdo);
            $pdo->commit();
            return $result;
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
            throw $e;
        }
    }
}

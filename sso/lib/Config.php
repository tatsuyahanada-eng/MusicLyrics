<?php
declare(strict_types=1);

/** 設定へのドット記法アクセス。 */
final class Config
{
    /** @var array<string,mixed> */
    private static array $values = [];

    public static function init(array $values): void
    {
        self::$values = $values;
    }

    /** @return mixed */
    public static function get(string $path, $default = null)
    {
        $node = self::$values;
        foreach (explode('.', $path) as $key) {
            if (!is_array($node) || !array_key_exists($key, $node)) {
                return $default;
            }
            $node = $node[$key];
        }
        return $node;
    }

    public static function baseUrl(string $path = ''): string
    {
        $base = rtrim((string) self::get('base_url', ''), '/');
        if ($path === '') {
            return $base;
        }
        return $base . '/' . ltrim($path, '/');
    }
}

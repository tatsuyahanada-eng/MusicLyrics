<?php
declare(strict_types=1);

/** HTML エスケープ。 */
function h($value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/** 302 リダイレクトして終了する。 */
function redirect(string $url): never
{
    header('Location: ' . $url);
    exit;
}

/** JSON を返して終了する。 */
function json_out(array $payload, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=UTF-8');
    header('Cache-Control: no-store');
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function client_ip(): string
{
    return substr((string) ($_SERVER['REMOTE_ADDR'] ?? ''), 0, 45);
}

function user_agent(): string
{
    return substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255);
}

/** 推測不可能なトークン（16進64文字）。 */
function random_token(): string
{
    return bin2hex(random_bytes(32));
}

/** トークンは平文で保存せず、この値を DB に入れる。 */
function token_hash(string $token): string
{
    return hash('sha256', $token);
}

function now(): string
{
    return date('Y-m-d H:i:s');
}

function at(int $timestamp): string
{
    return date('Y-m-d H:i:s', $timestamp);
}

/** 画面に一度だけ出すメッセージを積む。 */
function flash(string $type, string $message): void
{
    $_SESSION['_flash'][] = ['type' => $type, 'message' => $message];
}

/** @return list<array{type:string,message:string}> */
function take_flashes(): array
{
    $flashes = $_SESSION['_flash'] ?? [];
    unset($_SESSION['_flash']);
    return is_array($flashes) ? $flashes : [];
}

function post(string $key, string $default = ''): string
{
    $v = $_POST[$key] ?? $default;
    return is_string($v) ? trim($v) : $default;
}

function query(string $key, string $default = ''): string
{
    $v = $_GET[$key] ?? $default;
    return is_string($v) ? trim($v) : $default;
}

/**
 * 戻り先URLがそのアプリの base_url 配下かを検証する（オープンリダイレクト対策）。
 */
function url_is_within(string $url, string $base): bool
{
    $u = parse_url($url);
    $b = parse_url(rtrim($base, '/'));
    if (!is_array($u) || !is_array($b)) {
        return false;
    }
    foreach (['scheme', 'host'] as $part) {
        if (!isset($u[$part], $b[$part]) || strcasecmp((string) $u[$part], (string) $b[$part]) !== 0) {
            return false;
        }
    }
    if (($u['port'] ?? null) !== ($b['port'] ?? null)) {
        return false;
    }
    $basePath = rtrim((string) ($b['path'] ?? ''), '/');
    $urlPath  = (string) ($u['path'] ?? '/');
    if ($basePath === '') {
        return true;
    }
    return $urlPath === $basePath || str_starts_with($urlPath, $basePath . '/');
}

/** クエリ文字列を足した URL を作る。 */
function url_with(string $url, array $params): string
{
    if ($params === []) {
        return $url;
    }
    return $url . (str_contains($url, '?') ? '&' : '?') . http_build_query($params);
}

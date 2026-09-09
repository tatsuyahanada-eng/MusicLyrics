<?php
/** 共通ヘルパー */
declare(strict_types=1);

/** HTML エスケープ */
function h(?string $s): string
{
    return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/** JSON を返して終了 */
function json_out($data, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=UTF-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** エラー JSON を返して終了 */
function json_error(string $message, int $status = 400): void
{
    json_out(['error' => $message], $status);
}

/** リクエストボディの JSON を配列で取得 */
function json_body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

/** 文字列を取り出して trim（最大長で切り詰め） */
function s(array $src, string $key, int $max = 500): string
{
    $v = isset($src[$key]) && is_scalar($src[$key]) ? trim((string)$src[$key]) : '';
    return mb_substr($v, 0, $max);
}

/** 日付（Y-m-d）として妥当か */
function valid_date(string $v): bool
{
    $d = DateTime::createFromFormat('Y-m-d', $v);
    return $d !== false && $d->format('Y-m-d') === $v;
}

/** 時刻（H:i）として妥当か */
function valid_time(string $v): bool
{
    return (bool)preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $v);
}

/** http(s) の URL か（空文字は許可） */
function valid_url(string $v): bool
{
    return $v === '' || (bool)preg_match('#^https?://#i', $v);
}

/** 操作ログを記録 */
function audit(string $action, ?string $target = null, ?string $detail = null): void
{
    try {
        $user = current_user();
        $st = db()->prepare(
            'INSERT INTO lp_audit_log (user_id, login_id, action, target, detail, ip_address)
             VALUES (?, ?, ?, ?, ?, ?)'
        );
        $st->execute([
            $user['user_id'] ?? null,
            $user['login_id'] ?? null,
            $action,
            $target !== null ? mb_substr($target, 0, 120) : null,
            $detail !== null ? mb_substr($detail, 0, 500) : null,
            $_SERVER['REMOTE_ADDR'] ?? null,
        ]);
    } catch (Throwable $e) {
        error_log('[library-portal] audit failed: ' . $e->getMessage());
    }
}

/* ============================================================
   版数の採番
     ・最初の登録は 1.00
     ・通常の更新は 1.1 → 1.2 …（マイナーが上がる）
     ・微修正は 1.1 → 1.11 → 1.12 …（リビジョンが上がる）
   桁があふれたときは繰り上げる（1.9 の次は 2.00、1.19 の次は 1.2）ので、
   同じ表記が二度出ることはありません。
   ============================================================ */

/** major / minor / revision を「1.00」「1.1」「1.11」の形にする */
function lp_version_label(int $major, int $minor, int $rev): string
{
    if ($minor === 0 && $rev === 0) {
        return $major . '.00';
    }
    return $rev === 0 ? "{$major}.{$minor}" : "{$major}.{$minor}{$rev}";
}

/**
 * 更新履歴（古い順）から、各更新時点の版数を順に求める。
 *
 * @param array $bumps 各更新の 'minor'（通常）または 'revision'（微修正）
 * @return string[]    古い順の版数
 */
function lp_version_series(array $bumps): array
{
    $major = 1;
    $minor = 0;
    $rev   = 0;
    $out   = [];

    foreach ($bumps as $i => $bump) {
        if ($i === 0) {
            // 最初の登録は必ず 1.00
        } elseif ($bump === 'revision') {
            $rev++;
            if ($rev > 9) { $rev = 0; $minor++; }        // 1.19 の次は 1.2
            if ($minor > 9) { $minor = 0; $major++; }
        } else {
            $minor++;
            $rev = 0;
            if ($minor > 9) { $minor = 0; $major++; }    // 1.9 の次は 2.00
        }
        $out[] = lp_version_label($major, $minor, $rev);
    }
    return $out;
}

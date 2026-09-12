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

/* ============================================================
   カテゴリ（種別）
   ============================================================ */

/** プリセットの配色キー一覧（PALETTE と対応）。この他に #rrggbb のカスタム色も許可する */
function lp_category_colors(): array
{
    return ['navy', 'graphite', 'tan', 'maroon', 'forest', 'plum', 'rust', 'denim', 'charcoal'];
}

/** 選べるアイコンキー（assets/library.js の ICONS と対応） */
function lp_category_icons(): array
{
    return ['app', 'code', 'doc', 'book', 'folder', 'tag', 'star', 'flag'];
}

/**
 * カテゴリ一覧を並び順で返す。
 * sql/upgrade.sql をまだ実行していない（lp_categories が無い）サーバーでは、
 * これまで固定だった4種類をそのまま返すので、画面が真っ白になったり
 * 種別が選べなくなったりしない。
 */
function lp_categories_list(): array
{
    if (!lp_has_table('lp_categories')) {
        return [
            ['code' => 'APP', 'label' => 'アプリ',     'color' => 'navy',     'icon' => 'app'],
            ['code' => 'PRG', 'label' => 'プログラム', 'color' => 'graphite', 'icon' => 'code'],
            ['code' => 'DOC', 'label' => '資料',       'color' => 'tan',      'icon' => 'doc'],
            ['code' => 'MAN', 'label' => 'マニュアル', 'color' => 'maroon',   'icon' => 'book'],
        ];
    }
    return db()->query(
        'SELECT code, label, color, icon FROM lp_categories ORDER BY sort_no, category_id'
    )->fetchAll();
}

/** 登録済みカテゴリの表示名（label）だけの一覧。lp_items.category の検証に使う */
function lp_category_labels(): array
{
    return array_column(lp_categories_list(), 'label');
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
 * アイテムの登録時点を Ver1.00 とし、そこから最初の更新も含めて毎回
 * バージョンアップとして数える（1回目の更新で 1.1、微修正なら 1.01）。
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

    foreach ($bumps as $bump) {
        if ($bump === 'revision') {
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

/* ============================================================
   添付ファイル（画像・PDF・ZIP）のアップロード
   ============================================================ */

/** アップロードを許可する拡張子と、それぞれで許容する MIME タイプ */
function lp_upload_allowed_types(): array
{
    return [
        'jpg'  => ['image/jpeg'],
        'jpeg' => ['image/jpeg'],
        'png'  => ['image/png'],
        'gif'  => ['image/gif'],
        'webp' => ['image/webp'],
        'pdf'  => ['application/pdf'],
        // ZIP は OS やブラウザによって報告される MIME がまちまちなので幅を持たせる
        'zip'  => ['application/zip', 'application/x-zip-compressed', 'application/octet-stream'],
    ];
}

/** 添付ファイルの上限サイズ（バイト）。config.php で upload_max_bytes を指定すればそれに従う */
function lp_upload_max_bytes(): int
{
    $c = lp_config();
    return (int)($c['upload_max_bytes'] ?? (20 * 1024 * 1024)); // 既定 20MB
}

/**
 * $_FILES の1件を検証する。
 * 問題なければ ['ok' => true, 'ext' => ..., 'mime' => ...]、
 * 問題があれば ['ok' => false, 'error' => 'ユーザー向けメッセージ'] を返す。
 */
function lp_validate_upload(array $file): array
{
    $code = $file['error'] ?? UPLOAD_ERR_NO_FILE;
    if ($code === UPLOAD_ERR_NO_FILE) {
        return ['ok' => false, 'error' => null]; // ファイルは選ばれていない（エラーではない）
    }
    if ($code !== UPLOAD_ERR_OK) {
        $messages = [
            UPLOAD_ERR_INI_SIZE   => 'ファイルサイズが大きすぎます。',
            UPLOAD_ERR_FORM_SIZE  => 'ファイルサイズが大きすぎます。',
            UPLOAD_ERR_PARTIAL    => 'アップロードが途中で失敗しました。もう一度お試しください。',
            UPLOAD_ERR_NO_TMP_DIR => 'サーバー側の一時保存先の設定に問題があります。',
            UPLOAD_ERR_CANT_WRITE => 'サーバーへの書き込みに失敗しました。',
        ];
        return ['ok' => false, 'error' => $messages[$code] ?? 'アップロードに失敗しました。'];
    }

    $tmp = $file['tmp_name'] ?? '';
    if ($tmp === '' || !is_uploaded_file($tmp)) {
        return ['ok' => false, 'error' => 'アップロードに失敗しました。'];
    }

    $max = lp_upload_max_bytes();
    if ((int)($file['size'] ?? 0) > $max) {
        $mb = number_format($max / (1024 * 1024), 0);
        return ['ok' => false, 'error' => "ファイルサイズが大きすぎます（上限 {$mb}MB）。"];
    }

    $name = (string)($file['name'] ?? '');
    $ext  = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    $allowed = lp_upload_allowed_types();
    if ($ext === '' || !isset($allowed[$ext])) {
        return ['ok' => false, 'error' => '画像（jpg / png / gif / webp）・PDF・ZIP のみアップロードできます。'];
    }

    // 拡張子を偽装しただけのファイルを弾くため、中身の MIME も確認する
    $finfo = finfo_open(FILEINFO_MIME_TYPE);
    $mime  = $finfo ? finfo_file($finfo, $tmp) : false;
    if ($finfo) { finfo_close($finfo); }
    if ($mime === false || !in_array($mime, $allowed[$ext], true)) {
        return ['ok' => false, 'error' => 'ファイルの種類が確認できませんでした（拡張子と中身が一致しません）。'];
    }

    return ['ok' => true, 'ext' => $ext, 'mime' => $mime];
}

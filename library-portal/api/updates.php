<?php
/**
 * POST api/updates.php … 更新履歴を1件登録（管理者のみ）
 * PUT  api/updates.php … 登録済みの更新履歴を1件修正（管理者のみ）
 */
declare(strict_types=1);
require_once __DIR__ . '/../includes/auth.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
if ($method !== 'POST' && $method !== 'PUT') {
    json_error('許可されていないメソッドです。', 405);
}
$isEdit = ($method === 'PUT');

$user = api_require_admin();
api_verify_csrf();

$b       = json_body();
$uid     = isset($b['uid']) ? (int)$b['uid'] : 0;
$itemId  = s($b, 'itemId', 20);
$date    = s($b, 'date', 10);
$time    = s($b, 'time', 5);
$author  = s($b, 'author', 60);
$kind    = s($b, 'kind', 20);
$version = s($b, 'version', 20);
$summary = s($b, 'summary', 500);
$target  = s($b, 'target', 200);
$ticket  = s($b, 'ticket', 30);
$url     = s($b, 'downloadUrl', 500);
$files   = isset($b['files']) && is_array($b['files']) ? $b['files'] : [];

$allowedKind = ['機能追加', '不具合修正', '改善', '資料改訂', '初版公開'];
if (!valid_date($date))  json_error('更新日が不正です。');
if (!valid_time($time))  json_error('時間が不正です。');
if ($author === '')      json_error('対応者は必須です。');
if ($summary === '')     json_error('更新内容は必須です。');
if ($target === '')      json_error('対象機能は必須です。');
if (!in_array($kind, $allowedKind, true)) json_error('区分が不正です。');
if (!valid_url($url))    json_error('URLは http:// または https:// で入力してください。');
if ($isEdit && $uid <= 0) json_error('修正する更新履歴が指定されていません。');

$chk = db()->prepare('SELECT 1 FROM lp_items WHERE item_id = ? AND is_active = 1');
$chk->execute([$itemId]);
if (!$chk->fetchColumn()) {
    json_error('対象アイテムが見つかりません。');
}

if ($isEdit) {
    $chkU = db()->prepare('SELECT 1 FROM lp_updates WHERE update_id = ?');
    $chkU->execute([$uid]);
    if (!$chkU->fetchColumn()) json_error('修正する更新履歴が見つかりません。', 404);
}

$pdo = db();
try {
    $pdo->beginTransaction();

    if ($isEdit) {
        $st = $pdo->prepare(
            'UPDATE lp_updates
                SET item_id = ?, updated_on = ?, updated_time = ?, author = ?, update_kind = ?,
                    version = ?, summary = ?, target_feature = ?, ticket_no = ?
              WHERE update_id = ?'
        );
        $st->execute([
            $itemId, $date, $time . ':00', $author, $kind,
            $version !== '' ? $version : null, $summary, $target,
            $ticket !== '' ? $ticket : null, $uid,
        ]);
        $updateId = $uid;
        // 修正したファイルは入れ替える（残したまま足すと重複するため）
        $pdo->prepare('DELETE FROM lp_update_files WHERE update_id = ?')->execute([$updateId]);
    } else {
        $st = $pdo->prepare(
            'INSERT INTO lp_updates
               (item_id, updated_on, updated_time, author, author_user_id, update_kind, version, summary, target_feature, ticket_no)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
        );
        $st->execute([
            $itemId, $date, $time . ':00', $author, $user['user_id'], $kind,
            $version !== '' ? $version : null, $summary, $target, $ticket !== '' ? $ticket : null,
        ]);
        $updateId = (int)$pdo->lastInsertId();
    }

    if ($files) {
        $fs = $pdo->prepare('INSERT INTO lp_update_files (update_id, file_path, change_note, sort_no) VALUES (?, ?, ?, ?)');
        $no = 0;
        foreach ($files as $line) {
            if (!is_scalar($line)) continue;
            $line = trim((string)$line);
            if ($line === '') continue;
            // 「ファイル名 : 修正内容」の形式は分割して保存する
            $parts = preg_split('/\s+:\s+/u', $line, 2);
            $path  = mb_substr($parts[0], 0, 300);
            $note  = isset($parts[1]) ? mb_substr($parts[1], 0, 300) : null;
            $fs->execute([$updateId, $path, $note, ++$no]);
            if ($no >= 50) break;   // 1更新あたりの上限
        }
    }

    if ($url !== '') {
        $up = $pdo->prepare('UPDATE lp_items SET download_url = ? WHERE item_id = ?');
        $up->execute([$url, $itemId]);
    }

    $pdo->commit();
} catch (Throwable $e) {
    if ($pdo->inTransaction()) $pdo->rollBack();
    error_log('[library-portal] update save failed: ' . $e->getMessage());
    json_error(($isEdit ? '修正' : '登録') . 'に失敗しました。時間をおいて再度お試しください。', 500);
}

audit($isEdit ? 'update.edit' : 'update.create', $itemId, mb_substr($summary, 0, 200));
json_out(['ok' => true, 'updateId' => $updateId], $isEdit ? 200 : 201);

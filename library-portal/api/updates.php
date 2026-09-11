<?php
/**
 * POST   api/updates.php … 更新履歴を1件登録（管理者のみ）
 * PUT    api/updates.php … 登録済みの更新履歴を1件修正（管理者のみ）
 * DELETE api/updates.php … 登録済みの更新履歴を1件削除（管理者のみ）
 *
 * 添付ファイル（画像・PDF・ZIP）を送る場合は multipart/form-data、
 * 送らない場合は今まで通り JSON のどちらでも受け付ける。
 */
declare(strict_types=1);
require_once __DIR__ . '/../includes/auth.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
if (!in_array($method, ['POST', 'PUT', 'DELETE'], true)) {
    json_error('許可されていないメソッドです。', 405);
}

$user = api_require_admin();
api_verify_csrf();

if ($method === 'DELETE') {
    $b = json_body();
    $uid = isset($b['uid']) ? (int)$b['uid'] : 0;
    if ($uid <= 0) json_error('削除する更新履歴が指定されていません。');

    $pdo = db();
    $hasFileCols = lp_has_column('lp_updates', 'file_path');
    $st = $pdo->prepare($hasFileCols
        ? 'SELECT item_id, file_path FROM lp_updates WHERE update_id = ?'
        : 'SELECT item_id, NULL AS file_path FROM lp_updates WHERE update_id = ?');
    $st->execute([$uid]);
    $row = $st->fetch();
    if ($row === false) json_error('対象の更新履歴が見つかりません。', 404);

    try {
        $pdo->beginTransaction();
        $pdo->prepare('DELETE FROM lp_update_files WHERE update_id = ?')->execute([$uid]);
        $pdo->prepare('DELETE FROM lp_updates WHERE update_id = ?')->execute([$uid]);
        $pdo->commit();
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        error_log('[library-portal] update delete failed: ' . $e->getMessage());
        json_error('削除に失敗しました。時間をおいて再度お試しください。', 500);
    }

    // 添付ファイルは行の削除が確定してから消す（先に消すと失敗時に孤立ファイルが残らない）
    if (!empty($row['file_path'])) {
        $full = __DIR__ . '/../' . $row['file_path'];
        if (is_file($full) && !@unlink($full)) {
            error_log('[library-portal] failed to remove attachment on delete: ' . $row['file_path']);
        }
    }

    audit('update.delete', $row['item_id'], 'update_id=' . $uid);
    json_out(['ok' => true]);
}

$isEdit = ($method === 'PUT');

// ブラウザからは PUT で multipart/form-data を直接送れないため、
// 修正は POST + _method=PUT のフォーム送信も受け付ける（apiSendForm 側の実装に合わせる）
$isMultipart = str_starts_with($_SERVER['CONTENT_TYPE'] ?? '', 'multipart/form-data');
$b = $isMultipart ? $_POST : json_body();
if ($isMultipart && ($b['_method'] ?? '') === 'PUT') {
    $isEdit = true;
}

$uid     = isset($b['uid']) ? (int)$b['uid'] : 0;
$itemId  = s($b, 'itemId', 20);
$date    = s($b, 'date', 10);
$time    = s($b, 'time', 5);
$author  = s($b, 'author', 60);
$kind    = s($b, 'kind', 20);
$bump    = s($b, 'bump', 10) === 'revision' ? 'revision' : 'minor';
$summary = s($b, 'summary', 500);
$target  = s($b, 'target', 200);
$ticket  = s($b, 'ticket', 30);
$url     = s($b, 'downloadUrl', 500);
$removeFile = $isMultipart && ($b['removeFile'] ?? '') === '1';

if ($isMultipart) {
    $filesRaw = json_decode((string)($b['filesJson'] ?? '[]'), true);
    $files = is_array($filesRaw) ? $filesRaw : [];
} else {
    $files = isset($b['files']) && is_array($b['files']) ? $b['files'] : [];
}

$allowedKind = ['機能追加', '不具合修正', '改善', '資料改訂', '初版公開'];
if (!valid_date($date))  json_error('更新日が不正です。');
if (!valid_time($time))  json_error('時間が不正です。');
if ($author === '')      json_error('対応者は必須です。');
if ($summary === '')     json_error('更新内容は必須です。');
if ($target === '')      json_error('対象機能は必須です。');
if (!in_array($kind, $allowedKind, true)) json_error('区分が不正です。');
if (!valid_url($url))    json_error('URLは http:// または https:// で入力してください。');
if ($isEdit && $uid <= 0) json_error('修正する更新履歴が指定されていません。');

// 添付ファイルの列がまだ無いサーバーでは、選ばせもしないので無条件に無視してよい。
// item_id は既存の行と一致した時点で安全な文字（英数字・_・-）だけと分かる。
$hasFileCols = lp_has_column('lp_updates', 'file_path')
    && preg_match('/^[A-Za-z0-9_-]{1,20}$/', $itemId) === 1;

$newFile = null;
if ($hasFileCols && $isMultipart && isset($_FILES['file'])) {
    $v = lp_validate_upload($_FILES['file']);
    if ($v['ok']) {
        $newFile = $v; // ['ext' => ..., 'mime' => ...]
    } elseif ($v['error'] !== null) {
        json_error($v['error']);
    }
}

$chk = db()->prepare('SELECT 1 FROM lp_items WHERE item_id = ? AND is_active = 1');
$chk->execute([$itemId]);
if (!$chk->fetchColumn()) {
    json_error('対象アイテムが見つかりません。');
}

$oldFilePath = null;
if ($isEdit) {
    $chkU = db()->prepare(
        $hasFileCols
            ? 'SELECT file_path FROM lp_updates WHERE update_id = ?'
            : 'SELECT 1 AS file_path FROM lp_updates WHERE update_id = ?'
    );
    $chkU->execute([$uid]);
    $existing = $chkU->fetch();
    if ($existing === false) json_error('修正する更新履歴が見つかりません。', 404);
    if ($hasFileCols) {
        $oldFilePath = $existing['file_path'] ?: null;
    }
}

$pdo = db();
$deleteAfterCommit = null; // コミット後に削除する「もう使われなくなった」物理ファイル
try {
    $pdo->beginTransaction();

    // bump_type はデータベースの更新（sql/upgrade.sql）で足す列。まだ無ければ書かず、
    // 版数はすべて「通常の更新」として数えられる（あとから流せばそのまま反映される）
    $hasBump = lp_has_column('lp_updates', 'bump_type');

    if ($isEdit) {
        $st = $pdo->prepare($hasBump
            ? 'UPDATE lp_updates
                  SET item_id = ?, updated_on = ?, updated_time = ?, author = ?, update_kind = ?,
                      bump_type = ?, summary = ?, target_feature = ?, ticket_no = ?
                WHERE update_id = ?'
            : 'UPDATE lp_updates
                  SET item_id = ?, updated_on = ?, updated_time = ?, author = ?, update_kind = ?,
                      summary = ?, target_feature = ?, ticket_no = ?
                WHERE update_id = ?');
        $args = [$itemId, $date, $time . ':00', $author, $kind];
        if ($hasBump) { $args[] = $bump; }
        array_push($args, $summary, $target, $ticket !== '' ? $ticket : null, $uid);
        $st->execute($args);
        $updateId = $uid;
        // 修正したファイル（実際に直したプログラム）は入れ替える（残したまま足すと重複するため）
        $pdo->prepare('DELETE FROM lp_update_files WHERE update_id = ?')->execute([$updateId]);
    } else {
        $st = $pdo->prepare($hasBump
            ? 'INSERT INTO lp_updates
                 (item_id, updated_on, updated_time, author, author_user_id, update_kind, bump_type, summary, target_feature, ticket_no)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
            : 'INSERT INTO lp_updates
                 (item_id, updated_on, updated_time, author, author_user_id, update_kind, summary, target_feature, ticket_no)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)');
        $st->execute($hasBump
            ? [$itemId, $date, $time . ':00', $author, $user['user_id'], $kind,
               $bump, $summary, $target, $ticket !== '' ? $ticket : null]
            : [$itemId, $date, $time . ':00', $author, $user['user_id'], $kind,
               $summary, $target, $ticket !== '' ? $ticket : null]);
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

    // ---- 添付ファイル（画像・PDF・ZIP）----
    if ($hasFileCols) {
        if ($newFile !== null) {
            // 新しいファイルを保存する（既存があれば、コミット後に置き換える）
            $dir = __DIR__ . '/../uploads/' . $itemId;
            if (!is_dir($dir) && !mkdir($dir, 0775, true) && !is_dir($dir)) {
                throw new RuntimeException('添付ファイル用のフォルダを作成できませんでした。');
            }
            $relPath = 'uploads/' . $itemId . '/' . $updateId . '.' . $newFile['ext'];
            $fullPath = __DIR__ . '/../' . $relPath;
            if (!move_uploaded_file($_FILES['file']['tmp_name'], $fullPath)) {
                throw new RuntimeException('添付ファイルの保存に失敗しました。');
            }
            $pdo->prepare(
                'UPDATE lp_updates SET file_path = ?, file_name = ?, file_size = ?, file_mime = ? WHERE update_id = ?'
            )->execute([
                $relPath,
                mb_substr((string)$_FILES['file']['name'], 0, 255),
                (int)$_FILES['file']['size'],
                $newFile['mime'],
                $updateId,
            ]);
            if ($oldFilePath !== null && $oldFilePath !== $relPath) {
                $deleteAfterCommit = $oldFilePath;
            }
        } elseif ($removeFile && $oldFilePath !== null) {
            $pdo->prepare(
                'UPDATE lp_updates SET file_path = NULL, file_name = NULL, file_size = NULL, file_mime = NULL WHERE update_id = ?'
            )->execute([$updateId]);
            $deleteAfterCommit = $oldFilePath;
        } elseif ($isEdit && $oldFilePath !== null) {
            // 対象アイテムを変更した場合は、添付ファイルもそのアイテムのフォルダへ移しておく
            // （保存場所が変わるだけで、ダウンロードは update_id で引くので動作に影響はない）
            $expectedPrefix = 'uploads/' . $itemId . '/';
            if (strncmp($oldFilePath, $expectedPrefix, strlen($expectedPrefix)) !== 0) {
                $ext     = pathinfo($oldFilePath, PATHINFO_EXTENSION);
                $newDir  = __DIR__ . '/../uploads/' . $itemId;
                $newRel  = 'uploads/' . $itemId . '/' . $updateId . '.' . $ext;
                $oldFull = __DIR__ . '/../' . $oldFilePath;
                $newFull = __DIR__ . '/../' . $newRel;
                if ((is_dir($newDir) || mkdir($newDir, 0775, true)) && is_file($oldFull) && @rename($oldFull, $newFull)) {
                    $pdo->prepare('UPDATE lp_updates SET file_path = ? WHERE update_id = ?')
                        ->execute([$newRel, $updateId]);
                }
            }
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

if ($deleteAfterCommit !== null) {
    $target = __DIR__ . '/../' . $deleteAfterCommit;
    if (is_file($target) && !@unlink($target)) {
        error_log('[library-portal] failed to remove old attachment: ' . $deleteAfterCommit);
    }
}

audit($isEdit ? 'update.edit' : 'update.create', $itemId, mb_substr($summary, 0, 200));
json_out(['ok' => true, 'updateId' => $updateId], $isEdit ? 200 : 201);

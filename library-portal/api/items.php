<?php
/**
 * GET  api/items.php        … ライブラリ一覧＋更新履歴を返す（要ログイン）
 * POST api/items.php        … アイテムを新規登録（管理者・編集者）
 * PUT  api/items.php        … アイテムを修正（管理者・編集者）
 */
declare(strict_types=1);
// SSO（シングルサインオン）を再度有効化する場合は次の行のコメントを外す
// require __DIR__ . '/../sso/sso_guard.php';
require_once __DIR__ . '/../includes/auth.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    api_require_login();

    // sql/upgrade.sql をまだ流していないサーバーでも一覧が出るように、
    // 後から足した列は「あれば読む・無ければ既定値」で扱う
    $seriesCol = lp_has_column('lp_items', 'series') ? 'series' : "'' AS series";
    $bumpCol   = lp_has_column('lp_updates', 'bump_type') ? 'u.bump_type' : "'minor' AS bump_type";
    $hasFileCols = lp_has_column('lp_updates', 'file_path');
    $fileCols = $hasFileCols
        ? 'u.file_path, u.file_name, u.file_size, u.file_mime'
        : "NULL AS file_path, NULL AS file_name, NULL AS file_size, NULL AS file_mime";

    $items = db()->query(
        "SELECT item_id, name, category, {$seriesCol}, created_by, description, download_url, created_date
           FROM lp_items WHERE is_active = 1 ORDER BY item_id"
    )->fetchAll();

    if (!$items) {
        json_out(['items' => [], 'categories' => lp_categories_list()]);
    }

    $updates = db()->query(
        "SELECT u.update_id, u.item_id, u.updated_on, u.updated_time, u.author, u.update_kind,
                {$bumpCol}, u.summary, u.target_feature, u.ticket_no, {$fileCols}
           FROM lp_updates u
           JOIN lp_items i ON i.item_id = u.item_id AND i.is_active = 1
          ORDER BY u.updated_on DESC, u.updated_time DESC, u.update_id DESC"
    )->fetchAll();

    $files = db()->query(
        'SELECT update_id, file_path, change_note FROM lp_update_files ORDER BY update_id, sort_no, file_id'
    )->fetchAll();

    // 「どのファイルを、どう直したか」を分けて返す（画面側で表にして見せるため）
    $filesByUpdate = [];
    foreach ($files as $f) {
        $filesByUpdate[(int)$f['update_id']][] = [
            'path' => $f['file_path'],
            'note' => (string)($f['change_note'] ?? ''),
        ];
    }

    $historyByItem = [];
    foreach ($updates as $u) {
        $historyByItem[$u['item_id']][] = [
            'uid'     => (int)$u['update_id'],   // 修正するときに対象を特定するための番号
            'date'    => $u['updated_on'],
            'time'    => substr((string)$u['updated_time'], 0, 5),
            'author'  => $u['author'],
            'kind'    => $u['update_kind'],
            'bump'    => ($u['bump_type'] ?? 'minor') === 'revision' ? 'revision' : 'minor',
            'summary' => $u['summary'],
            'target'  => $u['target_feature'],
            'files'   => $filesByUpdate[(int)$u['update_id']] ?? [],
            'ticket'  => $u['ticket_no'] ?? '',
            'attachment' => $u['file_path'] ? [
                'name' => $u['file_name'] ?: basename($u['file_path']),
                'size' => (int)$u['file_size'],
                'mime' => $u['file_mime'] ?: '',
                'url'  => 'download.php?uid=' . (int)$u['update_id'],
            ] : null,
        ];
    }

    // 版数は登録順から決まる決まりごとなので、保存値ではなく毎回ここで数える。
    // $historyByItem は新しい順なので、古い順に数えてから戻す。
    foreach ($historyByItem as $itemId => $hist) {
        $oldestFirst = array_reverse($hist);
        $labels = lp_version_series(array_column($oldestFirst, 'bump'));
        foreach ($oldestFirst as $i => $_) {
            $oldestFirst[$i]['version'] = $labels[$i];
        }
        $historyByItem[$itemId] = array_reverse($oldestFirst);
    }

    $out = [];
    foreach ($items as $i) {
        $out[] = [
            'id'          => $i['item_id'],
            'name'        => $i['name'],
            'category'    => $i['category'],
            'series'      => $i['series'] ?? '',
            'creator'     => $i['created_by'],
            'createdAt'   => $i['created_date'],
            'downloadUrl' => $i['download_url'] ?? '',
            'description' => $i['description'] ?? '',
            'history'     => $historyByItem[$i['item_id']] ?? [],
            // 更新がまだ無い資料も、登録した時点で 1.00 とする（「版数なし」を無くす）
            'version'     => $historyByItem[$i['item_id']][0]['version'] ?? lp_version_label(1, 0, 0),
        ];
    }
    json_out(['items' => $out, 'categories' => lp_categories_list()]);
}

if ($method === 'POST') {
    api_require_editor();
    api_verify_csrf();

    $b = json_body();
    $id      = s($b, 'id', 20);
    $name    = s($b, 'name', 120);
    $cat     = s($b, 'category', 20);
    $series  = s($b, 'series', 60);
    $creator = s($b, 'creator', 60);
    $desc    = s($b, 'description', 2000);
    $url     = s($b, 'downloadUrl', 500);
    $date    = s($b, 'createdAt', 10);

    $allowedCat = lp_category_labels();
    if ($id === '' || !preg_match('/^[A-Za-z0-9_-]{1,20}$/', $id)) {
        json_error('管理IDは半角英数字・ハイフンで入力してください。');
    }
    if ($name === '' || $creator === '') {
        json_error('名称と作成者は必須です。');
    }
    if (!in_array($cat, $allowedCat, true)) {
        json_error('種別が不正です。');
    }
    if (!valid_date($date)) {
        json_error('作成日が不正です。');
    }
    if (!valid_url($url)) {
        json_error('URLは http:// または https:// で入力してください。');
    }

    $exists = db()->prepare('SELECT 1 FROM lp_items WHERE item_id = ?');
    $exists->execute([$id]);
    if ($exists->fetchColumn()) {
        json_error('その管理IDは既に登録されています。');
    }

    // series はデータベースの更新（sql/upgrade.sql）で足す列なので、まだ無ければ書かない
    $hasSeries = lp_has_column('lp_items', 'series');
    $st = db()->prepare($hasSeries
        ? 'INSERT INTO lp_items (item_id, name, category, series, created_by, description, download_url, created_date)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        : 'INSERT INTO lp_items (item_id, name, category, created_by, description, download_url, created_date)
           VALUES (?, ?, ?, ?, ?, ?, ?)');
    $st->execute($hasSeries
        ? [$id, $name, $cat, $series !== '' ? $series : null, $creator, $desc, $url !== '' ? $url : null, $date]
        : [$id, $name, $cat, $creator, $desc, $url !== '' ? $url : null, $date]);
    audit('item.create', $id, $name);

    json_out(['ok' => true, 'id' => $id], 201);
}

/* ------------------------------------------------------------
   登録済みアイテムの修正（管理IDは変更しない）
   ------------------------------------------------------------ */
if ($method === 'PUT') {
    api_require_editor();
    api_verify_csrf();

    $b = json_body();
    $id      = s($b, 'id', 20);
    $name    = s($b, 'name', 120);
    $cat     = s($b, 'category', 20);
    $series  = s($b, 'series', 60);
    $creator = s($b, 'creator', 60);
    $desc    = s($b, 'description', 2000);
    $url     = s($b, 'downloadUrl', 500);
    $date    = s($b, 'createdAt', 10);

    $allowedCat = lp_category_labels();
    if ($id === '' || !preg_match('/^[A-Za-z0-9_-]{1,20}$/', $id)) {
        json_error('管理IDが不正です。');
    }
    if ($name === '' || $creator === '') {
        json_error('名称と作成者は必須です。');
    }
    if (!in_array($cat, $allowedCat, true)) {
        json_error('種別が不正です。');
    }
    if (!valid_date($date)) {
        json_error('作成日が不正です。');
    }
    if (!valid_url($url)) {
        json_error('URLは http:// または https:// で入力してください。');
    }

    $hasSeries = lp_has_column('lp_items', 'series');
    $st = db()->prepare($hasSeries
        ? 'UPDATE lp_items
              SET name = ?, category = ?, series = ?, created_by = ?,
                  description = ?, download_url = ?, created_date = ?
            WHERE item_id = ?'
        : 'UPDATE lp_items
              SET name = ?, category = ?, created_by = ?,
                  description = ?, download_url = ?, created_date = ?
            WHERE item_id = ?');
    $st->execute($hasSeries
        ? [$name, $cat, $series !== '' ? $series : null, $creator, $desc, $url !== '' ? $url : null, $date, $id]
        : [$name, $cat, $creator, $desc, $url !== '' ? $url : null, $date, $id]);
    if ($st->rowCount() === 0) {
        $exists = db()->prepare('SELECT 1 FROM lp_items WHERE item_id = ?');
        $exists->execute([$id]);
        if (!$exists->fetchColumn()) json_error('対象のアイテムが見つかりません。', 404);
    }
    audit('item.update', $id, $name);

    json_out(['ok' => true, 'id' => $id]);
}

json_error('許可されていないメソッドです。', 405);

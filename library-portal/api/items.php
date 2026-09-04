<?php
/**
 * GET  api/items.php        … ライブラリ一覧＋更新履歴を返す（要ログイン）
 * POST api/items.php        … アイテムを新規登録（管理者のみ）
 */
declare(strict_types=1);
require_once __DIR__ . '/../includes/auth.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    api_require_login();

    $items = db()->query(
        'SELECT item_id, name, category, created_by, description, download_url, created_date
           FROM lp_items WHERE is_active = 1 ORDER BY item_id'
    )->fetchAll();

    if (!$items) {
        json_out([]);
    }

    $updates = db()->query(
        'SELECT u.update_id, u.item_id, u.updated_on, u.updated_time, u.author, u.update_kind,
                u.version, u.summary, u.target_feature, u.ticket_no
           FROM lp_updates u
           JOIN lp_items i ON i.item_id = u.item_id AND i.is_active = 1
          ORDER BY u.updated_on DESC, u.updated_time DESC, u.update_id DESC'
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
            'date'    => $u['updated_on'],
            'time'    => substr((string)$u['updated_time'], 0, 5),
            'author'  => $u['author'],
            'kind'    => $u['update_kind'],
            'version' => $u['version'] ?? '',
            'summary' => $u['summary'],
            'target'  => $u['target_feature'],
            'files'   => $filesByUpdate[(int)$u['update_id']] ?? [],
            'ticket'  => $u['ticket_no'] ?? '',
        ];
    }

    $out = [];
    foreach ($items as $i) {
        $out[] = [
            'id'          => $i['item_id'],
            'name'        => $i['name'],
            'category'    => $i['category'],
            'creator'     => $i['created_by'],
            'createdAt'   => $i['created_date'],
            'downloadUrl' => $i['download_url'] ?? '',
            'description' => $i['description'] ?? '',
            'history'     => $historyByItem[$i['item_id']] ?? [],
        ];
    }
    json_out($out);
}

if ($method === 'POST') {
    api_require_admin();
    api_verify_csrf();

    $b = json_body();
    $id      = s($b, 'id', 20);
    $name    = s($b, 'name', 120);
    $cat     = s($b, 'category', 20);
    $creator = s($b, 'creator', 60);
    $desc    = s($b, 'description', 2000);
    $url     = s($b, 'downloadUrl', 500);
    $date    = s($b, 'createdAt', 10);

    $allowedCat = ['アプリ', 'プログラム', '資料', 'マニュアル'];
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

    $st = db()->prepare(
        'INSERT INTO lp_items (item_id, name, category, created_by, description, download_url, created_date)
         VALUES (?, ?, ?, ?, ?, ?, ?)'
    );
    $st->execute([$id, $name, $cat, $creator, $desc, $url !== '' ? $url : null, $date]);
    audit('item.create', $id, $name);

    json_out(['ok' => true, 'id' => $id], 201);
}

json_error('許可されていないメソッドです。', 405);

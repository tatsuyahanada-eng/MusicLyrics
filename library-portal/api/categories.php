<?php
/**
 * GET    api/categories.php        … カテゴリ一覧を返す（要ログイン、使用件数つき）
 * POST   api/categories.php        … カテゴリを新規登録（管理者のみ）
 * PUT    api/categories.php?code=X … 表示名・配色・アイコンを修正（管理者のみ、code は変更不可）
 * DELETE api/categories.php?code=X … カテゴリを削除（管理者のみ、使用中のアイテムがあれば拒否）
 */
declare(strict_types=1);
// SSO（シングルサインオン）を再度有効化する場合は次の行のコメントを外す
// require __DIR__ . '/../sso/sso_guard.php';
require_once __DIR__ . '/../includes/auth.php';

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    api_require_login();

    $cats = lp_categories_list();
    if (!lp_has_table('lp_categories')) {
        json_out($cats); // 未移行のサーバーでは使用件数を数える術が無いのでそのまま返す
    }

    $counts = db()->query(
        'SELECT category, COUNT(*) AS n FROM lp_items WHERE is_active = 1 GROUP BY category'
    )->fetchAll();
    $byLabel = [];
    foreach ($counts as $c) {
        $byLabel[$c['category']] = (int)$c['n'];
    }
    foreach ($cats as &$c) {
        $c['itemCount'] = $byLabel[$c['label']] ?? 0;
    }
    unset($c);
    json_out($cats);
}

if (!lp_has_table('lp_categories')) {
    json_error('データベースの更新がまだ行われていません。phpMyAdmin で sql/upgrade.sql を実行してください。', 500);
}

api_require_admin();
api_verify_csrf();

function validate_category_fields(array $b, bool $isNew): array
{
    $label = s($b, 'label', 40);
    $color = s($b, 'color', 20);
    $icon  = s($b, 'icon', 20);

    if ($label === '') {
        json_error('表示名は必須です。');
    }
    if (!in_array($color, lp_category_colors(), true)) {
        json_error('配色の指定が不正です。');
    }
    if (!in_array($icon, lp_category_icons(), true)) {
        json_error('アイコンの指定が不正です。');
    }

    $out = ['label' => $label, 'color' => $color, 'icon' => $icon];
    if ($isNew) {
        $code = strtoupper(s($b, 'code', 10));
        if (!preg_match('/^[A-Z][A-Z0-9]{1,9}$/', $code)) {
            json_error('接頭辞は半角英字で始まる英数字2〜10文字で入力してください（例：APP）。');
        }
        $out['code'] = $code;
    }
    return $out;
}

// ---------------------------------------------------------- 新規登録
if ($method === 'POST') {
    $f = validate_category_fields(json_body(), true);

    $dup = db()->prepare('SELECT 1 FROM lp_categories WHERE code = ? OR label = ?');
    $dup->execute([$f['code'], $f['label']]);
    if ($dup->fetchColumn()) {
        json_error('その接頭辞または表示名は既に使われています。');
    }

    $nextSort = (int)db()->query('SELECT COALESCE(MAX(sort_no), 0) + 1 FROM lp_categories')->fetchColumn();
    $st = db()->prepare(
        'INSERT INTO lp_categories (code, label, color, icon, sort_no) VALUES (?, ?, ?, ?, ?)'
    );
    $st->execute([$f['code'], $f['label'], $f['color'], $f['icon'], $nextSort]);
    audit('category.create', $f['code'], $f['label']);
    json_out(['ok' => true, 'code' => $f['code']], 201);
}

// ---------------------------------------------------------- 修正
if ($method === 'PUT' || $method === 'PATCH') {
    $code = strtoupper((string)($_GET['code'] ?? ''));
    if ($code === '') {
        json_error('対象のカテゴリが指定されていません。');
    }
    $existing = db()->prepare('SELECT * FROM lp_categories WHERE code = ?');
    $existing->execute([$code]);
    $row = $existing->fetch();
    if (!$row) {
        json_error('対象のカテゴリが見つかりません。', 404);
    }

    $f = validate_category_fields(json_body(), false);
    if ($f['label'] !== $row['label']) {
        $dup = db()->prepare('SELECT 1 FROM lp_categories WHERE label = ? AND code <> ?');
        $dup->execute([$f['label'], $code]);
        if ($dup->fetchColumn()) {
            json_error('その表示名は既に使われています。');
        }
    }

    $st = db()->prepare('UPDATE lp_categories SET label = ?, color = ?, icon = ? WHERE code = ?');
    $st->execute([$f['label'], $f['color'], $f['icon'], $code]);

    // 表示名を変えた場合は、既存のアイテムの種別表記も追従させる
    if ($f['label'] !== $row['label']) {
        $upd = db()->prepare('UPDATE lp_items SET category = ? WHERE category = ?');
        $upd->execute([$f['label'], $row['label']]);
    }
    audit('category.update', $code, $f['label']);
    json_out(['ok' => true]);
}

// ---------------------------------------------------------- 削除
if ($method === 'DELETE') {
    $code = strtoupper((string)($_GET['code'] ?? ''));
    if ($code === '') {
        json_error('対象のカテゴリが指定されていません。');
    }
    $existing = db()->prepare('SELECT * FROM lp_categories WHERE code = ?');
    $existing->execute([$code]);
    $row = $existing->fetch();
    if (!$row) {
        json_error('対象のカテゴリが見つかりません。', 404);
    }

    $total = (int)db()->query('SELECT COUNT(*) FROM lp_categories')->fetchColumn();
    if ($total <= 1) {
        json_error('最後の1件は削除できません。');
    }

    $inUse = db()->prepare('SELECT COUNT(*) FROM lp_items WHERE category = ?');
    $inUse->execute([$row['label']]);
    $n = (int)$inUse->fetchColumn();
    if ($n > 0) {
        json_error("「{$row['label']}」を使用しているアイテムが{$n}件あるため削除できません。先にアイテムの種別を変更するか削除してください。");
    }

    $st = db()->prepare('DELETE FROM lp_categories WHERE code = ?');
    $st->execute([$code]);
    audit('category.delete', $code, $row['label']);
    json_out(['ok' => true]);
}

json_error('許可されていないメソッドです。', 405);

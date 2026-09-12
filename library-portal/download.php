<?php
/**
 * download.php?uid=更新履歴のID
 *
 * 更新履歴に添付されたファイル（画像・PDF・ZIP）を配信する。
 * uploads/ 配下はブラウザから直接アクセスできないようにしてあるので、
 * ダウンロードは必ずこの画面を経由させ、ログイン済みかどうかをここで確認する。
 */
declare(strict_types=1);
// SSO（シングルサインオン）を再度有効化する場合は次の行のコメントを外す
// require __DIR__ . '/sso/sso_guard.php';
require_once __DIR__ . '/includes/auth.php';

require_login(); // 閲覧できる利用者なら誰でもダウンロード可（編集権限は問わない）

$uid = isset($_GET['uid']) ? (int)$_GET['uid'] : 0;
if ($uid <= 0) {
    http_response_code(400);
    exit('不正な指定です。');
}

$st = db()->prepare(
    'SELECT u.file_path, u.file_name, u.file_mime, u.file_size
       FROM lp_updates u
       JOIN lp_items i ON i.item_id = u.item_id AND i.is_active = 1
      WHERE u.update_id = ?'
);
$st->execute([$uid]);
$row = $st->fetch();

if (!$row || (string)($row['file_path'] ?? '') === '') {
    http_response_code(404);
    exit('添付ファイルが見つかりません。');
}

// uploads/ の外を指すパスでないことを確認してから配信する（保存経路は自分で決めているが念のため）
$base = realpath(__DIR__ . '/uploads');
$full = realpath(__DIR__ . '/' . $row['file_path']);
if ($base === false || $full === false || strncmp($full, $base, strlen($base)) !== 0) {
    http_response_code(404);
    exit('添付ファイルが見つかりません。');
}
if (!is_file($full)) {
    http_response_code(404);
    exit('添付ファイルが見つかりません（サーバー上から削除された可能性があります）。');
}

$name = (string)($row['file_name'] ?: basename($full));
// 日本語ファイル名でも文字化けしないよう、ASCII の代替名と RFC 5987 形式の両方を送る
$asciiName = preg_replace('/[^\x20-\x7e]/', '_', $name);
$encoded   = rawurlencode($name);
$mime      = (string)($row['file_mime'] ?: 'application/octet-stream');

// 画像・PDF はブラウザがそのまま表示できるので inline（新しいタブでそのまま見られる）。
// ZIP など表示できない種類だけ attachment にする（inline のまま new tab で開くと、
// ダウンロードだけ起きて中身の無い真っ白なタブが残ってしまうため）。
$viewable    = str_starts_with($mime, 'image/') || $mime === 'application/pdf';
$disposition = $viewable ? 'inline' : 'attachment';

header('Content-Type: ' . $mime);
header('Content-Length: ' . (string)($row['file_size'] ?: filesize($full)));
header('Content-Disposition: ' . $disposition . '; filename="' . $asciiName . '"; filename*=UTF-8\'\'' . $encoded);
header('X-Content-Type-Options: nosniff');
header('Cache-Control: private, must-revalidate');

readfile($full);

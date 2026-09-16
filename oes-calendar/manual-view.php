<?php
/**
 * OES入替作業APP / 手順書・資料を「端末の既定のアプリで開く」ために渡すエンドポイント（PDF用）
 *
 * 置き場所: index.html と同じ場所（このファイル自身がある場所）に置いたままでよい。
 *           手動でコピー・移動する必要はない（配布ZIPを展開すればそのまま使える）。
 *
 * 動き:     manuals/フォルダ内のPDFを、ブラウザに「ダウンロードしてから開く」形で渡す
 *           （Content-Disposition: attachment）。ホーム画面から起動したPWA（WebAPK）の中では、
 *           PDFをその場で開こうとしてもアプリ内に留まってしまい、まともに見られないことがある。
 *           ダウンロードとして渡せば、Android側の「ダウンロード完了→開く」の流れに乗るため、
 *           Acrobat Readerなど端末に設定された既定のPDFアプリで開ける。
 *
 * ※ 認証は不要（アプリの一覧に出ているファイルをそのまま渡すだけで、manuals/フォルダの
 *   直リンクと同じ扱い。新しく何かを公開するわけではない）。
 */

declare(strict_types=1);

const MANUAL_DIR = __DIR__ . '/manuals';

function fail(int $code, string $msg): void {
    http_response_code($code);
    header('Content-Type: text/plain; charset=utf-8');
    echo $msg;
    exit;
}

// url同様、file はファイル名部分だけを使う（../等でのパス操作を防ぐ）
$file = basename((string)($_GET['file'] ?? ''));
if ($file === '' || $file === '.' || $file === '..') {
    fail(400, 'file が指定されていません');
}
$path = MANUAL_DIR . '/' . $file;
if (!is_file($path)) {
    fail(404, 'ファイルが見つかりません');
}

$finfo = new finfo(FILEINFO_MIME_TYPE);
$mime  = (string)$finfo->file($path);

// 表示名（元のファイル名）。無ければ保存名をそのまま使う。ヘッダーインジェクション防止のため制御文字と "を除く
$name = (string)($_GET['name'] ?? $file);
$name = preg_replace('/[\x00-\x1F\x7F"]/u', '', $name) ?? $file;
if ($name === '') { $name = $file; }
$encodedName = rawurlencode($name);

header('Content-Type: ' . $mime);
header('Content-Length: ' . (string)filesize($path));
// filename*（RFC 5987/6266）で日本語ファイル名も正しく渡す。古いブラウザ向けにfilenameも併記する
header('Content-Disposition: attachment; filename="' . $encodedName . '"; filename*=UTF-8\'\'' . $encodedName);
header('X-Content-Type-Options: nosniff');
header('Cache-Control: private, max-age=0, must-revalidate');
readfile($path);

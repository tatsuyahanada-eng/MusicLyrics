<?php
/**
 * OES入替作業APP / 業態ごとの手順書・資料（画像・PDF）の削除用エンドポイント（任意・PHPが動くサーバー向け）
 *
 * 置き場所: index.html と同じ場所に "manual-delete.php" という名前で置く。
 *           （このファイルは deploy/ にあるサンプルです。名前を manual-delete.php にしてコピーしてください）
 *
 * 動き:     設定タブで手順書・資料を削除したときに、"manuals/" フォルダの実ファイルも消す。
 *           settings.json 側の削除（一覧から消す）は index.html 側で既に行われているので、
 *           このエンドポイントが無い・失敗しても一覧の見た目には影響しない（ファイルが残るだけ）。
 */

declare(strict_types=1);

const MANUAL_DIR = __DIR__ . '/manuals';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function fail(string $msg, int $code = 400): void {
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    fail('POSTで送信してください', 405);
}

$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    fail('config.php がありません（index.html と同じ場所に置いてください）', 500);
}
$config = require $configFile;
$adminPassword = (string)($config['admin_password'] ?? '');
if ($adminPassword === '') {
    fail('config.php に admin_password がありません', 500);
}

$raw = file_get_contents('php://input');
$body = ($raw !== false && $raw !== '') ? json_decode($raw, true) : null;
if (!is_array($body)) {
    fail('JSONを読み取れませんでした');
}
if (!isset($body['password']) || !hash_equals($adminPassword, (string)$body['password'])) {
    fail('パスワードが違います', 403);
}

$url = (string)($body['url'] ?? '');
if ($url === '') {
    fail('削除するファイルが指定されていません');
}

// url に何が入っていてもパスとしてはファイル名部分だけを使う（../ 等でのパス操作を防ぐ）
$fname = basename($url);
if ($fname === '' || $fname === '.' || $fname === '..') {
    fail('ファイル名が不正です');
}
$path = MANUAL_DIR . '/' . $fname;

// すでに無い場合も含めて「削除できた（＝もう存在しない）」として扱う（一覧側は既に消えているため）
if (is_file($path)) {
    @unlink($path);
}

echo json_encode(['ok' => true], JSON_UNESCAPED_UNICODE);

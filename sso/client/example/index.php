<?php
/**
 * 既存アプリへの組み込み例。
 *
 * ポイントは先頭の1行だけ。あとは今までどおりのページを書けばよい。
 * $SSO_USER に共通ユーザーデータベースのユーザーが入っている。
 */
require dirname(__DIR__) . '/sso_guard.php';

// ここから下は、既存アプリのコードをそのまま使える。
// 既にアプリ独自のユーザーテーブルがある場合は、対応付けたIDで引き当てる：
//
//   $localUserId = $SSO_USER['external_user_id'] ?? null;
//   if ($localUserId === null) {
//       // 初回ログイン時に、アプリ側のユーザーを作る／既存行に紐づける
//   }
?>
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>組み込みサンプル</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 720px; margin: 60px auto; padding: 0 20px; line-height: 1.8; }
  table { border-collapse: collapse; width: 100%; margin: 20px 0; }
  th, td { border: 1px solid #dde5ea; padding: 8px 10px; text-align: left; }
  th { background: #f7fafb; width: 12em; }
  code { background: #f2f6f8; padding: 2px 6px; border-radius: 4px; }
</style>
</head>
<body>
  <h1>ログインできました</h1>
  <p>このページは <code>sso_guard.php</code> を1行読み込んだだけで保護されています。</p>

  <table>
    <tr><th>ログインID</th><td><?= htmlspecialchars((string) $SSO_USER['username'], ENT_QUOTES, 'UTF-8') ?></td></tr>
    <tr><th>氏名</th><td><?= htmlspecialchars((string) $SSO_USER['display_name'], ENT_QUOTES, 'UTF-8') ?></td></tr>
    <tr><th>所属</th><td><?= htmlspecialchars((string) $SSO_USER['department'], ENT_QUOTES, 'UTF-8') ?></td></tr>
    <tr><th>メール</th><td><?= htmlspecialchars((string) $SSO_USER['email'], ENT_QUOTES, 'UTF-8') ?></td></tr>
    <tr><th>アプリ内のID</th>
        <td><?= htmlspecialchars((string) ($SSO_USER['external_user_id'] ?? '（未設定）'), ENT_QUOTES, 'UTF-8') ?></td></tr>
  </table>

  <p>
    <a href="sso_logout.php">ログアウト（全アプリ）</a>
  </p>
</body>
</html>

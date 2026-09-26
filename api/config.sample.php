<?php
/**
 * 領収書AI読み取りの設定ファイル（サンプル）
 *
 * 【初回の設定】
 *   1. このファイルを同じフォルダ（api/）に config.php という名前でコピーする
 *   2. gemini_api_key に Google AI Studio で発行したAPIキーを貼り付けて保存する
 *   3. 画面の「🔌 接続テスト」ボタンで「接続OK」と出ればOK
 *
 * 【APIキーを変更するとき】
 *   config.php の gemini_api_key を新しいキーに書き換えて上書き保存するだけで、すぐに反映されます。
 *   index.html や receipt.php を編集する必要はありません。
 *   変更後は「🔌 接続テスト」で新しいキー（末尾4桁）が有効か確認してください。
 *
 * 【モデルを変更するとき】
 *   model を新しいモデル名に書き換えます（旧モデルの提供終了時など）。
 *
 * config.php は api/.htaccess で外部から直接開けないよう保護されています。
 * APIキーをGitなどにコミットしないでください（.gitignore で config.php は除外済み）。
 */
return [
    // Gemini APIキー（必須）
    'gemini_api_key' => 'YOUR_GEMINI_API_KEY',

    // 使用するモデル（省略時は gemini-3.8-flash）
    'model' => 'gemini-3.8-flash',
];

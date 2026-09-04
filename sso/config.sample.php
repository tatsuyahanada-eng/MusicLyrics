<?php
/**
 * WELSYS User Management / SSO 設定ファイルのひな形。
 *
 *   cp config.sample.php config.php     して、config.php を環境に合わせて編集する。
 *   config.php は .gitignore 済み（秘密情報をコミットしないこと）。
 */
return [
    // ── データベース ────────────────────────────────────────────────
    'db' => [
        'dsn'  => 'mysql:host=127.0.0.1;port=3306;dbname=welsys_sso;charset=utf8mb4',
        'user' => 'welsys_sso',
        'pass' => 'CHANGE_ME',
    ],

    // ── 認証サーバー（このアプリ）の公開URL。public/ を指す。末尾スラッシュ無し ──
    'base_url' => 'https://auth.example.com',

    // ── ログインセッションのCookie ─────────────────────────────────
    'cookie' => [
        'name'     => 'WELSYS_SSO',
        'secure'   => true,   // HTTPS 必須。開発環境で http を使う時だけ false
        'domain'   => '',     // 例: '.example.com'（同一ドメインの複数アプリで共有する場合）
        'samesite' => 'Lax',
    ],

    // ── セッションの寿命（秒） ─────────────────────────────────────
    'session' => [
        'idle_timeout'     => 1800,   // 無操作でこの時間を過ぎたら失効（30分）
        'absolute_timeout' => 43200,  // ログインからの最大寿命（12時間）
    ],

    // ── 使い捨てチケットの寿命（秒） ───────────────────────────────
    'ticket_ttl' => 60,

    // ── ログイン試行の制限 ─────────────────────────────────────────
    'login' => [
        'max_attempts'    => 5,
        'lockout_seconds' => 900,     // 15分ロック
    ],

    // ── パスワードポリシー ─────────────────────────────────────────
    'password' => [
        'min_length' => 10,
    ],

    // 管理者(is_admin)を全アプリ閲覧可として扱うか。false なら管理者にも個別に許可が要る
    'admin_bypass_permissions' => false,

    // アプリ側が「まだ有効か」を認証サーバーに問い合わせる間隔の推奨値（秒）
    'recheck_interval' => 60,

    'timezone' => 'Asia/Tokyo',
];

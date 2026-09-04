<?php
/**
 * PWA マニフェスト。
 * base_url が環境ごとに変わる（サブパス配置も含む）ため、静的JSONではなく
 * この PHP から動的に生成する。
 */
declare(strict_types=1);
require __DIR__ . '/../lib/bootstrap.php';

header('Content-Type: application/manifest+json; charset=UTF-8');
header('Cache-Control: public, max-age=86400');

$asset = Config::baseUrl('assets');

echo json_encode([
    'name'             => View::PRODUCT_NAME . ' — WELSYS',
    'short_name'       => View::PRODUCT_NAME,
    'description'      => '共通ユーザーデータベースとシングルサインオンの管理コンソール',
    'lang'             => 'ja',
    'start_url'        => Config::baseUrl('index.php'),
    'scope'            => Config::baseUrl(),
    'display'          => 'standalone',
    'orientation'      => 'any',
    'background_color' => '#F4F1F9',
    'theme_color'      => '#552583',
    'icons'            => [
        ['src' => $asset . '/img/icon-192.png',           'sizes' => '192x192', 'type' => 'image/png', 'purpose' => 'any'],
        ['src' => $asset . '/img/icon-512.png',           'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'any'],
        ['src' => $asset . '/img/icon-maskable-192.png',  'sizes' => '192x192', 'type' => 'image/png', 'purpose' => 'maskable'],
        ['src' => $asset . '/img/icon-maskable-512.png',  'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'maskable'],
    ],
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);

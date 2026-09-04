/**
 * WELSYS User Management の Service Worker。
 *
 * この画面はログイン状態や権限判定を扱う管理画面のため、
 * HTML（ログイン・ダッシュボード・各管理ページ）は決してキャッシュしない。
 * キャッシュするのは見た目のためだけの静的資産（CSS・アイコン）に限る。
 * これにより「オフラインでも古い管理画面が開けてしまう」事故を避ける。
 */
const CACHE_NAME = 'welsys-um-static-v1';
const STATIC_ASSETS = [
  'assets/style.css',
  'assets/img/welsys-mark.png',
  'assets/img/welsys-logo.png',
  'assets/img/welsys-wordmark.png',
  'assets/img/welsys-wordmark-light.png',
  'assets/img/icon-192.png',
  'assets/img/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(
      STATIC_ASSETS.map((p) => new URL(p, self.registration.scope).toString())
    )).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) => Promise.all(
      names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n))
    ))
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') {
    return;
  }
  const url = new URL(req.url);
  const isStaticAsset = url.pathname.includes('/assets/');
  if (!isStaticAsset) {
    // HTML・API（validate.php 等）は常にネットワークから取得する
    return;
  }
  event.respondWith(
    caches.match(req).then((cached) => cached || fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
      return res;
    }))
  );
});

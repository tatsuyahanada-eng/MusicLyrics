/* ============================================================
   ライブラリポータル — Service Worker
   ・静的ファイル（CSS/JS/画像）のみキャッシュします
   ・画面本体（index.php）と API はキャッシュせず、常にサーバーへ問い合わせます
     （利用者ごとに内容と権限が異なるため、端末に残さない方針）
   ・オフライン時はナビゲーションを offline.html へ切り替えます
   ============================================================ */
const CACHE = 'library-portal-v6';
const SHELL = [
  'assets/library.css?v=6',
  'assets/library.js?v=5',
  'assets/settings.js?v=1',
  'assets/pwa.js?v=1',
  'assets/welsys-logo.jpg',
  'assets/app-icon.png',
  'assets/icon-192.png?v=5',
  'assets/icon-512.png?v=5',
  'assets/favicon-32.png?v=5',
  'assets/favicon-16.png?v=5',
  'offline.html',
  'manifest.webmanifest'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(SHELL))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== location.origin) return;

  // 画面遷移：ネットワーク優先、失敗時はオフライン案内
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match('offline.html'))
    );
    return;
  }

  // API とログイン関連はキャッシュしない
  if (url.pathname.includes('/api/') || url.pathname.endsWith('login.php') || url.pathname.endsWith('logout.php')) {
    return;
  }

  // 静的ファイル：キャッシュ優先、無ければ取得してキャッシュ
  event.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      if (res.ok && res.type === 'basic') {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
      }
      return res;
    }))
  );
});

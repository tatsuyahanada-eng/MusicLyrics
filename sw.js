/* Case By Case — service worker (offline cache, app-shell) */
const CACHE = 'case-by-case-v56';
const ASSETS = [
  'manual.html',
  'manual.css?v=49',
  'manual.js?v=50',
  'manifest.webmanifest',
  'icon.svg',
  'vendor/xlsx.full.min.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  // API（動的データ）はキャッシュせず常にネットワークへ
  if (url.pathname.endsWith('api.php') || url.pathname.includes('/api')) return;

  // ページ遷移（HTML）は必ずネットワーク優先。これによりサーバーのログイン(Basic認証)や
  // 401 がキャッシュで迂回されず、未ログイン時にキャッシュのアプリが表示されない。
  // ネットワークに全く繋がらない（オフライン）ときだけキャッシュを使う。
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match('manual.html'))
    );
    return;
  }

  // それ以外のアセットは cache-first（ネットワークが無ければキャッシュ）
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(req, copy));
          }
          return res;
        })
        .catch(() => caches.match('manual.html'));
    })
  );
});

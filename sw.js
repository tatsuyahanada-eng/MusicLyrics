/* Case By Case — service worker (offline cache, app-shell) */
const CACHE = 'case-by-case-v136';
const ASSETS = [
  'manual.html',
  'manual.css?v=106',
  'manual.js?v=122',
  'manifest.webmanifest',
  'icon.svg',
  'logo-default.png',
  'vendor/xlsx.full.min.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // cache.addAll() は「1つでも取得に失敗すると全部失敗」する。失敗すると新しい
    // サービスワーカーが入らず、古いものが動き続ける＝サーバーへアップロードしても
    // 端末側にいつまでも反映されない、という状態になる。
    // そのため1件ずつ入れ、失敗した分だけ諦める（他のファイルは確実に更新する）。
    // cache:'reload' で、ブラウザが持っている古い写しを使わず必ず取り直す。
    await Promise.all(ASSETS.map(async (url) => {
      try {
        const res = await fetch(new Request(url, { cache: 'reload' }));
        if (res && res.ok) await cache.put(url, res);
      } catch (_) { /* この1件だけ諦める */ }
    }));
    await self.skipWaiting();
  })());
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
  //
  // cache:'no-store' が重要。サーバーがキャッシュの指示（Cache-Control）を返さないため、
  // 単に fetch(req) とするとブラウザが独自判断で「少し前に取った manual.html」を
  // 使い回すことがある。その古いHTMLは古い manual.js を指しているので、
  // サーバーへ新しい版をアップロードしても端末にいつまでも反映されない、という状態になる。
  // 毎回サーバーへ取りにいくことで、アップロードした内容が確実に反映されるようにする。
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req.url, { cache: 'no-store', credentials: 'same-origin' })
        .catch(() => fetch(req))
        .catch(() => caches.match('manual.html'))
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

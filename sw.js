/* Music Lyrics — service worker (app-shell cache) */
const CACHE = 'music-lyrics-v83';
const SHELL = [
  './',
  './lyrics.html',
  './lyrics.css?v=47',
  './lyrics.js?v=47',
  './config.js?v=47',
  './manifest.json',
  './icon.svg',
  './icon-maskable.svg',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-192.png',
  './icon-maskable-512.png',
];

self.addEventListener('install', e => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL).catch(() => {})));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  /* Only intercept same-origin app-shell requests. APIs
     (YouTube, iTunes, lyrics.ovh, lrclib) always hit the
     network so they get fresh data. */
  if (url.origin !== self.location.origin) return;

  /* Network-first for the HTML document so version bumps show up
     immediately instead of being pinned to a cached old page.
     Falls back to cache when offline. */
  if (req.mode === 'navigate' || req.destination === 'document') {
    e.respondWith(
      fetch(req)
        .then(res => {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(req, clone));
          return res;
        })
        .catch(() => caches.match(req).then(c => c || caches.match('./lyrics.html')))
    );
    return;
  }

  /* Cache-first for the versioned assets (css/js/config/icons) */
  e.respondWith(
    caches.match(req).then(cached => {
      const network = fetch(req)
        .then(res => {
          if (res && res.status === 200 && res.type === 'basic') {
            const clone = res.clone();
            caches.open(CACHE).then(c => c.put(req, clone));
          }
          return res;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});

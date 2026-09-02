/* ============================================================
   VertiCale — Service Worker
   画面の表示に必要なファイルを保存しておき、通信が不安定でも開けるようにする。
   予定データの同期（sync.php）は常に通信を行い、キャッシュしない。
   ============================================================ */

const CACHE = 'task-scheduler-v39';

const ASSETS = [
  './schedule.html',
  './schedule.css?v=39',
  './schedule.js?v=39',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // 1つ取得できなくても導入が止まらないようにする
      .then((cache) => Promise.all(ASSETS.map((url) => cache.add(url).catch(() => null))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', (event) => {
  if (event.data === 'skip-waiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // 同期の通信は必ずサーバへ（古い内容を返さない）
  if (url.pathname.endsWith('/sync.php')) return;

  // 表示に使うファイルは、まず通信を試し、失敗したら保存済みを使う
  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.status === 200 && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((cache) => cache.put(req, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() => caches.match(req).then((hit) => hit || caches.match('./schedule.html')))
  );
});

/* OES入替作業APP / Service Worker
 *
 * Androidで「ホーム画面に追加」したときに、Chromeのマークが付いたショートカットではなく
 * 独立したアプリ（WebAPK）として登録されるために必要なファイルです。
 *
 * 方針: 常にネットワークを優先する（FTPでファイルを差し替えたら、次に開いたときすぐ反映される）。
 *       オフラインのときだけキャッシュから返す。
 *       共有設定（settings.json）・パスワード設定（config.json）・PHPはキャッシュしない。
 */
var CACHE = 'oes-app-v1';
var CORE = [
  './',
  './index.html',
  './manual.html',
  './manifest.webmanifest',
  './assets/icon-192.png',
  './assets/icon-512.png',
  './assets/icon-512-maskable.png'
];

self.addEventListener('install', function(e){
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(function(c){
    return Promise.all(CORE.map(function(u){ return c.add(u)['catch'](function(){}); }));
  }));
});

self.addEventListener('activate', function(e){
  e.waitUntil(caches.keys().then(function(keys){
    return Promise.all(keys.map(function(k){ return k === CACHE ? null : caches['delete'](k); }));
  }).then(function(){ return self.clients.claim(); }));
});

self.addEventListener('fetch', function(e){
  var req = e.request;
  if(req.method !== 'GET') return;
  var url = new URL(req.url);
  if(url.origin !== self.location.origin) return;
  /* 共有設定・パスワード設定・PHPは必ず最新をサーバーから取る */
  if(/(settings\.json|config\.json|\.php)$/.test(url.pathname)) return;

  e.respondWith(
    fetch(req).then(function(res){
      if(res && res.ok && res.type === 'basic'){
        var copy = res.clone();
        caches.open(CACHE).then(function(c){ c.put(req, copy); });
      }
      return res;
    })['catch'](function(){
      return caches.match(req).then(function(hit){
        if(hit) return hit;
        if(req.mode === 'navigate') return caches.match('./index.html');
        return new Response('', { status: 504, statusText: 'offline' });
      });
    })
  );
});

/* ============================================================
   ライブラリポータル — PWA（インストール）まわり
   ・Service Worker の登録（https でのみ有効）
   ・「アプリをインストール」の案内をブラウザから受け取って保持する
     （Chrome / Edge は beforeinstallprompt を一度しか出さないため、
      受け取った時点で保持し、利用者が押したいときに出せるようにする）
   ============================================================ */
(function () {
  'use strict';

  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('service-worker.js').catch(function (e) {
        console.warn('Service Worker の登録に失敗しました', e);
      });
    });
  }

  var deferred = null;                    // ブラウザから預かったインストール案内
  var listeners = [];                     // 状態が変わったときに知らせる相手

  function notify() {
    listeners.forEach(function (fn) {
      try { fn(); } catch (e) { /* 表示側の失敗で他へ影響させない */ }
    });
  }

  window.addEventListener('beforeinstallprompt', function (e) {
    e.preventDefault();                   // 既定のバナーは出さず、こちらの導線に集約する
    deferred = e;
    notify();
  });

  window.addEventListener('appinstalled', function () {
    deferred = null;
    notify();
  });

  window.LP_PWA = {
    /** インストール済み（アプリとして起動している）か */
    isInstalled: function () {
      return window.matchMedia('(display-mode: standalone)').matches ||
             window.navigator.standalone === true;
    },
    /** ブラウザからインストール案内を預かっているか */
    canPrompt: function () { return deferred !== null; },
    /** iPhone / iPad の Safari か（この場合は「ホーム画面に追加」の手動操作になる） */
    isIos: function () {
      return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
             (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    },
    /** インストールを実行する。結果（accepted / dismissed / unavailable）を返す */
    install: function () {
      if (!deferred) return Promise.resolve('unavailable');
      var p = deferred;
      deferred = null;
      p.prompt();
      return p.userChoice.then(function (choice) {
        notify();
        return choice.outcome;            // 'accepted' か 'dismissed'
      });
    },
    /** 状態が変わったときに呼ばれる関数を登録する */
    onChange: function (fn) { listeners.push(fn); fn(); }
  };
})();

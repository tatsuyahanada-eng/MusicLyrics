/* Service Worker の登録（https でのみ有効） */
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('service-worker.js').catch((e) => {
      console.warn('Service Worker の登録に失敗しました', e);
    });
  });
}

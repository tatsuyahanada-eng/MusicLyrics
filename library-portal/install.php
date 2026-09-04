<?php
/**
 * アプリのインストール案内ページ
 *
 * ログインなしで開けます（案内文だけで、データは一切表示しません）。
 * このページの URL を関係者に配って、各自の端末へ追加してもらう想定です。
 */
declare(strict_types=1);
require_once __DIR__ . '/includes/helpers.php';
?>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>アプリのインストール ｜ ライブラリポータル</title>
  <link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png?v=5">
  <link rel="icon" type="image/png" sizes="16x16" href="assets/favicon-16.png?v=5">
  <link rel="apple-touch-icon" href="assets/icon-192.png?v=5">
  <link rel="manifest" href="manifest.webmanifest">
  <meta name="theme-color" content="#007a33">
  <link rel="stylesheet" href="assets/library.css?v=13">
</head>
<body class="lp-body lp-body-auth">
  <main class="lp-auth">
    <div class="lp-auth-card lp-install-card">
      <div class="lp-auth-icons">
        <img class="lp-app-icon lp-app-icon-lg" src="assets/app-icon.png" alt="ライブラリポータル アイコン">
      </div>
      <h1 class="lp-auth-title">ライブラリポータル</h1>
      <p class="lp-auth-sub">この画面から、お使いの端末にアプリとして追加できます。</p>

      <p id="installState" class="lp-install-state" hidden></p>
      <button id="btnInstall" class="lp-btn lp-btn-primary lp-btn-block lp-install-btn" type="button" hidden>
        この端末にインストール
      </button>

      <div class="lp-install-steps">
        <section class="lp-install-os" id="stepIos">
          <h2>iPhone・iPad（Safari）</h2>
          <ol>
            <li>このページを <strong>Safari</strong> で開く</li>
            <li>画面下（iPad は上）の <strong>共有ボタン</strong> をタップ</li>
            <li><strong>「ホーム画面に追加」</strong> を選び、右上の「追加」をタップ</li>
          </ol>
          <p class="lp-install-note">Chrome など Safari 以外のブラウザからは追加できません。</p>
        </section>

        <section class="lp-install-os" id="stepAndroid">
          <h2>Android（Chrome）</h2>
          <ol>
            <li>上の <strong>「この端末にインストール」</strong> を押す</li>
            <li>ボタンが出ていない場合は、右上のメニュー（︙）から<strong>「アプリをインストール」</strong>を選ぶ</li>
          </ol>
        </section>

        <section class="lp-install-os" id="stepDesktop">
          <h2>パソコン（Chrome・Edge）</h2>
          <ol>
            <li>上の <strong>「この端末にインストール」</strong> を押す</li>
            <li>ボタンが出ていない場合は、アドレスバー右端の
              <strong>インストールアイコン</strong>（画面に＋が付いたマーク）を押す</li>
          </ol>
        </section>
      </div>

      <p class="lp-install-help">
        追加後は、デスクトップやホーム画面のアイコンから、ブラウザのタブを介さずに起動できます。
      </p>

      <p><a class="lp-btn lp-btn-ghost lp-btn-block" href="index.php">ライブラリポータルを開く</a></p>
    </div>
  </main>

  <script src="assets/pwa.js?v=2"></script>
  <script>
    (function () {
      var btn   = document.getElementById('btnInstall');
      var state = document.getElementById('installState');

      function show(el, text, cls) {
        el.textContent = text;
        el.className = 'lp-install-state' + (cls ? ' ' + cls : '');
        el.hidden = false;
      }

      window.LP_PWA.onChange(function () {
        if (window.LP_PWA.isInstalled()) {
          btn.hidden = true;
          show(state, 'この端末にはインストール済みです。', 'is-done');
          return;
        }
        if (window.LP_PWA.canPrompt()) {
          btn.hidden = false;
          state.hidden = true;
        } else {
          btn.hidden = true;
          show(state, window.LP_PWA.isIos()
            ? '下の「iPhone・iPad」の手順で、ホーム画面に追加してください。'
            : 'このブラウザでは自動のインストールボタンを利用できません。下の手順で追加してください。');
        }
      });

      btn.addEventListener('click', function () {
        btn.disabled = true;
        window.LP_PWA.install().then(function (outcome) {
          btn.disabled = false;
          if (outcome === 'accepted') {
            btn.hidden = true;
            show(state, 'インストールしました。ホーム画面（デスクトップ）のアイコンから起動できます。', 'is-done');
          } else if (outcome === 'dismissed') {
            show(state, 'インストールを中止しました。もう一度行う場合は、このページを再読み込みしてください。');
          } else {
            show(state, '下の手順から追加してください。');
          }
        });
      });
    })();
  </script>
</body>
</html>

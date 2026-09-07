# CLAUDE.md — OES入替作業APP

このディレクトリは「OES入替作業APP」プロジェクトです。詳細仕様は `SPEC.md` を参照してください。

## 最重要の前提：複数人で使うアプリである

**このアプリは会社の複数の担当者が、それぞれ自分の端末で使うことを前提に設計されている。
「一人用ツール」として単純化して考えないこと。**

- **設定（業態・時間帯・定型文・よく使う文など）は全員が同じ内容を使う。** 保存場所は
  index.html と同じ場所に置く **`settings.json` の1ファイルだけ**。起動時にこれを読み込み、
  全員が同じ内容で動く（`loadSharedSettings()`）。ブラウザの `localStorage` は、共有設定を
  読めなかったときの控えとしてのみ使う。
- **設定の変更は管理者だけが行う。** 設定タブは既定でロックされ、管理者パスワードを
  入れないと編集できない（`unlockSettings()`）。**パスワードの平文を `index.html` に書かないこと。**
  照合は `verifyAdminPassword()` が次の順で行う。
  ① `admin-auth.php`（あればサーバー側で照合。パスワードは `config.php` の中だけにあり、
     ブラウザからは見えない＝推奨） → ② `config.json` の `adminPasswordHash`（SHA-256、
     ソルトは `ADMIN_SALT` = `'oes-calendar:'`） → ③ 組み込みの `ADMIN_HASH_FALLBACK`（初期パスワード）。
  ②③はハッシュなので平文は読めないが総当たりは防げない。厳密に守るなら①か
  `deploy/.htaccess.sample` のBasic認証を併用する。初期パスワードは `deploy/config.php` を参照。
  解除時に入力された平文は `adminPw`（メモリのみ・`lockSettings()` で破棄）に置き、
  共有設定の保存POSTにだけ使う。**sessionStorage等に保存しないこと。**
- **「担当者」という概念は廃止済み。** 担当者名・担当者定型文・担当者ごとのカレンダーURL・
  差し込み文字 `{担当者}` `{担当者定型文}` はすべて削除した。復活させないこと
  （旧い設定ファイルに残っていても `fillTemplate()` が空文字に置き換えて消す）。
- **Googleカレンダーからの予定の直接読み込みは停止中。** 利用者ごとにカレンダーが違い、
  共有設定と噛み合わないため、作業当日タブからは削除した。設定項目（Apps ScriptのURL・
  絞り込み文字・接続テスト・コード表示）だけは将来の再開に備えて「詳細設定」に残してある。

## プロジェクト概要

GoogleカレンダーへOES入替作業の予定を登録するための、単一HTMLファイルのWebツール。

| ファイル | 役割 |
|---|---|
| `index.html` | 本体。HTML/CSS/JS＋画面用の画像（data URI）を内包 |
| `manifest.json` | PWAのマニフェスト（**実ファイルであることが必須**。data URIや `.webmanifest` に戻さない） |
| `icon-192.png` `icon-512.png` `icon-512-maskable.png` | PWAアイコン（**ルートに置く**。`assets/` にも同じものがある） |
| `sw.js` | Service Worker。Androidで独立したアプリ（WebAPK）として登録されるために必要 |
| `manual.html` | 操作マニュアル（レスポンシブHTML。ブラウザの印刷でPDF化可） |
| `assets/welsys-logo.jpg` | ウェルシス株式会社ロゴ（manual.html が参照。index.html は同じ画像をbase64で内包） |
| `assets/device-printer.jpg` `assets/device-kitchen.jpg` | 対象機器の画像（マルチプリンター／マルチステーション）。manual.html が参照。index.html は縮小版をbase64で内包 |
| `icon-*.png` | PWAアイコン（192/512/180/512-maskable）。**`manifest.json` から実URLで参照する**（Androidでアイコンを取得するために必須）。180はapple-touch-iconとしてindex.htmlにdata URIで内包 |
| `apps-script/Code.gs` | Googleカレンダー連携用のApps Scriptコード（現在は停止中の機能。参考用に保持） |
| `deploy/.htaccess.sample` | Basic認証用サンプル |
| `deploy/settings-save.php` | 共有設定をサーバーに保存するための任意のエンドポイント（PHPが動く場合のみ。`settings-save.php` という名前で index.html と同じ場所に置く） |
| `deploy/config.php` | 管理者パスワードの置き場所（PHP。ここが唯一の平文。`config.php` として置く） |
| `deploy/admin-auth.php` | `config.php` を使ってサーバー側でパスワードを照合するエンドポイント |
| `deploy/config.json.sample` | PHPが使えない場合のパスワード設定（SHA-256ハッシュ）。`config.json` として置く |
| `deploy/make-hash.html` | `config.json` 用のハッシュを作る手元用ページ（**サーバーには置かない**） |
| `legacy/` | Claude Chat時代の旧版（参照用・非稼働） |

## 重要なルール

- **フレームワーク・ビルドツール・外部CDN・npmパッケージは導入しない。** バニラJS（ES5相当）で書く。
- **画面に表示する画像は data URI で `index.html` に埋め込む**（ロゴ・機器写真・スプラッシュのアイコンなど）。
  ただし**PWAに必要なファイル（`manifest.json` `sw.js` `icon-*.png`）だけは実ファイルにする**。
  Androidでは data URI のマニフェストだとWebAPKが作られず、Chromeのマークが付いたただのショートカットに
  なってしまうため。**この3つをdata URI化・削除しないこと。**
- **設定の保存場所は「サーバー上の `settings.json` 1か所」**。起動時に `fetch` で読み込む（`SHARED_FILE`）。
  `localStorage`（キー: `oes-calendar-settings-v1`）は共有設定を読めなかったときの控えに過ぎない。
  保存は `settings-save.php` があればそこへPOSTし、無ければ `settings.json` をダウンロードさせて
  FTPでアップロードしてもらう（`saveSharedSettings()`）。**登録リスト（作業予定）は保存しない**（揮発）。
- **「保存し忘れ」で共有されない事故を起こさないこと。** 設定を変えたら `markSharedDirty()` が
  `sharedState.dirty` を立て、設定タブに未共有バナー（`#dirty-bar`）を出し、離脱時に警告する。
  起動時の `probeSaveEndpoint()` で `settings-save.php` が本当に使えると分かっている場合は、
  変更の1.5秒後に**自動でサーバーへ保存する**（管理者が押し忘れても共有される）。
  この「変更＝未共有として見せる」「使えるなら自動保存する」動作を外さないこと。
- **原因を利用者が自分で特定できるようにする。** 設定タブの `#diag-card`（`runDiagnostics()`）で
  https / `settings.json` / `settings-save.php` / パスワード設定 / `manifest.json` / アイコン /
  Service Worker / インストール状態を1つずつ確認し、✅⚠️❌と「どうすればよいか」を日本語で出す。
  **ロック中でも実行できるようにする**（管理者以外のスマホから確認する用途があるため、
  `#lock-card` `#install-card` と同様にロック対象から除外する）。
  `settings.json` の読み込み成功時は、業態の並び順とサーバー上の更新日時を `#shared-status` に出して、
  PCとスマホで見比べられるようにする。
- **設定タブは管理者パスワードでロックする。** ロック中は `#pane-set` に `locked` クラスが付き、
  CSSで入力・ボタンを操作不可にする。ただし**閲覧・折りたたみの開閉・「アプリとしてインストール」
  （`#install-card`）・「共有設定を再読み込み」（`#lock-card`）はロック中でも使える**
  （インストールや閲覧は管理者以外も必要なため、ロック対象から除外する）。
  変更系の関数には `requireAdmin()` を入れて二重に防ぐ。
- 日本語UI。**配色は「青（メイン `#1565C0`）× 空色（アクセント `#29B6F6`）」の2色（バイカラー）を維持する。**
  ヘッダー・選択中タブ・カード見出しの番号丸は青→空色のグラデーション/空色、カードの見出し帯・ボタンの薄色部分などの
  淡色背景は空色寄りの薄いトーン（`--blue-l` `--blue-l2` `--line` `--line-w`）にする。危険操作（削除）の赤・
  自動生成タグの緑・手動編集タグの琥珀色などの意味付き色はこの2色ルールの対象外（そのまま維持してよい）。
- **パソコン・スマートフォン両対応。スマホでの利用を重視し、余白・文字は必要最小限に詰める。**
  入力欄の `font-size` は16px以上を維持（iOSの自動ズーム防止）。タップ領域は**34px以上を死守**しつつ、
  それ以上は無闇に広げない（旧仕様の42px基準は廃止済み。詰めすぎて34px未満にしないことだけ守る）。
- **フッターのコピーライトのすぐ上に「📱 アプリインストールはこちらから」を常時置く**
  （`.install-link` → `openInstallHelp()`）。`beforeinstallprompt` を受け取っていればそのまま
  インストールダイアログを出し、そうでなければ端末に合わせた手順（iOS Safari / Android Chrome / PC）を
  モーダルで案内する。**この導線を消さないこと。**
- **フッターのコピーライトは、利用者から支給されたデザインに合わせた「バッジ」で表示する**
  （`.welsys-badge`：淡い緑 `#E7F4ED` の角丸ピル＋白地のWELSYSロゴ＋濃緑 `#003416` 太字の
  `© ウェルシス株式会社`）。**西暦は入れない。** この見た目は指定されたものなので勝手に変えない。
- **起動時にスプラッシュ（`#splash`）を出す。** OESアイコン＋アプリ名「OES入替作業APP」＋
  フッターと同じWELSYSバッジを1.2秒表示してフェードで消し、DOMから取り除く。
  **`pointer-events:none` を維持すること**（操作を妨げず、自動テストのクリックも遮らないため）。
- **カレンダーの月グリッドは廃止した。** 稼働件数ぶんの行にそれぞれ日付入力（`<input type=date>`）が付くため、
  月をめくって視認するUIは不要になった。カレンダーグリッド（`.cal-*`／`.day`／`renderCalendars()`等）を復活させないこと。
- **「カレンダー」画面は質問形式（ウィザード）で、①業態と稼働件数 → ②日付と時間帯 → ③店舗名と住所 →
  ④一覧で確認してGoogleカレンダーへ登録、の4ステップで1画面に完結する。**
  この「業態と件数を決める → 日付と時間帯を決める → 店舗名と住所を入れる」という順番は利用者が明示的に
  指定したものなので、勝手に1つの入力欄にまとめ直さないこと。
  - ①「業態と稼働件数を選ぶ」: `#w-gyotai`（業態）・`#w-count`（稼働件数、1〜30件から選ぶ）のみ。
    **担当者に関するものは一切置かない**（担当者の概念そのものを廃止済み）。
    選んだ業態の午前・午後それぞれの時刻を`#w-time-hint`に表示するだけの参考情報とする。
  - ②「日付と時間帯を選ぶ」: `#date-rows`に稼働件数ぶんの行（`.dt-row`）が並び、各行が**日付（`<input type=date>`）と
    午前/午後のトグル**を独立して持つ。同じ日を複数件でも、別々の日でもよい。
  - ③「店舗名と住所を入力」: `#store-rows`に同じ件数の行（`.st-row`）が並ぶ。②の行とは`data-idx`で1対1に対応し、
    各行の見出し（`#st-when-<i>`）に②で選んだ日付・時間帯を表示する（`updateRowWhen(i)`。②の変更・業態変更のたびに更新）。
    「このN件を登録リストへ」（`submitStoreRows()`）で、**②で日付が入っている行だけ**を登録リストに追加する
    （日付未選択の行は黙ってスキップし、件数はトーストで知らせる）。店舗名・住所は空欄でも登録してよい。
    業態は①で選んだものを全行共通で使う。登録後は②③とも入力を空に戻す（`resetStoreRows()`）。
  - ④「一覧で確認してGoogleカレンダーへ登録」: 登録リストは既定で折りたたみ表示（`.entry`に`open`クラスが無ければ
    本文非表示）にして、件数が多くても一覧が長くなりすぎないようにする（`toggleEntry(id)`で開閉、見出し行に要約と
    「登録」ボタンを常時表示）。カードを開いたときの編集項目は日付・業態・時間帯・開始・終了・店舗名・住所・説明文で、
    担当者に関する欄は無い。「Googleカレンダーで順番に登録」（`startQueue()`）と「ICSファイルで一括取込」
    （`downloadIcs()`）の両方をここに置く。
- **設定の「よく使う文」の編集欄は1行の `<input>` にして、操作ボタンと横並びにする**（`.ph-row`）。
  縦に長くならないようにするための指定なので、複数行のtextareaに戻さないこと。
- **作業当日タブの③退店連絡にある機器台数（MPR/MST/HT/BC/BP）は、入力した機器だけ反映する。**
  台数は**1〜10のプルダウン（`<select>`）で選ぶ**（数値入力の`<input type=number>`には戻さない）。
  空欄（未選択）のものは表示しない（テンプレート由来の空欄プレースホルダ行も読み込み時に除去する）。
  この「入力したものだけ出す」動作を、初期状態で全項目を表示するような実装に戻さないこと。
- **作業当日タブの①②③の各「コピー」ボタンの隣に「Chat」ボタンを置く。** 押すと、その作業の**業態＋店舗名**
  （`dayCtx.gyotai` / `dayCtx.tenpo`）に対応するGoogle ChatのURLを新しいタブで直接開く（`openStoreChat()`）。
  URLは`settings.storeChats[]`（`{id, gyotai, tenpo, url}`）に業態＋店舗名の組み合わせで保存する
  （同じ店舗名でも業態が違えば別ルームのことがあるため、店舗名だけでなく業態も含めて照合する＝`findStoreChat(gyotai,tenpo)`）。
  **該当する登録が無い場合は、`prompt()`等で案内せずそのまま一般のGoogle Chat（`https://chat.google.com/`）を開く。**
  自動登録プロンプトは廃止済み。登録は設定の「詳細設定」内の一覧（`#store-chat-list`）から手動で行う
  （業態・店舗名・URLの3項目、追加・編集・削除ができる）。
- **PWA対応（ホーム画面に追加できる）。** Androidで**Chromeのマークが付かない独立したアプリ（WebAPK）**として
  登録されるには、次がすべて必要（1つでも欠けるとただのショートカットになる）。
  ①`https://` 配信 ②`<link rel="manifest" href="manifest.json">`（**実ファイル**。`.webmanifest` は
  未知の拡張子として扱うサーバーがあるため **`.json` にする**）
  ③`manifest.json` に `id` `start_url` `scope` `display:standalone` と
  192/512/512-maskable のアイコンを**実URL**で書く ④`sw.js`（fetchハンドラを持つService Worker）を登録する。
  **PWAアイコンは `index.html` と同じ階層に置く**（`icon-192.png` `icon-512.png` `icon-512-maskable.png`）。
  `assets/` の中だけに置くと、`assets/` をアップロードし忘れた環境でアイコンを取得できず、
  アイコンの無いショートカットになってしまうため。`assets/` 側の同名ファイルは manual.html 用に残す。
  `sw.js` はネットワーク優先（FTPで差し替えたらすぐ反映されるように）で、
  `settings.json` `config.json` `*.php` はキャッシュしない。アイコンは
  `assets/device-printer.jpg` に「OES」の青いバッジを重ねたもの（`icon-*.png` に元データを同梱）。
  アイコンやアプリ名を変える場合は `manifest.json` と `icon-*.png` を差し替える
  （スプラッシュ用のアイコンはindex.html内のdata URIなので、Pythonなどでbase64を作り直して差し替える）。
  設定タブに「📱 アプリとしてインストール」カードを置き、`beforeinstallprompt`イベントを受け取れた場合だけ
  ボタン（`#btn-install-pwa`）を表示してその場でインストールできるようにする（`installPwa()`）。
  イベントに対応しないブラウザ（iOS Safari等）ではボタンは出さず、下の案内文とマニュアルへのリンクのみ表示する。
- **業態は設定画面で並び替えできる**（各業態の見出しの「↑」「↓」＝`moveGyotai()`）。
  この並び順がそのままカレンダー画面の業態プルダウンの順番になる。
- **設定タブは常時表示を最小限にする。** 使用頻度の低い項目（共通設定・作業当日の目印・カレンダー連携）は
  「詳細設定」1枚に折りたたむ（既定は閉）。新しい設定項目を追加する場合も、まず「詳細設定」に入れることを検討する
  （毎回必ず調整するような項目だけを常時表示に置く）。
- **アプリ名は「OES入替作業APP」で統一する**（`<title>`・ヘッダー・マニュアル・各ドキュメント）。
  カレンダーの予定タイトル `OES入替作業({業態} {店舗名})` はこれとは別物なので変更しない。
- 機器画像はタイトル横に**小さく控えめに**置く（高さ34〜38px）。主張させない。
- **`index.html` の仕様を変えたら `manual.html` と `SPEC.md` も必ず更新する。**

## 画面構成（3タブ）

1. **📅 カレンダー** — 質問形式のウィザードで、日付選択・入力・Googleカレンダーへの登録までをこの1画面で完結する
   （旧版の「登録リスト」タブは廃止し、このタブに統合済み。**タブを分ける設計に戻さないこと**）。詳細は上の
   「重要なルール」を参照。
   - ①「業態と稼働件数を選ぶ」カード
   - ②「日付と時間帯を選ぶ」カード: 件数ぶんの行に日付・午前/午後を入力
   - ③「店舗名と住所を入力」カード: ②と対応する行に店舗名・住所を入力し、「このN件を登録リストへ」で④へ追加
   - ④「一覧で確認してGoogleカレンダーへ登録」カード: 登録リスト（既定は折りたたみ）。「Googleカレンダーで順番に登録」
     （`startQueue()`）と「ICSファイルで一括取込」（`downloadIcs()`）を配置する。
2. **🛠️ 作業当日** — **主動線は「業態をプルダウンで選び、店舗名を入れるだけ」**（`#day-gyotai` /
   `#day-tenpo` →「この内容で連絡文を作る」＝`buildDayFromGyotai()`）。その業態・時間帯の定型文から
   入店/中間報告/退店を作る。時間帯（`#day-slot-field`）は**その業態の時間帯が2つ以上あるときだけ**出す。
   Googleカレンダーの説明文の貼り付けは、同じカード内の折りたたみ（`#gy-paste`）に入れた**補助動線**。
   各欄のコピー → 隣の「Chat」ボタンでその業態・店舗のGoogle Chatを開いて貼り付ける。
   画面下に「よく使う文」の小さなコピーチップを置く（**控えめな見た目を維持する**。カード化しない）
3. **⚙️ 設定** — 管理者ロック＋共有設定の状態表示、業態・時間帯・定型文（並び替え可）、よく使う文、
   業態・店舗ごとのGoogle Chat URL、作業当日の目印、Googleカレンダー連携（停止中・項目のみ）、
   共通設定、共有設定の保存、アプリとしてインストール

### 作業当日タブの方針
- **「業態を選ぶ＋店舗名を入れる」だけで完結することを主役にする。** カレンダーもコピーも不要で使えること。
  この動線が目立たなくなるような画面変更をしないこと（貼り付けは折りたたみの補助動線に留める）。
- **Google Chat への自動送信はしない。** コピー＆手動貼り付け。誤送信を防ぐためこの方針を維持する。
- **Apps Script 連携は任意機能。** 未設定でも「説明文の貼り付け」だけで全機能が使えること（この前提を壊さない）。
- `apps-script/Code.gs` と `index.html` の `APPS_SCRIPT_CODE` は**同じ内容**。片方を直したらもう片方も直す。

## 主な編集ポイント（index.html内）

| 関数・定数 | 役割 |
|---|---|
| `defaultSettings()` | 初期の業態・時間帯・定型文。既定値の変更はここ |
| `TPL_STANDARD` / `TPL_SUKIYA` / `TPL_STAFF` | 定型文のひな形 |
| `normalizeSettings(o)` | 読み込んだ設定の正規化。**設定項目を増やしたらここも必ず更新** |
| `fillTemplate(tpl, ctx)` | 差し込み文字の置換。廃止した `{担当者}` 系は空文字にして消す |
| `buildDesc(en)` / `buildTitle(en)` | 説明文・タイトルの生成 |
| `renderWizardForm(keepValues)` | ①業態・稼働件数のプルダウンを描画し、②の行を再構成する（`renderStoreRows()`を呼ぶ） |
| `onWizGyotaiChange()` / `updateWizardHints()` | 業態変更時の時刻ヒント（午前・午後の時刻）更新 |
| `onWCountChange()` / `renderStoreRows()` | 稼働件数の変更に合わせて②③の入力行を過不足なく増減する（②③は同数・`data-idx`で対応） |
| `wizSlotFor(ap)` | 現在選択中の業態について、'am'/'pm'に対応する時間帯（`slots[0]`/`slots[1]`）を返す |
| `dateRowHtml(i)` / `storeRowHtml(i)` | ②（日付・時間帯）と③（店舗名・住所）の1行分のHTML生成 |
| `onRowDateChange(i)` / `onRowAmPmChange(el,i)` / `updateRowWhen(i)` | ②の入力を③の各行見出し（日付・時間帯の表示）へ反映する |
| `submitStoreRows()` / `resetStoreRows()` | ②③を突き合わせて日付のある行だけ登録リストへ追加／登録後に②③の入力を空へ戻す |
| `toggleEntry(id)` | ③登録リストの各カードの折りたたみ開閉 |
| `entryInnerHtml(en, i)` | 登録リストのカード1件分のHTML |
| `googleUrl(en)` | GoogleカレンダーのTEMPLATE URL |
| `buildIcs()` / `icsFold()` | ICS生成（RFC5545の75オクテット折返し） |
| `renderSettings()` | 設定画面の描画 |
| `moveGyotai(i, d)` | 業態の並び替え（カレンダー画面のプルダウンの順番になる） |
| `applyLockState()` / `unlockSettings()` / `lockSettings()` / `requireAdmin()` | 設定タブの管理者ロック |
| `loadSharedSettings()` / `saveSharedSettings()` / `reloadSharedSettings()` | 共有設定（`settings.json`）の読み込み・保存 |
| `markSharedDirty()` / `updateDirtyBar()` | 未共有の変更を記録し、設定タブに警告バーを出す。保存できる環境なら自動保存を予約する |
| `probeSaveEndpoint()` | 起動時に `settings-save.php` が本当に使えるかを判定する（PHP未実行・config.php欠落・404を区別する） |
| `runDiagnostics()` / `diagRow()` | 設定タブの「サーバーの状態をチェック」。共有されない／インストールできない原因を1つずつ表示する |
| `toggleGy(id)` | 折りたたみ（業態・「詳細設定」で共通利用）の開閉。要素idは `gy-<id>` |
| `splitReport(text)` | 説明文を入店/中間報告/退店に振り分ける。目印は `settings.dayKeywords` |
| `stripEquipmentLines(text)` / `rebuildEquipmentBlock()` | ③退店連絡の機器台数（MPR/MST/HT/BC/BP）を除去・再構築。入力した機器だけ固定順で反映する |
| `renderEquipRow()` / `onEquipInput(code,v)` / `dayCounts` | 機器台数プルダウン（1〜10）の描画・変更ハンドラ・現在値（候補切替でリセット） |
| `openStoreChat()` / `findStoreChat(gyotai,tenpo)` | 業態＋店舗のGoogle Chatを直接開く／検索。該当なしなら案内せず一般のChatを開く |
| `renderStoreChatList()` ほか | 設定画面での業態・店舗Chat URLの追加・編集・削除（`settings.storeChats`） |
| `sha256Hex(str)` | 外部ライブラリを使わないSHA-256。管理者パスワードの照合に使う（httpsでない環境でも動くようにするため） |
| `loadAdminConfig()` / `verifyAdminPassword(v, cb)` | `config.json` の読み込みと、パスワードの照合（admin-auth.php → ハッシュの順） |
| `currentAdminPw()` | 共有設定の保存に使う平文パスワード。無ければ聞き直す（保存はしない） |
| `renderDayGyotaiForm()` / `renderDaySlotRow()` / `buildDayFromGyotai()` | 作業当日タブの主動線（業態＋店舗名から、定型文で連絡文を作る） |
| `openInstallHelp()` / `openModal()` | フッターの「アプリインストールはこちらから」。すぐ入れられるならダイアログ、無理ならOS別の手順を出す |
| `installPwa()` | `beforeinstallprompt`で保持したイベントの`.prompt()`を呼び、PWAインストールダイアログを出す |
| `renderPhraseChips()` / `copyPhrase(i)` | よく使う文のチップ描画とコピー |
| `renderPhraseList()` ほか | 設定画面でのよく使う文の追加・並び替え・削除 |
| `detectFromText(text)` | 本文から業態・店舗名・時刻を推測 |
| `htmlToText(t)` | HTML混じりの説明文をプレーンテキスト化 |
| `copyText(text, cb)` | クリップボードへコピー（execCommandフォールバック付き） |
| `fetchCalendar(url, date, q)` | Apps Scriptから当日の予定を取得 |
| `APPS_SCRIPT_CODE` | 設定画面で表示するApps Scriptコード |

## 差し込み文字

`{業態}` `{店舗名}` `{日付}` `{開始}` `{終了}` `{時間帯}`
（旧版互換で `★0`〜`★3` も置換される）

## 動作確認

`index.html` をブラウザで直接開けば動く（サーバー不要）。
自動テストを走らせる場合はPlaywrightで、PC（1280x900）とスマホ（390x844）の両方を確認する。
共有設定（`settings.json`）の読み込みは `file://` では動かないため、その確認だけは簡易サーバー
（例: `python3 -m http.server`）で行う。
確認観点: ①業態・稼働件数の選択、②③の行ごとに異なる日付・AM/PM・店舗名・住所を入力してのN件一括作成
（日付未選択行のスキップ含む）、④一覧の折りたたみ表示・Google登録・ICS一括取込、定型文の編集反映、
業態の並び替えがカレンダーのプルダウンに反映されること、管理者ロック（誤パスワードで解除されない／
ロック中は変更できない／インストールと閲覧はできる）、共有設定の読み込み・保存と別ブラウザへの反映、
ICSの中身、作業当日の貼り付けからの振り分け・コピー・業態＋店舗別Chatボタン（未登録時に案内なしで
一般Chatへフォールバックすること）、機器台数プルダウン、PWAインストールボタンの表示切り替え、
横スクロールが出ないこと、JSエラーが出ないこと。
管理者パスワードは、①設定ファイル無し（組み込みハッシュ）②`config.json` を置いた場合
③`admin-auth.php` を置いた場合の3通りで、正しい／誤ったパスワードの挙動を確認する。
`index.html` に平文パスワードが含まれていないこと（`grep -c 'Welsys@1234' index.html` が 0）も確認する。
PWAは `manifest.json` がdata URIでなく実ファイルとして参照でき、アイコンのURLが全て取得でき、
Service Workerが登録されることを確認する（`http://` の簡易サーバーでも登録自体は確認できる）。

## 配置

サーバーのドキュメントルート配下に `index.html` / `manual.html` / `assets/` を同じ階層で置く。
Basic認証を使う場合は `deploy/.htaccess.sample` を参考にする（`AuthUserFile` は配置先の絶対パスに書き換える）。

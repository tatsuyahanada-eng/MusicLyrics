/* ============================================================
   ライブラリポータル — library.js  v1
   関連会社で共有する資料 / プログラム / アプリの更新履歴管理（サンプル）
   ============================================================ */
'use strict';

const STORAGE_KEY = 'welsys_library_portal_v1';

/* ---------- サンプルデータ ----------
   history は新しい順に並べる（[0] が最新更新）           */
const SAMPLE_ITEMS = [
  {
    id: 'APP-001',
    name: '交通費精算ツール',
    category: 'アプリ',
    owner: '管理部',
    createdAt: '2024-04-18',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/expense-tool/v2.3.1/setup.zip',
    description: '訪問先の住所から交通費を自動計算し、月次の精算書（Excel / CSV）を出力する Windows 向けツール。関連会社共通で利用。',
    history: [
      {
        date: '2025-08-21', time: '14:30', author: '花田 達也', kind: '機能追加', version: 'v2.3.1',
        summary: 'CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化',
        target: 'CSV出力機能 / 月次精算書出力',
        files: [
          'src/export/csvExporter.js : buildRow() に deptCode を追加',
          'db/schema/expense.sql : dept_code カラムを追加（NOT NULL, 既定値 000）',
          'src/ui/SettingsDialog.js : 部署コード入力欄を追加'
        ],
        ticket: 'WLS-1042'
      },
      {
        date: '2025-06-03', time: '10:05', author: '佐藤 美咲', kind: '不具合修正', version: 'v2.3.0',
        summary: '月をまたぐ出張で日付が前月に集計されてしまう不具合を修正',
        target: '月次集計処理',
        files: [
          'src/calc/monthlyAggregator.js : 集計基準日を出発日→帰着日に変更',
          'test/monthlyAggregator.test.js : 月跨ぎケースのテストを追加'
        ],
        ticket: 'WLS-0987'
      },
      {
        date: '2024-04-18', time: '09:00', author: '花田 達也', kind: '初版公開', version: 'v1.0.0',
        summary: '初版を公開（交通費入力・精算書出力の基本機能）',
        target: '全機能',
        files: ['初回リリース一式'],
        ticket: 'WLS-0501'
      }
    ]
  },
  {
    id: 'APP-002',
    name: '駐車場検索アシスタント',
    category: 'アプリ',
    owner: '情報システム室',
    createdAt: '2025-02-10',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/parking-finder/v1.4.0/parking-finder.zip',
    description: 'カレンダーの予定の住所から近隣のコインパーキングを検索し、予定の説明欄へ自動追記するアシスタント。訪問業務の事前準備を短縮する目的で導入。',
    history: [
      {
        date: '2025-08-28', time: '17:12', author: '田中 亮', kind: '改善', version: 'v1.4.0',
        summary: '検索対象を時間貸し（コインパーキング）のみに限定し、月極を除外',
        target: '駐車場検索ロジック',
        files: [
          'skills/parking_finder/search.py : filter_coin_parking() を追加',
          'skills/parking_finder/SKILL.md : 検索条件の説明を更新'
        ],
        ticket: 'WLS-1101'
      },
      {
        date: '2025-07-15', time: '11:40', author: '田中 亮', kind: '機能追加', version: 'v1.3.0',
        summary: '処理完了後に「JCOM交通費精算」予定を 18:00〜19:00 で自動作成',
        target: 'カレンダー連携 / 予定作成',
        files: [
          'skills/parking_finder/calendar.py : create_expense_event() を追加',
          'skills/parking_finder/config.yaml : 既定カレンダーを「その他業務」に設定'
        ],
        ticket: 'WLS-1077'
      }
    ]
  },
  {
    id: 'PRG-101',
    name: '受注データ取込バッチ',
    category: 'プログラム',
    owner: '情報システム室',
    createdAt: '2023-11-06',
    downloadUrl: 'https://share.welsys.example.co.jp/programs/order-import/v3.1.2/order-import.tar.gz',
    description: '取引先から受領した受注CSVを基幹システムへ取り込む夜間バッチ。文字コード変換・重複チェック・エラーログ出力を行う。',
    history: [
      {
        date: '2025-08-05', time: '22:45', author: '鈴木 健一', kind: '不具合修正', version: 'v3.1.2',
        summary: 'Shift_JIS の機種依存文字が混在した場合に取込が中断する不具合を修正',
        target: '文字コード変換処理',
        files: [
          'batch/import/encoding.py : cp932 フォールバックを追加',
          'batch/import/errorLogger.py : 変換失敗行を警告ログへ出力'
        ],
        ticket: 'WLS-1055'
      },
      {
        date: '2025-03-19', time: '20:10', author: '鈴木 健一', kind: '改善', version: 'v3.1.0',
        summary: '重複チェックをインデックス化し、処理時間を約 40% 短縮',
        target: '重複チェック処理',
        files: [
          'batch/import/duplicateChecker.py : 事前ハッシュ化に変更',
          'db/index/order_idx.sql : order_no + partner_cd の複合インデックスを追加'
        ],
        ticket: 'WLS-0995'
      }
    ]
  },
  {
    id: 'DOC-201',
    name: '共有サーバー運用手順書',
    category: '資料',
    owner: '管理部',
    createdAt: '2024-01-22',
    downloadUrl: 'https://share.welsys.example.co.jp/docs/server-operation/v4/server-operation_v4.pdf',
    description: '関連会社共通の共有サーバーについて、フォルダ構成・権限申請・バックアップ手順をまとめた運用手順書（PDF）。',
    history: [
      {
        date: '2025-07-30', time: '13:20', author: '井上 由紀', kind: '資料改訂', version: 'v4.0',
        summary: 'バックアップ世代を 3 世代 → 7 世代へ変更した内容を反映',
        target: '第5章 バックアップ運用',
        files: [
          'server-operation_v4.pdf : 第5章 P.18-21 を改訂',
          'backup/rotate.sh : 保持世代の設定値を 7 に変更（プログラム側の対応）'
        ],
        ticket: 'WLS-1030'
      },
      {
        date: '2024-09-12', time: '16:00', author: '井上 由紀', kind: '資料改訂', version: 'v3.0',
        summary: '権限申請フローを電子申請に変更した内容を反映',
        target: '第3章 権限申請',
        files: ['server-operation_v3.pdf : 第3章 全面改訂'],
        ticket: 'WLS-0902'
      }
    ]
  },
  {
    id: 'DOC-202',
    name: '安否確認システム 利用マニュアル',
    category: 'マニュアル',
    owner: '総務部',
    createdAt: '2025-05-09',
    downloadUrl: 'https://share.welsys.example.co.jp/docs/safety-check/v1.2/safety-check-manual_v1.2.pdf',
    description: '災害時の安否確認システムについて、社員向けの登録手順と回答方法を説明したマニュアル。関連会社各社へ配布。',
    history: [
      {
        date: '2025-08-18', time: '09:45', author: '佐藤 美咲', kind: '資料改訂', version: 'v1.2',
        summary: 'スマートフォンアプリ版の画面刷新にあわせて画面キャプチャを差し替え',
        target: '第2章 スマートフォンからの回答',
        files: ['safety-check-manual_v1.2.pdf : 第2章 P.6-11 のキャプチャを差し替え'],
        ticket: 'WLS-1088'
      }
    ]
  },
  {
    id: 'APP-003',
    name: '勤怠打刻ダッシュボード',
    category: 'アプリ',
    owner: '人事部',
    createdAt: '2025-01-15',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/attendance-dashboard/v0.9.3/dashboard.zip',
    description: '各社の勤怠打刻データを集約し、未打刻・残業超過をアラート表示する社内Webダッシュボード（試験公開中）。',
    history: [
      {
        date: '2025-08-26', time: '19:05', author: '田中 亮', kind: '機能追加', version: 'v0.9.3',
        summary: '残業 45 時間超の対象者をアラート一覧に表示する機能を追加',
        target: 'アラート判定 / ダッシュボード表示',
        files: [
          'web/src/alert/overtimeRule.ts : 45時間しきい値の判定を追加',
          'web/src/pages/Dashboard.tsx : アラートカードを追加'
        ],
        ticket: 'WLS-1096'
      },
      {
        date: '2025-06-27', time: '15:30', author: '鈴木 健一', kind: '不具合修正', version: 'v0.9.1',
        summary: '深夜勤務の打刻が翌日扱いとなり未打刻と判定される不具合を修正',
        target: '未打刻判定処理',
        files: ['web/src/calc/shiftResolver.ts : 勤務日の判定を勤務開始基準へ変更'],
        ticket: 'WLS-1024'
      }
    ]
  }
];

const KIND_CLASS = {
  '機能追加': 'feature',
  '不具合修正': 'bugfix',
  '改善': 'improve',
  '資料改訂': 'doc',
  '初版公開': 'initial'
};

/* ---------- 状態 ---------- */
let items = [];
const state = { view: 'library', q: '', category: '', owner: '', author: '', sort: 'updated_desc' };

/* ---------- ユーティリティ ---------- */
const $ = (id) => document.getElementById(id);

function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function safeUrl(url) {
  return /^https?:\/\//i.test(String(url || '')) ? url : '';
}

function latest(item) {
  return item.history && item.history.length ? item.history[0] : null;
}

function sortHistory(item) {
  item.history.sort((a, b) => (`${b.date} ${b.time}`).localeCompare(`${a.date} ${a.time}`));
}

function fmtDate(d) {
  if (!d) return '—';
  const [y, m, day] = String(d).split('-');
  return `${y}/${m}/${day}`;
}

function kindBadge(kind) {
  const cls = KIND_CLASS[kind] || 'improve';
  return `<span class="lp-badge lp-kind-${cls}">${esc(kind)}</span>`;
}

/* ---------- 保存 / 読込 ---------- */
function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length) { items = parsed; return; }
    }
  } catch (e) { /* 破損時はサンプルへフォールバック */ }
  items = JSON.parse(JSON.stringify(SAMPLE_ITEMS));
}

function save() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(items)); }
  catch (e) { toast('保存に失敗しました（ブラウザの設定をご確認ください）'); }
}

/* ---------- 絞り込み ---------- */
function historyMatchesQuery(h, q) {
  return [h.summary, h.target, h.author, h.kind, h.version, h.ticket, (h.files || []).join(' ')]
    .join(' ').toLowerCase().includes(q);
}

function filteredItems() {
  const q = state.q.trim().toLowerCase();
  let list = items.filter((it) => {
    if (state.category && it.category !== state.category) return false;
    if (state.owner && it.owner !== state.owner) return false;
    if (state.author && !it.history.some((h) => h.author === state.author)) return false;
    if (!q) return true;
    const base = [it.id, it.name, it.category, it.owner, it.description].join(' ').toLowerCase();
    return base.includes(q) || it.history.some((h) => historyMatchesQuery(h, q));
  });

  const key = (it) => {
    const h = latest(it);
    return h ? `${h.date} ${h.time}` : '';
  };
  list.sort((a, b) => {
    switch (state.sort) {
      case 'updated_asc': return key(a).localeCompare(key(b));
      case 'created_desc': return String(b.createdAt).localeCompare(String(a.createdAt));
      case 'name_asc': return a.name.localeCompare(b.name, 'ja');
      default: return key(b).localeCompare(key(a));
    }
  });
  return list;
}

function filteredHistory() {
  const q = state.q.trim().toLowerCase();
  const rows = [];
  items.forEach((it) => {
    if (state.category && it.category !== state.category) return;
    if (state.owner && it.owner !== state.owner) return;
    it.history.forEach((h) => {
      if (state.author && h.author !== state.author) return;
      if (q) {
        const base = [it.id, it.name, it.category, it.owner, it.description].join(' ').toLowerCase();
        if (!base.includes(q) && !historyMatchesQuery(h, q)) return;
      }
      rows.push({ item: it, h });
    });
  });
  const asc = state.sort === 'updated_asc';
  rows.sort((a, b) => {
    const ka = `${a.h.date} ${a.h.time}`, kb = `${b.h.date} ${b.h.time}`;
    return asc ? ka.localeCompare(kb) : kb.localeCompare(ka);
  });
  return rows;
}

/* ---------- 描画 ---------- */
function renderStats() {
  const histCount = items.reduce((n, it) => n + it.history.length, 0);
  const ym = new Date().toISOString().slice(0, 7);
  const monthCount = items.reduce(
    (n, it) => n + it.history.filter((h) => String(h.date).slice(0, 7) === ym).length, 0);
  const members = new Set();
  items.forEach((it) => it.history.forEach((h) => members.add(h.author)));

  $('statItems').textContent = items.length;
  $('statHistory').textContent = histCount;
  $('statMonth').textContent = monthCount;
  $('statMembers').textContent = members.size;
}

function renderLibrary() {
  const list = filteredItems();
  $('libraryBody').innerHTML = list.map((it) => {
    const h = latest(it);
    const url = safeUrl(it.downloadUrl);
    return `
      <tr>
        <td>
          <button class="lp-name-link" data-detail="${esc(it.id)}">${esc(it.name)}</button>
          <span class="lp-id">${esc(it.id)}</span>
          <span class="lp-badge lp-badge-cat">${esc(it.category)}</span>
          <span class="lp-id">${esc(it.owner)}</span>
        </td>
        <td class="lp-nowrap lp-time">${fmtDate(it.createdAt)}</td>
        <td class="lp-nowrap">
          ${h ? `<span class="lp-time">${fmtDate(h.date)} ${esc(h.time)}</span>
                 <span class="lp-id">${esc(h.author)}</span>` : '<span class="lp-muted">—</span>'}
        </td>
        <td>
          ${h ? `${kindBadge(h.kind)}
                 <div class="lp-tl-summary">${esc(h.summary)}</div>
                 <div class="lp-target-name lp-muted">対象機能：${esc(h.target)}</div>`
              : '<span class="lp-muted">更新履歴なし</span>'}
        </td>
        <td class="lp-nowrap">${h && h.version ? `<span class="lp-ver">${esc(h.version)}</span>` : '<span class="lp-muted">—</span>'}</td>
        <td>
          ${url ? `<a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">⬇ ダウンロード</a>
                   <span class="lp-dl-url">${esc(url)}</span>`
                : '<span class="lp-muted">URL 未設定</span>'}
        </td>
        <td class="lp-desc">${esc(it.description)}</td>
      </tr>`;
  }).join('');
  $('libraryEmpty').hidden = list.length > 0;
}

function renderHistory() {
  const rows = filteredHistory();
  $('historyBody').innerHTML = rows.map(({ item, h }) => `
    <tr>
      <td class="lp-nowrap lp-time">${fmtDate(h.date)}<br>${esc(h.time)}</td>
      <td>
        <button class="lp-name-link" data-detail="${esc(item.id)}">${esc(item.name)}</button>
        <span class="lp-id">${esc(item.id)} ／ ${esc(item.category)}</span>
      </td>
      <td>${kindBadge(h.kind)}</td>
      <td>
        <div class="lp-tl-summary">${esc(h.summary)}</div>
        ${h.ticket ? `<span class="lp-ticket">管理番号：${esc(h.ticket)}</span>` : ''}
      </td>
      <td>
        <span class="lp-target-name">${esc(h.target)}</span>
        ${(h.files && h.files.length)
          ? `<ul class="lp-files">${h.files.map((f) => `<li>${esc(f)}</li>`).join('')}</ul>` : ''}
      </td>
      <td class="lp-nowrap">${h.version ? `<span class="lp-ver">${esc(h.version)}</span>` : '<span class="lp-muted">—</span>'}</td>
      <td class="lp-nowrap">${esc(h.author)}</td>
    </tr>`).join('');
  $('historyEmpty').hidden = rows.length > 0;
}

function renderFilters() {
  const fill = (sel, values, label) => {
    const cur = sel.value;
    sel.innerHTML = `<option value="">${label}：すべて</option>` +
      values.map((v) => `<option value="${esc(v)}">${esc(v)}</option>`).join('');
    sel.value = values.includes(cur) ? cur : '';
  };
  const uniq = (arr) => [...new Set(arr)].sort((a, b) => a.localeCompare(b, 'ja'));
  fill($('filterCategory'), uniq(items.map((i) => i.category)), '種別');
  fill($('filterOwner'), uniq(items.map((i) => i.owner)), '管理部署');
  fill($('filterAuthor'), uniq(items.flatMap((i) => i.history.map((h) => h.author))), '対応者');
}

function renderItemOptions() {
  $('fItem').innerHTML = items
    .map((it) => `<option value="${esc(it.id)}">${esc(it.id)}：${esc(it.name)}</option>`).join('');
}

function render() {
  renderStats();
  if (state.view === 'library') renderLibrary(); else renderHistory();
  $('viewLibrary').hidden = state.view !== 'library';
  $('viewHistory').hidden = state.view !== 'history';
}

/* ---------- 詳細ドロワー ---------- */
function openDetail(id) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  const url = safeUrl(it.downloadUrl);

  $('detailMeta').textContent = `${it.id} ／ ${it.category} ／ 管理：${it.owner}`;
  $('detailName').textContent = it.name;
  $('detailBody').innerHTML = `
    <dl class="lp-detail-grid">
      <dt>説明</dt><dd>${esc(it.description)}</dd>
      <dt>作成日</dt><dd>${fmtDate(it.createdAt)}</dd>
      <dt>最終更新</dt><dd>${latest(it) ? `${fmtDate(latest(it).date)} ${esc(latest(it).time)}（${esc(latest(it).author)}）` : '—'}</dd>
      <dt>現在の版数</dt><dd>${latest(it) && latest(it).version ? `<span class="lp-ver">${esc(latest(it).version)}</span>` : '—'}</dd>
      <dt>入手先</dt>
      <dd>${url ? `<a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">⬇ ダウンロード</a>
                   <span class="lp-dl-url">${esc(url)}</span>` : 'URL 未設定'}</dd>
    </dl>
    <h3 class="lp-section-title">更新履歴（${it.history.length} 件）</h3>
    <ol class="lp-timeline">
      ${it.history.map((h) => `
        <li class="lp-tl-item">
          <div class="lp-tl-head">
            <span class="lp-tl-date">${fmtDate(h.date)} ${esc(h.time)}</span>
            ${kindBadge(h.kind)}
            ${h.version ? `<span class="lp-ver">${esc(h.version)}</span>` : ''}
            <span class="lp-tl-author">対応者：${esc(h.author)}</span>
            ${h.ticket ? `<span class="lp-ticket">${esc(h.ticket)}</span>` : ''}
          </div>
          <p class="lp-tl-summary">${esc(h.summary)}</p>
          <div class="lp-tl-target">
            <span class="lp-tl-target-label">対象機能</span>
            <span class="lp-target-name">${esc(h.target)}</span>
            ${(h.files && h.files.length) ? `
              <span class="lp-tl-target-label" style="margin-top:6px">修正したプログラム・ファイル</span>
              <ul class="lp-files">${h.files.map((f) => `<li>${esc(f)}</li>`).join('')}</ul>` : ''}
          </div>
        </li>`).join('')}
    </ol>`;

  $('detailDrawer').hidden = false;
  $('drawerOverlay').hidden = false;
}

function closeDetail() {
  $('detailDrawer').hidden = true;
  $('drawerOverlay').hidden = true;
}

/* ---------- 更新登録モーダル ---------- */
function openModal() {
  renderItemOptions();
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  $('fDate').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  $('fTime').value = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  $('updateModal').hidden = false;
  $('modalOverlay').hidden = false;
  $('fItem').focus();
}

function closeModal() {
  $('updateModal').hidden = true;
  $('modalOverlay').hidden = true;
  $('updateForm').reset();
}

function submitUpdate(ev) {
  ev.preventDefault();
  const it = items.find((x) => x.id === $('fItem').value);
  if (!it) return;

  const entry = {
    date: $('fDate').value,
    time: $('fTime').value,
    author: $('fAuthor').value.trim(),
    kind: $('fKind').value,
    version: $('fVersion').value.trim(),
    summary: $('fSummary').value.trim(),
    target: $('fTarget').value.trim(),
    files: $('fFiles').value.split('\n').map((s) => s.trim()).filter(Boolean),
    ticket: $('fTicket').value.trim()
  };
  it.history.push(entry);
  sortHistory(it);

  const newUrl = $('fUrl').value.trim();
  if (newUrl) it.downloadUrl = newUrl;

  save();
  renderFilters();
  render();
  closeModal();
  toast(`「${it.name}」に更新履歴を登録しました`);
}

/* ---------- トースト ---------- */
let toastTimer = null;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
}

/* ---------- 初期化 ---------- */
function init() {
  load();
  items.forEach(sortHistory);
  renderFilters();
  render();

  const now = new Date();
  $('syncTime').textContent =
    `${now.getFullYear()}/${String(now.getMonth() + 1).padStart(2, '0')}/${String(now.getDate()).padStart(2, '0')} ` +
    `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;

  $('searchInput').addEventListener('input', (e) => { state.q = e.target.value; render(); });
  $('filterCategory').addEventListener('change', (e) => { state.category = e.target.value; render(); });
  $('filterOwner').addEventListener('change', (e) => { state.owner = e.target.value; render(); });
  $('filterAuthor').addEventListener('change', (e) => { state.author = e.target.value; render(); });
  $('sortSelect').addEventListener('change', (e) => { state.sort = e.target.value; render(); });

  $('btnReset').addEventListener('click', () => {
    Object.assign(state, { q: '', category: '', owner: '', author: '', sort: 'updated_desc' });
    $('searchInput').value = '';
    ['filterCategory', 'filterOwner', 'filterAuthor'].forEach((id) => { $(id).value = ''; });
    $('sortSelect').value = 'updated_desc';
    render();
  });

  document.querySelectorAll('.lp-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      state.view = tab.dataset.view;
      document.querySelectorAll('.lp-tab').forEach((t) => {
        const on = t === tab;
        t.classList.toggle('is-active', on);
        t.setAttribute('aria-selected', String(on));
      });
      render();
    });
  });

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-detail]');
    if (btn) openDetail(btn.dataset.detail);
  });

  $('btnCloseDrawer').addEventListener('click', closeDetail);
  $('drawerOverlay').addEventListener('click', closeDetail);
  $('btnNewUpdate').addEventListener('click', openModal);
  $('btnCloseModal').addEventListener('click', closeModal);
  $('btnCancel').addEventListener('click', closeModal);
  $('modalOverlay').addEventListener('click', closeModal);
  $('updateForm').addEventListener('submit', submitUpdate);

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('updateModal').hidden) closeModal();
    else if (!$('detailDrawer').hidden) closeDetail();
  });
}

document.addEventListener('DOMContentLoaded', init);

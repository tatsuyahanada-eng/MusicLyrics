/* ============================================================
   ライブラリポータル — library.js  v2
   1行 = 1アイテムのアコーディオン一覧（サンプル画面）

   ■ データの供給元について
     DATA_SOURCE = 'sample' … このファイル内のサンプル定義 + localStorage（現状）
     DATA_SOURCE = 'api'    … バックエンド API 経由でデータベースを参照（本番想定）
     API 接続時に必要なエンドポイントは docs/library-portal-db.md を参照。
   ============================================================ */
'use strict';

const DATA_SOURCE = 'sample';           // 本番接続時は 'api' に変更
const API_BASE = '/api/library';        // 例: https://portal.welsys.co.jp/api/library
const STORAGE_KEY = 'welsys_library_portal_v2';

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

const CAT_CLASS = {
  'アプリ': 'app',
  'プログラム': 'prg',
  '資料': 'doc',
  'マニュアル': 'man'
};

/* ---------- 状態 ---------- */
let items = [];
const openIds = new Set();              // 開いている行の ID
const state = { q: '', category: '', sort: 'updated_desc' };

/* ---------- ユーティリティ ---------- */
const $ = (id) => document.getElementById(id);

function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

const safeUrl = (url) => (/^https?:\/\//i.test(String(url || '')) ? url : '');
const latest = (item) => (item.history && item.history.length ? item.history[0] : null);
const sortHistory = (item) =>
  item.history.sort((a, b) => `${b.date} ${b.time}`.localeCompare(`${a.date} ${a.time}`));

function fmtDate(d) {
  if (!d) return '—';
  const [y, m, day] = String(d).split('-');
  return `${y}/${m}/${day}`;
}

const kindBadge = (kind) =>
  `<span class="lp-badge lp-kind-${KIND_CLASS[kind] || 'improve'}">${esc(kind)}</span>`;

/* ---------- データ入出力 ----------
   本番（DATA_SOURCE = 'api'）では localStorage ではなく API を呼ぶ。 */
async function fetchItems() {
  if (DATA_SOURCE === 'api') {
    const res = await fetch(`${API_BASE}/items`, { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`GET /items ${res.status}`);
    return res.json();                  // [{ id, name, category, owner, createdAt, downloadUrl, description, history: [...] }]
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    if (Array.isArray(parsed) && parsed.length) return parsed;
  } catch (e) { /* 破損時はサンプルへフォールバック */ }
  return JSON.parse(JSON.stringify(SAMPLE_ITEMS));
}

async function persistUpdate(itemId, entry, newUrl) {
  if (DATA_SOURCE === 'api') {
    const res = await fetch(`${API_BASE}/items/${encodeURIComponent(itemId)}/updates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...entry, downloadUrl: newUrl || undefined })
    });
    if (!res.ok) throw new Error(`POST /updates ${res.status}`);
    return;
  }
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(items)); }
  catch (e) { toast('保存に失敗しました（ブラウザの設定をご確認ください）'); }
}

/* ---------- 絞り込み ---------- */
function historyMatches(h, q) {
  return [h.summary, h.target, h.author, h.kind, h.version, h.ticket, (h.files || []).join(' ')]
    .join(' ').toLowerCase().includes(q);
}

function visibleItems() {
  const q = state.q.trim().toLowerCase();
  const list = items.filter((it) => {
    if (state.category && it.category !== state.category) return false;
    if (!q) return true;
    const base = [it.id, it.name, it.category, it.owner, it.description].join(' ').toLowerCase();
    return base.includes(q) || it.history.some((h) => historyMatches(h, q));
  });

  const key = (it) => { const h = latest(it); return h ? `${h.date} ${h.time}` : ''; };
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

/* ---------- 描画 ---------- */
function rowHtml(it) {
  const h = latest(it);
  const open = openIds.has(it.id);
  const url = safeUrl(it.downloadUrl);

  const timeline = it.history.map((e, i) => `
    <li class="lp-tl-item${i === 0 ? ' is-latest' : ''}">
      <div class="lp-tl-head">
        <span class="lp-tl-date">${fmtDate(e.date)} ${esc(e.time)}</span>
        ${kindBadge(e.kind)}
        ${e.version ? `<span class="lp-ver">${esc(e.version)}</span>` : ''}
        <span class="lp-tl-author">対応者：${esc(e.author)}</span>
        ${e.ticket ? `<span class="lp-ticket">${esc(e.ticket)}</span>` : ''}
      </div>
      <p class="lp-tl-summary">${esc(e.summary)}</p>
      <div class="lp-tl-target">
        <span class="lp-tl-target-label">対象機能</span>
        <span class="lp-target-name">${esc(e.target)}</span>
        ${(e.files && e.files.length) ? `
          <span class="lp-tl-target-label" style="margin-top:7px">修正したプログラム・ファイル</span>
          <ul class="lp-files">${e.files.map((f) => `<li>${esc(f)}</li>`).join('')}</ul>` : ''}
      </div>
    </li>`).join('');

  return `
  <article class="lp-row${open ? ' is-open' : ''}" data-id="${esc(it.id)}">
    <button class="lp-row-head" type="button" data-toggle="${esc(it.id)}"
            aria-expanded="${open}" aria-controls="panel-${esc(it.id)}">
      <span><span class="lp-cat lp-cat-${CAT_CLASS[it.category] || 'prg'}">${esc(it.category)}</span></span>
      <span>
        <span class="lp-row-name">${esc(it.name)}</span>
        <span class="lp-row-id">${esc(it.id)} ／ ${esc(it.owner)}</span>
      </span>
      <span class="lp-row-date">${h ? fmtDate(h.date) : '—'}<span class="lp-row-time">${h ? esc(h.time) : ''}</span></span>
      <span>
        <span class="lp-row-summary">${h ? esc(h.summary) : '更新履歴なし'}</span>
        ${h ? `<span class="lp-row-target">対象機能：${esc(h.target)}</span>` : ''}
      </span>
      <span class="lp-row-author">${h ? esc(h.author) : '—'}</span>
      <span>${h && h.version ? `<span class="lp-ver">${esc(h.version)}</span>` : ''}</span>
      <span class="lp-chev" aria-hidden="true">▼</span>
    </button>

    <div class="lp-panel" id="panel-${esc(it.id)}" role="region">
      <div class="lp-panel-inner">
        <div class="lp-panel-body">
          <div class="lp-meta">
            <span class="lp-meta-item lp-meta-desc">
              <span class="lp-meta-label">説明</span>${esc(it.description)}
            </span>
            <span class="lp-meta-item"><span class="lp-meta-label">作成日</span>${fmtDate(it.createdAt)}</span>
            <span class="lp-meta-item"><span class="lp-meta-label">管理部署</span>${esc(it.owner)}</span>
            <span class="lp-meta-item"><span class="lp-meta-label">更新件数</span>${it.history.length} 件</span>
            <span class="lp-meta-item">
              <span class="lp-meta-label">ダウンロード</span>
              ${url ? `<a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">⬇ ダウンロード</a>
                       <span class="lp-dl-url">${esc(url)}</span>`
                    : 'URL 未設定'}
            </span>
          </div>

          <h3 class="lp-panel-title">更新履歴（${it.history.length} 件）</h3>
          <ol class="lp-timeline">${timeline}</ol>

          <div class="lp-panel-actions">
            <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-add="${esc(it.id)}">＋ このアイテムの更新を登録</button>
          </div>
        </div>
      </div>
    </div>
  </article>`;
}

function render() {
  const list = visibleItems();
  $('list').innerHTML = list.map(rowHtml).join('');
  $('listEmpty').hidden = list.length > 0;

  $('statItems').textContent = items.length;
  $('statHistory').textContent = items.reduce((n, it) => n + it.history.length, 0);
}

function renderChips() {
  const cats = [...new Set(items.map((i) => i.category))].sort((a, b) => a.localeCompare(b, 'ja'));
  $('chipRow').innerHTML =
    [['', 'すべて'], ...cats.map((c) => [c, c])]
      .map(([v, label]) =>
        `<button class="lp-chip${state.category === v ? ' is-on' : ''}" type="button" data-cat="${esc(v)}">${esc(label)}</button>`)
      .join('');
}

function renderItemOptions() {
  $('fItem').innerHTML = items
    .map((it) => `<option value="${esc(it.id)}">${esc(it.id)}：${esc(it.name)}</option>`).join('');
}

/* ---------- アコーディオン ---------- */
function toggleRow(id) {
  const row = document.querySelector(`.lp-row[data-id="${CSS.escape(id)}"]`);
  if (!row) return;
  const open = !openIds.has(id);
  if (open) openIds.add(id); else openIds.delete(id);
  row.classList.toggle('is-open', open);
  row.querySelector('.lp-row-head').setAttribute('aria-expanded', String(open));
}

function setAll(open) {
  openIds.clear();
  if (open) visibleItems().forEach((it) => openIds.add(it.id));
  document.querySelectorAll('.lp-row').forEach((row) => {
    row.classList.toggle('is-open', open);
    row.querySelector('.lp-row-head').setAttribute('aria-expanded', String(open));
  });
}

/* ---------- 更新登録モーダル ---------- */
function openModal(itemId) {
  renderItemOptions();
  if (itemId) $('fItem').value = itemId;
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  $('fDate').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  $('fTime').value = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  $('updateModal').hidden = false;
  $('modalOverlay').hidden = false;
  $('fAuthor').focus();
}

function closeModal() {
  $('updateModal').hidden = true;
  $('modalOverlay').hidden = true;
  $('updateForm').reset();
}

async function submitUpdate(ev) {
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
  const newUrl = $('fUrl').value.trim();

  it.history.push(entry);
  sortHistory(it);
  if (newUrl) it.downloadUrl = newUrl;

  try { await persistUpdate(it.id, entry, newUrl); }
  catch (e) { toast('サーバーへの保存に失敗しました'); }

  openIds.add(it.id);                   // 登録したアイテムは開いた状態で表示
  renderChips();
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
async function init() {
  try { items = await fetchItems(); }
  catch (e) { items = JSON.parse(JSON.stringify(SAMPLE_ITEMS)); toast('データ取得に失敗したためサンプルを表示しています'); }
  items.forEach(sortHistory);
  renderChips();
  render();

  $('searchInput').addEventListener('input', (e) => { state.q = e.target.value; render(); });
  $('sortSelect').addEventListener('change', (e) => { state.sort = e.target.value; render(); });
  $('btnExpandAll').addEventListener('click', () => setAll(true));
  $('btnCollapseAll').addEventListener('click', () => setAll(false));
  $('btnNewUpdate').addEventListener('click', () => openModal());

  $('chipRow').addEventListener('click', (e) => {
    const chip = e.target.closest('[data-cat]');
    if (!chip) return;
    state.category = chip.dataset.cat;
    renderChips();
    render();
  });

  $('list').addEventListener('click', (e) => {
    const add = e.target.closest('[data-add]');
    if (add) { openModal(add.dataset.add); return; }
    const head = e.target.closest('[data-toggle]');
    if (head) toggleRow(head.dataset.toggle);
  });

  $('btnCloseModal').addEventListener('click', closeModal);
  $('btnCancel').addEventListener('click', closeModal);
  $('modalOverlay').addEventListener('click', closeModal);
  $('updateForm').addEventListener('submit', submitUpdate);

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('updateModal').hidden) closeModal();
  });
}

document.addEventListener('DOMContentLoaded', init);

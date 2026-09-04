/* ============================================================
   ライブラリポータル — library.js  v3
   1行 = 1アイテムのアコーディオン一覧

   データ供給元：
     ・index.php（本番）… window.LP が定義され、api/items.php から取得
     ・preview.html（デザイン確認用）… window.LP_SAMPLE のサンプルを表示
   ============================================================ */
'use strict';

const LP_CFG = window.LP || null;              // 本番なら PHP から埋め込まれる
const CAN_EDIT = !!(LP_CFG && LP_CFG.canEdit); // 管理者のみ true
const API = LP_CFG ? LP_CFG.apiBase : null;

const KIND_CLASS = {
  '機能追加': 'feature',
  '不具合修正': 'bugfix',
  '改善': 'improve',
  '資料改訂': 'doc',
  '初版公開': 'initial'
};
const CAT_CLASS = { 'アプリ': 'app', 'プログラム': 'prg', '資料': 'doc', 'マニュアル': 'man' };

/* 絵文字ではなく線画のアイコンを使用（サイズ・太さを他要素と揃えるため） */
const ICON_CHEVRON = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
  stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>`;
const ICON_EXTERNAL = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
  stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round">
  <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
  <path d="M15 3h6v6"/><path d="M10 14 21 3"/></svg>`;
/* ツリーの根（アイテム本体）を示すアイコン */
const ICON_ROOT = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
  stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>`;

/* 種別ごとのアイコン（一覧で種類をひと目で見分けられるように） */
const SVG = (paths) => `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
  stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
const CAT_ICON = {
  'アプリ':     SVG('<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M3 9h18M9 21V9"/>'),
  'プログラム': SVG('<path d="m9 17-5-5 5-5"/><path d="m15 7 5 5-5 5"/>'),
  '資料':       SVG('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>'),
  'マニュアル': SVG('<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H19v3H6.5"/>')
};

/* ---------- 状態 ---------- */
let items = [];
const openIds = new Set();
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

/* ---------- API ---------- */
async function apiGet(path) {
  const res = await fetch(`${API}/${path}`, {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin'
  });
  if (res.status === 401) { location.href = 'login.php'; throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(`GET ${path} ${res.status}`);
  return res.json();
}

async function apiSend(path, method, body) {
  const res = await fetch(`${API}/${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-Token': LP_CFG.csrf,
      Accept: 'application/json'
    },
    credentials: 'same-origin',
    body: JSON.stringify(body)
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { location.href = 'login.php'; throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(data.error || `${method} ${path} ${res.status}`);
  return data;
}

async function loadItems() {
  if (!LP_CFG) {                       // プレビュー（静的）
    items = JSON.parse(JSON.stringify(window.LP_SAMPLE || []));
  } else {
    items = await apiGet('items.php');
  }
  items.forEach(sortHistory);
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
    const base = [it.id, it.name, it.category, it.creator, it.description].join(' ').toLowerCase();
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

/* ---------- 更新履歴のツリー（tree コマンド風） ----------
   ・幹／枝の罫線は CSS で描く（library.css の .lp-tree を参照）
   ・--d には上から数えた表示順を入れ、開いたときに順に描かれるようにする   */
function historyTree(it) {
  let seq = 0;                                   // 表示順（描画アニメーションの順番）
  const step = () => Math.min(seq++, 16);        // 件数が多くても待ち時間が延びすぎないよう上限を設ける

  // 「ファイル名 : 修正内容」を分けて表示する
  const filePath = (f) => {
    const m = String(f).split(/\s+:\s+/);
    return m.length > 1
      ? `<b>${esc(m[0])}</b> : ${esc(m.slice(1).join(' : '))}`
      : `<b>${esc(f)}</b>`;
  };

  const updates = it.history.map((e, i) => {
    const d = step();                            // 更新そのもの（第1階層）
    const attrs = [];

    const dSummary = step();
    attrs.push(`
      <li class="lp-tree-attr"><span class="lp-tree-row" style="--d:${dSummary}">
        <span class="lp-tree-key">更新内容</span>
        <span class="lp-tree-val lp-tree-val-strong">${esc(e.summary)}</span>
      </span></li>`);

    const dTarget = step();
    attrs.push(`
      <li class="lp-tree-attr"><span class="lp-tree-row" style="--d:${dTarget}">
        <span class="lp-tree-key">対象機能</span>
        <span class="lp-tree-val">${esc(e.target)}</span>
      </span></li>`);

    if (e.files && e.files.length) {
      const dFiles = step();
      const files = e.files.map((f) => `
        <li class="lp-tree-file"><span class="lp-tree-row" style="--d:${step()}">
          <span class="lp-tree-path">${filePath(f)}</span>
        </span></li>`).join('');
      attrs.push(`
        <li class="lp-tree-attr"><span class="lp-tree-row" style="--d:${dFiles}">
          <span class="lp-tree-key">修正ファイル</span>
          <span class="lp-tree-val">${e.files.length} 件</span>
        </span>
        <ul>${files}</ul></li>`);
    }

    return `
      <li class="lp-tree-update${i === 0 ? ' is-latest' : ''}">
        <span class="lp-tree-row" style="--d:${d}">
          <span class="lp-tree-date">${fmtDate(e.date)} ${esc(e.time)}</span>
          ${kindBadge(e.kind)}
          ${e.version ? `<span class="lp-ver">${esc(e.version)}</span>` : ''}
          <span class="lp-tree-author">${esc(e.author)}</span>
          ${e.ticket ? `<span class="lp-ticket">${esc(e.ticket)}</span>` : ''}
          ${i === 0 ? '<span class="lp-tree-latest">最新</span>' : ''}
        </span>
        <ul>${attrs.join('')}</ul>
      </li>`;
  }).join('');

  const fileCount = it.history.reduce((n, e) => n + ((e.files && e.files.length) || 0), 0);
  const body = it.history.length
    ? `<ul>${updates}</ul>
       <p class="lp-tree-foot" style="--dmax:${seq}">更新 ${it.history.length} 件、修正ファイル ${fileCount} 件</p>`
    : `<ul><li class="lp-tree-attr"><span class="lp-tree-row" style="--d:0">
         <span class="lp-tree-val lp-muted">更新履歴はまだ登録されていません。</span>
       </span></li></ul>`;

  return `
    <div class="lp-tree">
      <div class="lp-tree-root">
        <span class="lp-tree-root-icon">${ICON_ROOT}</span>
        <span>${esc(it.name)}</span>
        <span class="lp-tree-root-id">${esc(it.id)}</span>
      </div>
      ${body}
    </div>`;
}

/* ---------- 描画 ---------- */
function rowHtml(it) {
  const h = latest(it);
  const open = openIds.has(it.id);
  const url = safeUrl(it.downloadUrl);

  return `
  <article class="lp-row${open ? ' is-open' : ''}" data-id="${esc(it.id)}">
    <div class="lp-row-head" role="button" tabindex="0" data-toggle="${esc(it.id)}"
         aria-expanded="${open}" aria-controls="panel-${esc(it.id)}">
      <span><span class="lp-cat lp-cat-${CAT_CLASS[it.category] || 'prg'}">${CAT_ICON[it.category] || ''}${esc(it.category)}</span></span>
      <span>
        <span class="lp-row-name">${esc(it.name)}</span>
        <span class="lp-row-id">${esc(it.id)} ／ ${esc(it.creator)}</span>
      </span>
      <span class="lp-row-date">${h ? fmtDate(h.date) : '—'}<span class="lp-row-time">${h ? esc(h.time) : ''}</span></span>
      <span>
        <span class="lp-row-summary">${h ? esc(h.summary) : '更新履歴なし'}</span>
        ${h ? `<span class="lp-row-target">対象機能：${esc(h.target)}</span>` : ''}
      </span>
      <span class="lp-row-author">${esc(it.creator)}</span>
      <span class="lp-row-url">
        ${url ? `<a class="lp-url-link" href="${esc(url)}" target="_blank" rel="noopener"
                    aria-label="${esc(it.name)} を開く">${ICON_EXTERNAL}<span>開く</span></a>`
              : '<span class="lp-muted">—</span>'}
      </span>
      <span class="lp-chev" aria-hidden="true">${ICON_CHEVRON}</span>
    </div>

    <div class="lp-panel" id="panel-${esc(it.id)}" role="region">
      <div class="lp-panel-inner">
        <div class="lp-panel-body">
          <div class="lp-meta">
            <span class="lp-meta-item lp-meta-desc">
              <span class="lp-meta-label">説明</span>${esc(it.description) || '—'}
            </span>
            <span class="lp-meta-item"><span class="lp-meta-label">作成日</span>${fmtDate(it.createdAt)}</span>
            <span class="lp-meta-item"><span class="lp-meta-label">作成者</span>${esc(it.creator)}</span>
            <span class="lp-meta-item"><span class="lp-meta-label">更新件数</span>${it.history.length} 件</span>
            <span class="lp-meta-item">
              <span class="lp-meta-label">URL</span>
              ${url ? `<a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">🔗 開く</a>
                       <span class="lp-dl-url">${esc(url)}</span>` : 'URL 未設定'}
            </span>
          </div>

          <h3 class="lp-panel-title">更新履歴（${it.history.length} 件）</h3>
          ${historyTree(it)}

          ${CAN_EDIT ? `<div class="lp-panel-actions">
            <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-add="${esc(it.id)}">＋ このアイテムの更新を登録</button>
          </div>` : ''}
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
  const sel = $('fItem');
  if (!sel) return;
  sel.innerHTML = items
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

/* ---------- モーダル共通 ---------- */
function showModal(el) {
  $('modalOverlay').hidden = false;
  el.hidden = false;
}
function hideModals() {
  $('modalOverlay').hidden = true;
  ['updateModal', 'itemModal', 'pwModal'].forEach((id) => { const el = $(id); if (el) el.hidden = true; });
  ['updateError', 'itemError', 'pwError'].forEach((id) => { const el = $(id); if (el) el.hidden = true; });
}
function formError(id, message) {
  const el = $(id);
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
}

/* ---------- 更新登録 ---------- */
function openUpdateModal(itemId) {
  if (!CAN_EDIT) return;
  renderItemOptions();
  if (itemId) $('fItem').value = itemId;
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  $('fDate').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  $('fTime').value = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  showModal($('updateModal'));
  $('fSummary').focus();
}

async function submitUpdate(ev) {
  ev.preventDefault();
  const itemId = $('fItem').value;
  const payload = {
    itemId,
    date: $('fDate').value,
    time: $('fTime').value,
    author: $('fAuthor').value.trim(),
    kind: $('fKind').value,
    version: $('fVersion').value.trim(),
    summary: $('fSummary').value.trim(),
    target: $('fTarget').value.trim(),
    files: $('fFiles').value.split('\n').map((s) => s.trim()).filter(Boolean),
    ticket: $('fTicket').value.trim(),
    downloadUrl: $('fUrl').value.trim()
  };

  try {
    await apiSend('updates.php', 'POST', payload);
    await loadItems();
    openIds.add(itemId);
    renderChips();
    render();
    hideModals();
    $('updateForm').reset();
    toast('更新履歴を登録しました');
  } catch (e) {
    formError('updateError', e.message || '登録に失敗しました。');
  }
}

/* ---------- アイテム登録 ---------- */
function openItemModal() {
  if (!CAN_EDIT) return;
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  $('iCreated').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  showModal($('itemModal'));
  $('iId').focus();
}

async function submitItem(ev) {
  ev.preventDefault();
  const payload = {
    id: $('iId').value.trim(),
    name: $('iName').value.trim(),
    category: $('iCategory').value,
    creator: $('iCreator').value.trim(),
    createdAt: $('iCreated').value,
    description: $('iDesc').value.trim(),
    downloadUrl: $('iUrl').value.trim()
  };
  try {
    await apiSend('items.php', 'POST', payload);
    await loadItems();
    renderChips();
    render();
    hideModals();
    $('itemForm').reset();
    toast('アイテムを登録しました');
  } catch (e) {
    formError('itemError', e.message || '登録に失敗しました。');
  }
}

/* ---------- パスワード変更 ---------- */
async function submitPassword(ev) {
  ev.preventDefault();
  const next = $('pwNext').value;
  if (next !== $('pwConfirm').value) {
    formError('pwError', '新しいパスワードが一致しません。');
    return;
  }
  try {
    await apiSend('password.php', 'POST', { current: $('pwCurrent').value, next });
    hideModals();
    $('pwForm').reset();
    toast('パスワードを変更しました');
    const notice = document.querySelector('.lp-notice');
    if (notice) notice.remove();
  } catch (e) {
    formError('pwError', e.message || '変更に失敗しました。');
  }
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
  try {
    await loadItems();
  } catch (e) {
    if (String(e.message) !== 'unauthorized') toast('データの取得に失敗しました');
    items = [];
  }
  renderChips();
  render();

  $('searchInput').addEventListener('input', (e) => { state.q = e.target.value; render(); });
  $('sortSelect').addEventListener('change', (e) => { state.sort = e.target.value; render(); });
  $('btnExpandAll').addEventListener('click', () => setAll(true));
  $('btnCollapseAll').addEventListener('click', () => setAll(false));

  $('chipRow').addEventListener('click', (e) => {
    const chip = e.target.closest('[data-cat]');
    if (!chip) return;
    state.category = chip.dataset.cat;
    renderChips();
    render();
  });

  $('list').addEventListener('click', (e) => {
    if (e.target.closest('.lp-url-link')) return;   // URLを直接開く。行の開閉はしない
    const add = e.target.closest('[data-add]');
    if (add) { openUpdateModal(add.dataset.add); return; }
    const head = e.target.closest('[data-toggle]');
    if (head) toggleRow(head.dataset.toggle);
  });

  // 行の見出しは role="button" の div のため、Enter / Space での開閉を自前で処理する
  $('list').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (e.target.closest('.lp-url-link')) return;    // リンク自体のキー操作は既定の動作に任せる
    const head = e.target.closest('[data-toggle]');
    if (!head) return;
    e.preventDefault();
    toggleRow(head.dataset.toggle);
  });

  // 以下は index.php（ログイン後の画面）にのみ存在する要素
  const bind = (id, ev, fn) => { const el = $(id); if (el) el.addEventListener(ev, fn); };
  bind('btnNewUpdate', 'click', () => openUpdateModal());
  bind('btnNewItem', 'click', openItemModal);
  bind('btnCloseModal', 'click', hideModals);
  bind('btnCancel', 'click', hideModals);
  bind('btnCloseItemModal', 'click', hideModals);
  bind('btnItemCancel', 'click', hideModals);
  bind('btnClosePwModal', 'click', hideModals);
  bind('btnPwCancel', 'click', hideModals);
  bind('modalOverlay', 'click', hideModals);
  bind('updateForm', 'submit', submitUpdate);
  bind('itemForm', 'submit', submitItem);
  bind('pwForm', 'submit', submitPassword);
  bind('btnChangePw', 'click', () => { closeUserMenu(); showModal($('pwModal')); $('pwCurrent').focus(); });

  const menuBtn = $('btnUserMenu');
  if (menuBtn) {
    menuBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const menu = $('userMenu');
      const show = menu.hidden;
      menu.hidden = !show;
      menuBtn.setAttribute('aria-expanded', String(show));
    });
    document.addEventListener('click', (e) => {
      if (!e.target.closest('.lp-user')) closeUserMenu();
    });
  }

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    closeUserMenu();
    hideModals();
  });
}

function closeUserMenu() {
  const menu = $('userMenu');
  if (menu && !menu.hidden) {
    menu.hidden = true;
    const btn = $('btnUserMenu');
    if (btn) btn.setAttribute('aria-expanded', 'false');
  }
}

document.addEventListener('DOMContentLoaded', init);

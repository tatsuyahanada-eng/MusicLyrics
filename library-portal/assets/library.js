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

/* 版数の遷移（v1.2.0 → v1.4.0）に使う矢印 */
const ICON_ARROW = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
  stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h13"/><path d="m12 5 7 7-7 7"/></svg>`;

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
  const files = normFiles(h).map((f) => `${f.path} ${f.note}`).join(' ');
  return [h.summary, h.target, h.author, h.kind, h.version, h.ticket, files]
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

/* ---------- 更新履歴 ----------
   「何を、どのファイルで直して、いまの版に至ったのか」を軸に組み立てる。
   ・幹（縦線）と枝は CSS の罫線で描く（library.css の .lp-hist / .lp-files）
   ・--d には上から数えた表示順を入れ、開いたときに順に描かれるようにする   */

/** files は旧形式（"パス : 内容" の文字列）と新形式（{path, note}）の両方を受ける */
function normFiles(h) {
  return (h.files || []).map((f) => {
    if (f && typeof f === 'object') return { path: String(f.path || ''), note: String(f.note || '') };
    const parts = String(f).split(/\s+:\s+/);
    return { path: parts[0], note: parts.slice(1).join(' : ') };
  }).filter((f) => f.path);
}

/** 同じファイルを何度直したか（古い順に 1, 2, 3 …）を数えておく */
function fileRounds(it) {
  const seen = new Map();
  const rounds = new Map();                        // 「古い順の位置 + パス」→ 何回目か
  [...it.history].reverse().forEach((h, oldIdx) => {
    normFiles(h).forEach((f) => {
      const n = (seen.get(f.path) || 0) + 1;
      seen.set(f.path, n);
      rounds.set(oldIdx + ' ' + f.path, n);
    });
  });
  return { rounds, total: seen };
}

/** 版数の道のり（出発点 → … → いま）。版数が一つも無いときは出さない */
function versionRoad(it) {
  const chain = [...it.history].reverse()
    .filter((h) => h.version)
    .filter((h, i, arr) => i === 0 || h.version !== arr[i - 1].version);
  if (chain.length < 2) return '';                 // 版が1つだけなら「道のり」にならないので出さない

  const steps = chain.map((h, i) => {
    const isNow = i === chain.length - 1;
    return `
      <li class="lp-road-step${isNow ? ' is-now' : ''}" style="--d:${i}">
        <span class="lp-road-ver">${esc(h.version)}</span>
        <span class="lp-road-when">${fmtDate(h.date)}</span>
        <span class="lp-road-kind">${isNow ? 'いま' : esc(h.kind)}</span>
      </li>`;
  }).join('');

  return `
    <div class="lp-road">
      <span class="lp-road-label">版数の道のり</span>
      <ol class="lp-road-track">${steps}</ol>
    </div>`;
}

/** 1件の更新で直したファイルの一覧表 */
function fileTable(files, oldIdx, rounds, total) {
  if (!files.length) return '<p class="lp-files-none">ファイル単位の記録はありません。</p>';

  const rows = files.map((f) => {
    const n = rounds.get(oldIdx + ' ' + f.path) || 1;
    const many = (total.get(f.path) || 1) > 1;
    return `
      <tr>
        <td class="lp-files-path">
          <code>${esc(f.path)}</code>
          ${many ? `<span class="lp-files-round" title="このファイルを直した回数">${n} 回目</span>` : ''}
        </td>
        <td class="lp-files-note">${f.note ? esc(f.note) : '<span class="lp-muted">—</span>'}</td>
      </tr>`;
  }).join('');

  return `
    <table class="lp-files">
      <thead><tr><th>直したファイル</th><th>直した内容</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
}

/** 更新の記録（新しい順に、幹でつないだ一本の流れとして並べる） */
function historyList(it) {
  if (!it.history.length) {
    return '<p class="lp-hist-empty">更新履歴はまだ登録されていません。</p>';
  }

  const { rounds, total } = fileRounds(it);
  const n = it.history.length;

  const rows = it.history.map((e, i) => {
    const oldIdx = n - 1 - i;                      // 古い順に数えたときの位置
    const prev = it.history[i + 1] || null;        // ひとつ前（より古い）の更新
    const files = normFiles(e);
    const d = Math.min(i, 12);                     // 件数が多くても待ち時間が延びないよう上限

    // 同じ版のままの修正では「→」を出さない（v2.3.0 → v2.3.0 と見えてしまうため）
    const from = prev && prev.version !== e.version ? prev.version : '';
    const jump = e.version
      ? `<span class="lp-jump">${from
            ? `<span class="lp-jump-from">${esc(from)}</span><span class="lp-jump-arrow" aria-hidden="true">${ICON_ARROW}</span>`
            : ''}<span class="lp-jump-to">${esc(e.version)}</span></span>`
      : '';

    const tags =
      (i === 0 ? '<span class="lp-hist-tag lp-hist-tag-now">いまの姿</span>' : '') +
      (prev ? '' : '<span class="lp-hist-tag lp-hist-tag-start">出発点</span>');

    return `
      <li class="lp-hist-item${i === 0 ? ' is-latest' : ''}${prev ? '' : ' is-first'}" style="--d:${d}">
        <span class="lp-hist-no" aria-hidden="true">${oldIdx + 1}</span>
        <div class="lp-hist-card">
          <div class="lp-hist-head">
            <span class="lp-hist-when">${fmtDate(e.date)}<span class="lp-hist-time">${esc(e.time)}</span></span>
            ${kindBadge(e.kind)}
            ${jump}
            ${tags ? `<span class="lp-hist-tags">${tags}</span>` : ''}
          </div>

          <p class="lp-hist-what">${esc(e.summary)}</p>

          <div class="lp-hist-meta">
            <span class="lp-hist-chip"><span>対象機能</span>${esc(e.target)}</span>
            <span class="lp-hist-chip"><span>対応者</span>${esc(e.author)}</span>
            ${e.ticket ? `<span class="lp-hist-chip"><span>管理番号</span>${esc(e.ticket)}</span>` : ''}
          </div>

          <div class="lp-hist-files">
            <span class="lp-hist-files-cap">実際に直したプログラム・ファイル（${files.length} 件）</span>
            ${fileTable(files, oldIdx, rounds, total)}
          </div>
        </div>
      </li>`;
  }).join('');

  return `<ol class="lp-hist">${rows}</ol>`;
}

/** パネル冒頭：このアイテムが「いま」どうなっているか */
function nowCard(it) {
  const h = latest(it);
  const url = safeUrl(it.downloadUrl);
  const fileCount = it.history.reduce((sum, e) => sum + normFiles(e).length, 0);
  const touched = new Set();
  it.history.forEach((e) => normFiles(e).forEach((f) => touched.add(f.path)));

  return `
    <div class="lp-now">
      <div class="lp-now-head">
        <span class="lp-now-label">いまの版</span>
        <span class="lp-now-ver">${h && h.version ? esc(h.version) : '版数なし'}</span>
        <span class="lp-now-when">${h
          ? `${fmtDate(h.date)} ${esc(h.time)} の更新まで反映`
          : 'まだ更新は登録されていません'}</span>
        ${url ? `<a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">${ICON_EXTERNAL}<span>開く</span></a>` : ''}
      </div>

      <div class="lp-now-body">
        ${it.description ? `<p class="lp-now-desc">${esc(it.description)}</p>` : ''}

        <dl class="lp-now-facts">
          <div><dt>公開開始</dt><dd>${fmtDate(it.createdAt)}</dd></div>
          <div><dt>これまでの更新</dt><dd>${it.history.length} 回</dd></div>
          <div><dt>直したファイル</dt><dd>延べ ${fileCount} 件 ／ ${touched.size} 種類</dd></div>
          <div><dt>作成者</dt><dd>${esc(it.creator)}</dd></div>
          <div><dt>最終対応者</dt><dd>${h ? esc(h.author) : '—'}</dd></div>
        </dl>

        ${url ? `<p class="lp-dl-url">${esc(url)}</p>` : ''}
      </div>
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
      <span class="lp-row-date">${h ? fmtDate(h.date) : '—'}<span class="lp-row-time">${h ? esc(h.time) : ''}</span>
        ${h && h.version ? `<span class="lp-row-ver">${esc(h.version)}</span>` : ''}</span>
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
          ${nowCard(it)}
          ${versionRoad(it)}

          <h3 class="lp-panel-title">
            ここに至るまでの更新
            <span class="lp-panel-title-sub">新しい順／${it.history.length} 件</span>
          </h3>
          ${historyList(it)}

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

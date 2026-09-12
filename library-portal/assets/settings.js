/* ============================================================
   ライブラリポータル — settings.js
   設定画面（利用者管理）。管理者のみ到達できます。
   ============================================================ */
'use strict';

const API = window.LP.apiBase;
const ME = window.LP.user.id;

let users = [];
let categories = [];
let meta = { authMode: 'local', canManageAccounts: true, defaultRole: 'viewer', appKey: 'library' };
const canAcct = () => meta.canManageAccounts;

const $ = (id) => document.getElementById(id);
function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* 本棚（assets/library.js）と同じキー・同じ色を使う。増やすときは両方に足すこと */
const PALETTE = {
  navy:     { book: '#1c364a', bg: '#e6eef4', border: '#c3d4e2', fg: '#1c364a' },
  graphite: { book: '#33363c', bg: '#eef1ef', border: '#d9e0db', fg: '#33363c' },
  tan:      { book: '#947a57', bg: '#f7efdf', border: '#e3d1a8', fg: '#6b5326' },
  maroon:   { book: '#7a5b5f', bg: '#e4f1f4', border: '#bcdbe1', fg: '#0b4a56' },
  forest:   { book: '#2f4a3a', bg: '#e7f0ea', border: '#bcd6c6', fg: '#234534' },
  plum:     { book: '#5c4a6e', bg: '#efe9f3', border: '#d3c2de', fg: '#4a3a5c' },
  rust:     { book: '#8a4a3a', bg: '#f7e9e5', border: '#e3bfb2', fg: '#6b3324' },
  denim:    { book: '#3d5a73', bg: '#e8eef2', border: '#bcd0dd', fg: '#2c4356' },
  charcoal: { book: '#4a4a4a', bg: '#eeeeee', border: '#d4d4d4', fg: '#3a3a3a' }
};

/* カラーバーで選んだ色相から配色一式を作る（assets/library.js の同名関数と揃えてある） */
function hexToHue(hex) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(hex || ''));
  if (!m) return 0;
  const r = parseInt(m[1], 16) / 255, g = parseInt(m[2], 16) / 255, b = parseInt(m[3], 16) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (d === 0) return 0;
  let h;
  if (max === r) h = ((g - b) / d) % 6;
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  h *= 60;
  return h < 0 ? h + 360 : h;
}
function hslToHex(h, s, l) {
  s /= 100; l /= 100;
  const k = (n) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  const toHex = (n) => Math.round(f(n) * 255).toString(16).padStart(2, '0');
  return `#${toHex(0)}${toHex(8)}${toHex(4)}`;
}
function paletteFromHue(hue) {
  return {
    book: hslToHex(hue, 38, 30),
    bg: hslToHex(hue, 45, 93),
    border: hslToHex(hue, 32, 80),
    fg: hslToHex(hue, 55, 26)
  };
}
const isHexColor = (v) => /^#[0-9a-f]{6}$/i.test(String(v || ''));
function resolvePalette(colorValue) {
  if (PALETTE[colorValue]) return PALETTE[colorValue];
  if (isHexColor(colorValue)) return paletteFromHue(hexToHue(colorValue));
  return PALETTE.graphite;
}

const SVG = (paths) => `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
  stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
const ICONS = {
  app:    SVG('<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M3 9h18M9 21V9"/>'),
  code:   SVG('<path d="m9 17-5-5 5-5"/><path d="m15 7 5 5-5 5"/>'),
  doc:    SVG('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>'),
  book:   SVG('<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H19v3H6.5"/>'),
  folder: SVG('<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>'),
  tag:    SVG('<path d="M12 2H4a2 2 0 0 0-2 2v8l10 10 10-10L12 2z"/><circle cx="7.5" cy="7.5" r="1.5"/>'),
  star:   SVG('<path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/>'),
  flag:   SVG('<path d="M4 22V4"/><path d="M4 4h14l-2 4 2 4H4"/>')
};

function fmtDateTime(v) {
  if (!v) return '—';
  const s = String(v).replace(' ', 'T');
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return String(v);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ---------- API ---------- */
async function api(path, method = 'GET', body = null) {
  const opt = {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'X-CSRF-Token': window.LP.csrf }
  };
  if (body) {
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  const res = await fetch(`${API}/${path}`, opt);
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { location.href = 'login.php'; throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(data.error || `${method} ${path} ${res.status}`);
  return data;
}

async function loadUsers() {
  const data = await api('users.php');
  users = data.users || [];
  meta = {
    authMode: data.authMode || 'local',
    canManageAccounts: data.canManageAccounts !== false,
    defaultRole: data.defaultRole || null,
    appKey: data.appKey || 'library'
  };
}

async function loadCategories() {
  categories = await api('categories.php');
}

/* ---------- 描画 ---------- */
function userRow(u) {
  const isSelf = u.userId === ME;
  const admin = u.role === 'admin';
  const none = !u.role;                       // このアプリでの権限なし（共通DB運用時のみ発生）
  const current = none ? 'none' : (u.role || 'viewer');
  const central = meta.authMode === 'central';

  // 権限スイッチ：共通DB運用では「権限なし」も選べる
  const roleBtn = (value, label) => `
    <button type="button" class="lp-roleswitch-btn${value === current ? ' is-on' : ''}"
            data-role="${value}" data-id="${u.userId}" ${isSelf ? 'disabled' : ''}>${label}</button>`;

  const roleSwitch = `
    <span class="lp-roleswitch${admin ? ' is-admin' : ''}${current === 'editor' ? ' is-editor' : ''}${none ? ' is-none' : ''}" role="group" aria-label="権限の切り替え">
      ${roleBtn('admin', '管理者')}${roleBtn('editor', '編集者')}${roleBtn('viewer', '閲覧のみ')}${central ? roleBtn('none', '権限なし') : ''}
    </span>`;

  const actions = canAcct()
    ? `<button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-edit="${u.userId}">編集</button>
       <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-active="${u.userId}"
               ${isSelf ? 'disabled' : ''}>${u.isActive ? '停止' : '再開'}</button>
       <button class="lp-btn lp-btn-danger lp-btn-sm" type="button" data-del="${u.userId}"
               ${isSelf ? 'disabled' : ''}>削除</button>`
    : '<span class="lp-row-id">アカウント設定は共通の利用者管理から</span>';

  return `
  <article class="lp-row lp-row-user${u.isActive ? '' : ' is-inactive'}" data-user="${u.userId}">
    <div class="lp-row-head lp-row-head-user">
      <span>
        <span class="lp-row-name">${esc(u.name)}${isSelf ? '<span class="lp-self">自分</span>' : ''}</span>
        <span class="lp-row-id">${esc(u.email) || 'メール未登録'}</span>
      </span>
      <span class="lp-mono">${esc(u.loginId)}</span>
      <span class="lp-row-author">${esc(u.dept) || '—'}</span>
      <span>${roleSwitch}</span>
      <span>
        <span class="lp-status ${u.isActive ? 'is-active' : 'is-stopped'}">${u.isActive ? '有効' : '停止中'}</span>
        ${u.mustChangePw ? '<span class="lp-row-id">初期PW未変更</span>' : ''}
      </span>
      <span class="lp-row-date">${fmtDateTime(u.lastLoginAt)}</span>
      <span class="lp-row-actions">${actions}</span>
    </div>
  </article>`;
}

function render() {
  $('userList').innerHTML = users.map(userRow).join('');
  $('userEmpty').hidden = users.length > 0;
  renderCategories();
}

/* ---------- カテゴリ（種別） ---------- */
function categoryRow(c) {
  const p = resolvePalette(c.color);
  const inUse = (c.itemCount || 0) > 0;
  return `
  <article class="lp-catrow" data-code="${esc(c.code)}">
    <span class="lp-catrow-swatch" style="background:${p.book}">${ICONS[c.icon] || ICONS.folder}</span>
    <span class="lp-catrow-body">
      <span class="lp-catrow-label">${esc(c.label)}</span>
      <span class="lp-catrow-code">接頭辞：${esc(c.code)}　／　${c.itemCount || 0} 件で使用中</span>
    </span>
    <span class="lp-catrow-actions">
      <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-edit-cat="${esc(c.code)}">編集</button>
      <button class="lp-btn lp-btn-danger lp-btn-sm" type="button" data-del-cat="${esc(c.code)}"
              ${inUse ? 'disabled title="使用中のため削除できません"' : ''}>削除</button>
    </span>
  </article>`;
}

function renderCategories() {
  const list = $('categoryList');
  if (!list) return;
  list.innerHTML = categories.map(categoryRow).join('');
  $('categoryEmpty').hidden = categories.length > 0;
}

function renderColorRow(selected) {
  const row = $('cColorRow');
  const isPreset = !!PALETTE[selected];
  row.innerHTML = Object.keys(PALETTE).map((key) => `
    <button type="button" class="lp-swatch${key === selected ? ' is-on' : ''}"
            data-color="${key}" style="background:${PALETTE[key].book}"
            title="${key}" aria-label="${key}"></button>`).join('');

  // プリセットに一致すればその色相を、カスタム色ならその色の色相をバーの初期位置にする
  const hue = Math.round(hexToHue(isPreset ? PALETTE[selected].book : (isHexColor(selected) ? selected : PALETTE.graphite.book)));
  $('cHue').value = hue;
  updateHuePreview(hue);
}

function updateHuePreview(hue) {
  $('cHuePreview').style.background = paletteFromHue(hue).book;
}

/** 選ばれている配色（プリセットのキー、または色相バーで選んだ #rrggbb）を返す */
function selectedCategoryColor() {
  const onSwatch = $('cColorRow').querySelector('.lp-swatch.is-on');
  if (onSwatch) return onSwatch.dataset.color;
  return paletteFromHue(Number($('cHue').value)).book;
}

function renderIconRow(selected) {
  const row = $('cIconRow');
  row.innerHTML = Object.keys(ICONS).map((key) => `
    <button type="button" class="lp-iconbtn${key === selected ? ' is-on' : ''}"
            data-icon="${key}" title="${key}" aria-label="${key}">${ICONS[key]}</button>`).join('');
}

/* ---------- モーダル ---------- */
function openUserModal(user) {
  $('uUserId').value = user ? user.userId : '';
  $('uLoginId').value = user ? user.loginId : '';
  $('uLoginId').readOnly = !!user;
  $('uName').value = user ? user.name : '';
  $('uDept').value = user ? user.dept : '';
  $('uEmail').value = user ? user.email : '';
  $('uPassword').value = '';
  document.querySelectorAll('input[name="uRole"]').forEach((r) => {
    r.checked = r.value === (user ? user.role : 'viewer');
    r.disabled = !!user && user.userId === ME;
  });

  $('userModalTitle').textContent = user ? '利用者の編集' : '利用者の追加';
  $('pwLabel').innerHTML = user ? 'パスワードの再設定' : '初期パスワード <em>必須</em>';
  $('pwNote').textContent = user
    ? '入力した場合のみ変更します。変更後は本人に再設定を促します。'
    : '8文字以上・英字と数字を含めてください。初回ログイン時に本人による変更を促します。';
  $('uPassword').required = !user;
  $('userError').hidden = true;

  $('modalOverlay').hidden = false;
  $('userModal').hidden = false;
  (user ? $('uName') : $('uLoginId')).focus();
}

function closeModal() {
  $('modalOverlay').hidden = true;
  $('userModal').hidden = true;
  $('userForm').reset();
  const catModal = $('categoryModal');
  if (catModal) { catModal.hidden = true; $('categoryForm').reset(); }
}

function showError(msg) {
  $('userError').textContent = msg;
  $('userError').hidden = false;
}

/* ---------- カテゴリのモーダル ---------- */
function openCategoryModal(cat) {
  $('cCode').value = cat ? cat.code : '';
  $('cLabel').value = cat ? cat.label : '';
  $('cCodeInput').value = cat ? cat.code : '';
  $('cCodeInput').disabled = !!cat;           // 接頭辞はあとから変更しない
  $('cCodeField').hidden = !!cat;
  renderColorRow(cat ? cat.color : 'graphite');
  renderIconRow(cat ? cat.icon : 'folder');

  $('categoryModalTitle').textContent = cat ? 'カテゴリの編集' : 'カテゴリの追加';
  $('categoryError').hidden = true;

  $('modalOverlay').hidden = false;
  $('categoryModal').hidden = false;
  (cat ? $('cLabel') : $('cLabel')).focus();
}

function showCategoryError(msg) {
  $('categoryError').textContent = msg;
  $('categoryError').hidden = false;
}

async function submitCategory(ev) {
  ev.preventDefault();
  const code = $('cCode').value;
  const icon = $('cIconRow').querySelector('.lp-iconbtn.is-on');
  const payload = {
    label: $('cLabel').value.trim(),
    color: selectedCategoryColor(),
    icon: icon ? icon.dataset.icon : 'folder'
  };
  if (!code) payload.code = $('cCodeInput').value.trim().toUpperCase();

  try {
    if (code) {
      await api(`categories.php?code=${encodeURIComponent(code)}`, 'PUT', payload);
    } else {
      await api('categories.php', 'POST', payload);
    }
    await loadCategories();
    render();
    closeModal();
    toast(code ? 'カテゴリを更新しました' : 'カテゴリを登録しました');
  } catch (e) {
    showCategoryError(e.message || '保存に失敗しました。');
  }
}

async function removeCategory(code) {
  const cat = categories.find((c) => c.code === code);
  if (!cat) return;
  if (!confirm(`「${cat.label}」を削除します。よろしいですか？`)) return;
  try {
    await api(`categories.php?code=${encodeURIComponent(code)}`, 'DELETE');
    await loadCategories();
    render();
    toast('カテゴリを削除しました');
  } catch (e) {
    toast(e.message || '削除に失敗しました');
  }
}

async function submitUser(ev) {
  ev.preventDefault();
  const id = $('uUserId').value;
  const role = document.querySelector('input[name="uRole"]:checked');
  const payload = {
    loginId: $('uLoginId').value.trim(),
    name: $('uName').value.trim(),
    dept: $('uDept').value.trim(),
    email: $('uEmail').value.trim(),
    role: role ? role.value : 'viewer'
  };
  const pw = $('uPassword').value;
  if (pw) payload.password = pw;

  try {
    if (id) {
      delete payload.loginId;                       // ログインIDは変更しない
      if (Number(id) === ME) delete payload.role;   // 自分の権限は変更不可
      await api(`users.php?id=${encodeURIComponent(id)}`, 'PATCH', payload);
    } else {
      await api('users.php', 'POST', payload);
    }
    await loadUsers();
    render();
    closeModal();
    toast(id ? '利用者情報を更新しました' : '利用者を登録しました');
  } catch (e) {
    showError(e.message || '保存に失敗しました。');
  }
}

/* ---------- 行内の操作 ---------- */
async function changeRole(id, role) {
  const user = users.find((u) => u.userId === Number(id));
  if (!user) return;
  const nextRole = role === 'none' ? '' : role;
  if ((user.role || '') === nextRole) return;

  const label = { admin: '管理者', editor: '編集者', viewer: '閲覧のみ', none: '権限なし' }[role];
  if (role === 'none' && !confirm(`${user.name} さんからこのアプリの利用権限を外します。よろしいですか？`)) return;

  try {
    await api(`users.php?id=${id}`, 'PATCH', { role: nextRole });
    await loadUsers();
    render();
    toast(`${user.name} さんの権限を「${label}」に変更しました`);
  } catch (e) {
    toast(e.message || '権限の変更に失敗しました');
  }
}

async function toggleActive(id) {
  const user = users.find((u) => u.userId === Number(id));
  if (!user) return;
  const next = !user.isActive;
  if (!next && !confirm(`${user.name} さんのログインを停止します。よろしいですか？`)) return;
  try {
    await api(`users.php?id=${id}`, 'PATCH', { isActive: next });
    await loadUsers();
    render();
    toast(next ? '利用を再開しました' : '利用を停止しました');
  } catch (e) {
    toast(e.message || '状態の変更に失敗しました');
  }
}

async function removeUser(id) {
  const user = users.find((u) => u.userId === Number(id));
  if (!user) return;
  if (!confirm(`${user.name}（${user.loginId}）を削除します。元に戻せません。よろしいですか？`)) return;
  try {
    await api(`users.php?id=${id}`, 'DELETE');
    await loadUsers();
    render();
    toast('利用者を削除しました');
  } catch (e) {
    toast(e.message || '削除に失敗しました');
  }
}

/* ---------- トースト ---------- */
let toastTimer = null;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3000);
}

/* ---------- タブ切り替え ---------- */
function switchTab(name) {
  document.querySelectorAll('.lp-tab').forEach((t) => t.classList.toggle('is-active', t.dataset.tab === name));
  $('panelUsers').hidden = name !== 'users';
  $('panelCategories').hidden = name !== 'categories';
  const btnNewUser = $('btnNewUser');
  if (btnNewUser) btnNewUser.hidden = name !== 'users';
  $('btnNewCategory').hidden = name !== 'categories';
}

/* ---------- 初期化 ---------- */
async function init() {
  try {
    await loadUsers();
  } catch (e) {
    if (String(e.message) !== 'unauthorized') toast('利用者一覧の取得に失敗しました');
  }
  try {
    await loadCategories();
  } catch (e) {
    if (String(e.message) !== 'unauthorized') toast('カテゴリ一覧の取得に失敗しました');
  }
  render();

  const btnNew = $('btnNewUser');
  if (btnNew) btnNew.addEventListener('click', () => openUserModal(null));
  $('btnCloseUserModal').addEventListener('click', closeModal);
  $('btnUserCancel').addEventListener('click', closeModal);
  $('modalOverlay').addEventListener('click', closeModal);
  $('userForm').addEventListener('submit', submitUser);

  $('userList').addEventListener('click', (e) => {
    const role = e.target.closest('[data-role]');
    if (role) { changeRole(role.dataset.id, role.dataset.role); return; }
    const edit = e.target.closest('[data-edit]');
    if (edit) { openUserModal(users.find((u) => u.userId === Number(edit.dataset.edit))); return; }
    const act = e.target.closest('[data-active]');
    if (act) { toggleActive(act.dataset.active); return; }
    const del = e.target.closest('[data-del]');
    if (del) removeUser(del.dataset.del);
  });

  document.querySelectorAll('.lp-tabs .lp-tab').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  $('btnNewCategory').addEventListener('click', () => openCategoryModal(null));
  $('btnCloseCategoryModal').addEventListener('click', closeModal);
  $('btnCategoryCancel').addEventListener('click', closeModal);
  $('categoryForm').addEventListener('submit', submitCategory);
  $('cColorRow').addEventListener('click', (e) => {
    const btn = e.target.closest('.lp-swatch');
    if (!btn) return;
    $('cColorRow').querySelectorAll('.lp-swatch').forEach((b) => b.classList.toggle('is-on', b === btn));
    const hue = Math.round(hexToHue(PALETTE[btn.dataset.color].book));
    $('cHue').value = hue;
    updateHuePreview(hue);
  });
  // 色相バーを動かしたら、プリセットの選択は解除してカスタム色を使う
  $('cHue').addEventListener('input', () => {
    $('cColorRow').querySelectorAll('.lp-swatch').forEach((b) => b.classList.remove('is-on'));
    updateHuePreview(Number($('cHue').value));
  });
  $('cIconRow').addEventListener('click', (e) => {
    const btn = e.target.closest('.lp-iconbtn');
    if (!btn) return;
    $('cIconRow').querySelectorAll('.lp-iconbtn').forEach((b) => b.classList.toggle('is-on', b === btn));
  });
  $('categoryList').addEventListener('click', (e) => {
    const edit = e.target.closest('[data-edit-cat]');
    if (edit) { openCategoryModal(categories.find((c) => c.code === edit.dataset.editCat)); return; }
    const del = e.target.closest('[data-del-cat]');
    if (del && !del.disabled) removeCategory(del.dataset.delCat);
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('userModal').hidden || !$('categoryModal').hidden) closeModal();
  });
}

document.addEventListener('DOMContentLoaded', init);

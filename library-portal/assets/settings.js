/* ============================================================
   ライブラリポータル — settings.js
   設定画面（利用者管理）。管理者のみ到達できます。
   ============================================================ */
'use strict';

const API = window.LP.apiBase;
const ME = window.LP.user.id;

let users = [];
let meta = { authMode: 'local', canManageAccounts: true, defaultRole: 'viewer', appKey: 'library' };
const canAcct = () => meta.canManageAccounts;

const $ = (id) => document.getElementById(id);
function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

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

/* ---------- 描画 ---------- */
function userRow(u) {
  const isSelf = u.userId === ME;
  const admin = u.role === 'admin';
  const none = !u.role;                       // このアプリでの権限なし（共通DB運用時のみ発生）
  const central = meta.authMode === 'central';

  // 権限スイッチ：共通DB運用では「権限なし」も選べる
  const roleBtn = (value, label) => `
    <button type="button" class="lp-roleswitch-btn${(value === 'admin' ? admin : value === 'viewer' ? (!admin && !none) : none) ? ' is-on' : ''}"
            data-role="${value}" data-id="${u.userId}" ${isSelf ? 'disabled' : ''}>${label}</button>`;

  const roleSwitch = `
    <span class="lp-roleswitch${admin ? ' is-admin' : ''}${none ? ' is-none' : ''}" role="group" aria-label="権限の切り替え">
      ${roleBtn('admin', '管理者')}${roleBtn('viewer', '閲覧のみ')}${central ? roleBtn('none', '権限なし') : ''}
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
}

function showError(msg) {
  $('userError').textContent = msg;
  $('userError').hidden = false;
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

  const label = { admin: '管理者', viewer: '閲覧のみ', none: '権限なし' }[role];
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

/* ---------- 初期化 ---------- */
async function init() {
  try {
    await loadUsers();
  } catch (e) {
    if (String(e.message) !== 'unauthorized') toast('利用者一覧の取得に失敗しました');
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

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('userModal').hidden) closeModal();
  });
}

document.addEventListener('DOMContentLoaded', init);

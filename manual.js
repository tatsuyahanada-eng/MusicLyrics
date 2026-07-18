/* ============================================================
   TREE MANUAL — manual.js  v1
   ツリー型 作業マニュアル（チャットボット風ナビゲーション）
   データは localStorage に保存。JSON でエクスポート／インポート可能。
   ============================================================ */
(() => {
  'use strict';

  const STORAGE_KEY = 'treeManual.data.v1';
  const OPEN_KEY = 'treeManual.openNodes.v1';

  /* ---------- utils ---------- */
  const $ = (sel, root = document) => root.querySelector(sel);
  const uid = () =>
    'n' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  const esc = (s) =>
    String(s).replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /* ============================================================
     DATA
     node: { id, title, body, children: [] }
     ============================================================ */
  function seedData() {
    const n = (title, body, children = []) => ({ id: uid(), title, body, children });
    return [
      n('ドミネーターが起動しない', '', [
        n('ウォレットが接続できない', '', [
          n('「CONNECT WALLET」を押しても反応しない',
            '# 対処手順\n- ブラウザを最新版に更新してください。\n- 拡張機能（ウォレット）が有効か確認してください。\n- 別のブラウザ／シークレットウィンドウで再試行してください。\n\nそれでも解決しない場合はサポートへ連絡してください。'),
          n('接続後にエラーが表示される',
            '# 対処手順\n- 対応ネットワークに切り替わっているか確認してください。\n- ウォレットを一度切断し、再接続してください。\n- キャッシュをクリアして再読み込みしてください。'),
        ]),
        n('バッテリー残量が0になっている',
          '# バッテリー切れの対処\n- バッテリーNFTを補充してください。\n- 補充後、ページを再読み込みすると残量が反映されます。'),
      ]),
      n('色相診断ゲームの進め方', '', [
        n('測定モードを開始したい',
          '# 測定モードの開始\n- TOP から「色相診断ゲーム」を選択します。\n- ドミネーターを選択します。\n- 質問に回答すると診断が進みます（全5会話）。'),
        n('診断結果の見方を知りたい',
          '# 結果の見方\n- CRIME COEFFICIENT：犯罪係数。数値が低いほど安全域です。\n- COLOR：色相。あなたの回答傾向を色で表します。\n- HUE POINTS：色相ポイント。'),
      ]),
      n('NFT・ガチャについて', '', [
        n('ガチャのセット内容を知りたい',
          '# セット内容\n## セットA\n- パラライザー ×350 ＋ バッテリーNFT\n- エリミネーター ×120 ＋ バッテリーNFT\n- デコンポーザー ×30 ＋ バッテリーNFT\n\n## セットB\n- 各ドミネーター ＋ バッテリーNFT ＋ マスターNFT（MR）'),
      ]),
    ];
  }

  let tree = load();
  let openNodes = loadOpen();

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const data = JSON.parse(raw);
        if (Array.isArray(data)) return data;
      }
    } catch (e) { /* fall through */ }
    const seeded = seedData();
    persist(seeded);
    return seeded;
  }
  function persist(data = tree) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(data)); return true; }
    catch (e) { return false; }
  }
  function loadOpen() {
    try { return new Set(JSON.parse(localStorage.getItem(OPEN_KEY) || '[]')); }
    catch (e) { return new Set(); }
  }
  function persistOpen() {
    try { localStorage.setItem(OPEN_KEY, JSON.stringify([...openNodes])); } catch (e) {}
  }

  /* ---------- tree helpers ---------- */
  function findPath(id, nodes = tree, acc = []) {
    for (const node of nodes) {
      const next = [...acc, node];
      if (node.id === id) return next;
      const deeper = findPath(id, node.children, next);
      if (deeper) return deeper;
    }
    return null;
  }
  function findNode(id) {
    const p = findPath(id);
    return p ? p[p.length - 1] : null;
  }
  // returns { parentArray, index }
  function locate(id, nodes = tree, parent = tree) {
    for (let i = 0; i < nodes.length; i++) {
      if (nodes[i].id === id) return { arr: nodes, index: i };
      const deeper = locate(id, nodes[i].children, nodes[i].children);
      if (deeper) return deeper;
    }
    return null;
  }

  /* ============================================================
     BODY rendering (very small markdown-ish)
     ============================================================ */
  function renderBody(text) {
    const lines = String(text).replace(/\r\n/g, '\n').split('\n');
    let html = '';
    let inList = false;
    const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
    const inline = (s) =>
      esc(s).replace(/(https?:\/\/[^\s]+)/g, '<a href="$1" target="_blank" rel="noopener">$1</a>');

    for (const raw of lines) {
      const line = raw.trimEnd();
      if (/^#{1,3}\s+/.test(line)) {
        closeList();
        html += `<h3>${inline(line.replace(/^#{1,3}\s+/, ''))}</h3>`;
      } else if (/^[-*]\s+/.test(line)) {
        if (!inList) { html += '<ul>'; inList = true; }
        html += `<li>${inline(line.replace(/^[-*]\s+/, ''))}</li>`;
      } else if (line.trim() === '') {
        closeList();
      } else {
        closeList();
        html += `<p>${inline(line)}</p>`;
      }
    }
    closeList();
    return html;
  }

  /* ============================================================
     NAVIGATION (chat) MODE
     ============================================================ */
  const chatLog = $('#chatLog');
  const choiceDock = $('#choiceDock');
  const breadcrumbBar = $('#breadcrumbBar');
  const backBtn = $('#backBtn');
  const restartBtn = $('#restartBtn');
  const remainHint = $('#remainHint');

  // navPath: array of node ids representing current descent (empty = root)
  let navPath = [];

  function currentChildren() {
    if (navPath.length === 0) return tree;
    const node = findNode(navPath[navPath.length - 1]);
    return node ? node.children : [];
  }

  function aiBubble(innerHtml) {
    return `
      <div class="tm-msg tm-msg-ai">
        <div class="tm-avatar">AI</div>
        <div class="tm-bubble">${innerHtml}</div>
      </div>`;
  }
  function userBubble(text) {
    return `
      <div class="tm-msg tm-msg-user">
        <div class="tm-avatar">YOU</div>
        <div class="tm-bubble">${esc(text)}</div>
      </div>`;
  }

  function renderNav() {
    // rebuild chat log from navPath so it always reflects state
    let logHtml = aiBubble('作業案内を開始します。当てはまる大項目を選択してください。');
    let pathNodes = [];
    for (const id of navPath) {
      const node = findNode(id);
      if (!node) break;
      pathNodes.push(node);
      logHtml += userBubble(node.title);
      const kids = node.children || [];
      if (node.body && node.body.trim()) {
        const leafTag = kids.length === 0
          ? '<span class="tm-leaf-tag">最終作業項目</span>' : '';
        logHtml += aiBubble(leafTag + renderBody(node.body));
        if (kids.length > 0) {
          logHtml += aiBubble('さらに詳しい項目を選択してください。');
        }
      } else if (kids.length > 0) {
        logHtml += aiBubble('次の項目を選択してください。');
      } else {
        logHtml += aiBubble('この項目にはまだ詳細が登録されていません。編集モードから内容を追加できます。');
      }
    }
    chatLog.innerHTML = logHtml;
    chatLog.scrollTop = chatLog.scrollHeight;

    // choices
    const kids = currentChildren();
    if (kids.length > 0) {
      const label = navPath.length === 0 ? '大項目を選択' : '項目を選択';
      choiceDock.innerHTML =
        `<div class="tm-choice-label">${label}</div>` +
        kids.map((c) => {
          const meta = c.children && c.children.length
            ? `${c.children.length} 件の項目`
            : (c.body && c.body.trim() ? '作業内容を表示' : '未登録');
          return `<button class="tm-choice" data-goto="${c.id}">${esc(c.title)}
            <span class="tm-choice-meta">${meta}</span></button>`;
        }).join('');
    } else {
      choiceDock.innerHTML =
        '<div class="tm-emptynote">これ以上の分岐はありません。上のガイドをご確認ください。</div>';
    }

    // breadcrumb
    let crumbs = `<span class="tm-crumb ${navPath.length ? '' : 'is-current'}" data-crumb-home>TOP</span>`;
    pathNodes.forEach((node, i) => {
      const isCurrent = i === pathNodes.length - 1;
      crumbs += `<span class="tm-crumb-sep">&#8250;</span>` +
        `<span class="tm-crumb ${isCurrent ? 'is-current' : ''}" data-crumb="${node.id}">${esc(node.title)}</span>`;
    });
    breadcrumbBar.innerHTML = crumbs;

    backBtn.disabled = navPath.length === 0;
    remainHint.textContent = kids.length ? `残り ${kids.length} 項目` : '';
  }

  function navGoto(id) {
    navPath.push(id);
    renderNav();
  }
  function navBack() {
    navPath.pop();
    renderNav();
  }
  function navTo(id) {
    // jump to a specific ancestor in the path
    const idx = navPath.indexOf(id);
    if (idx >= 0) navPath = navPath.slice(0, idx + 1);
    renderNav();
  }
  function navRestart() {
    navPath = [];
    renderNav();
  }

  choiceDock.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-goto]');
    if (btn) navGoto(btn.dataset.goto);
  });
  breadcrumbBar.addEventListener('click', (e) => {
    if (e.target.closest('[data-crumb-home]')) return navRestart();
    const c = e.target.closest('[data-crumb]');
    if (c) navTo(c.dataset.crumb);
  });
  backBtn.addEventListener('click', navBack);
  restartBtn.addEventListener('click', navRestart);

  /* ============================================================
     EDIT MODE
     ============================================================ */
  const editTree = $('#editTree');
  const saveStatus = $('#saveStatus');

  function flashSaved(msg = '保存しました') {
    saveStatus.textContent = '✓ ' + msg;
    clearTimeout(flashSaved._t);
    flashSaved._t = setTimeout(() => { saveStatus.textContent = ''; }, 2200);
  }

  function renderEdit() {
    if (tree.length === 0) {
      editTree.innerHTML =
        '<div class="tm-emptynote">まだカテゴリがありません。「大項目（カテゴリ）を追加」から始めてください。</div>';
      return;
    }
    editTree.innerHTML = tree.map((n, i) => nodeHtml(n, i, tree.length)).join('');
  }

  function nodeHtml(node, index, siblingCount) {
    const kids = node.children || [];
    const hasKids = kids.length > 0;
    const isOpen = openNodes.has(node.id);
    let badge;
    if (hasKids) badge = `<span class="tm-treenode-badge">${kids.length}項目</span>`;
    else if (node.body && node.body.trim()) badge = `<span class="tm-treenode-badge is-leaf">作業内容</span>`;
    else badge = `<span class="tm-treenode-badge is-doc">未登録</span>`;

    const toggle = hasKids
      ? `<button class="tm-treenode-toggle" data-toggle="${node.id}" title="開閉">${isOpen ? '▼' : '▶'}</button>`
      : `<span class="tm-treenode-toggle is-empty">•</span>`;

    let childrenHtml = '';
    if (hasKids && isOpen) {
      childrenHtml =
        `<div class="tm-treenode-children">` +
        kids.map((c, i) => nodeHtml(c, i, kids.length)).join('') +
        `<button class="tm-treenode-addchild" data-addchild="${node.id}">&#43; 子項目を追加</button>` +
        `</div>`;
    } else if (!hasKids) {
      childrenHtml =
        `<div class="tm-treenode-children">` +
        `<button class="tm-treenode-addchild" data-addchild="${node.id}">&#43; 子項目を追加</button>` +
        `</div>`;
    }

    return `
      <div class="tm-treenode">
        <div class="tm-treenode-row">
          ${toggle}
          <span class="tm-treenode-title" title="${esc(node.title)}">${esc(node.title)}</span>
          ${badge}
          <button class="tm-iconbtn" data-up="${node.id}" title="上へ" ${index === 0 ? 'disabled' : ''}>&#9650;</button>
          <button class="tm-iconbtn" data-down="${node.id}" title="下へ" ${index === siblingCount - 1 ? 'disabled' : ''}>&#9660;</button>
          <button class="tm-iconbtn" data-edit="${node.id}" title="編集">&#9998;</button>
          <button class="tm-iconbtn tm-iconbtn-danger" data-del="${node.id}" title="削除">&#128465;</button>
        </div>
        ${childrenHtml}
      </div>`;
  }

  editTree.addEventListener('click', (e) => {
    const t = e.target.closest('button');
    if (!t) return;
    const d = t.dataset;
    if (d.toggle) {
      if (openNodes.has(d.toggle)) openNodes.delete(d.toggle);
      else openNodes.add(d.toggle);
      persistOpen();
      renderEdit();
    } else if (d.edit) {
      openNodeDialog(d.edit);
    } else if (d.addchild) {
      openNodeDialog(null, d.addchild);
    } else if (d.del) {
      confirmDelete(d.del);
    } else if (d.up) {
      moveNode(d.up, -1);
    } else if (d.down) {
      moveNode(d.down, 1);
    }
  });

  function moveNode(id, dir) {
    const loc = locate(id);
    if (!loc) return;
    const { arr, index } = loc;
    const j = index + dir;
    if (j < 0 || j >= arr.length) return;
    [arr[index], arr[j]] = [arr[j], arr[index]];
    persist();
    renderEdit();
    flashSaved('並び順を変更しました');
  }

  $('#addRootBtn').addEventListener('click', () => openNodeDialog(null, null));

  /* ---------- node dialog ---------- */
  const nodeDialog = $('#nodeDialog');
  const nodeForm = $('#nodeForm');
  const nodeTitleInput = $('#nodeTitleInput');
  const nodeBodyInput = $('#nodeBodyInput');
  const nodeDialogTitle = $('#nodeDialogTitle');
  let dialogTarget = null; // { mode: 'edit'|'add', id, parentId }

  function openNodeDialog(editId, parentId) {
    if (editId) {
      const node = findNode(editId);
      if (!node) return;
      dialogTarget = { mode: 'edit', id: editId };
      nodeDialogTitle.textContent = '項目を編集';
      nodeTitleInput.value = node.title;
      nodeBodyInput.value = node.body || '';
    } else {
      dialogTarget = { mode: 'add', parentId: parentId || null };
      nodeDialogTitle.textContent = parentId ? '子項目を追加' : '大項目（カテゴリ）を追加';
      nodeTitleInput.value = '';
      nodeBodyInput.value = '';
    }
    nodeDialog.showModal();
    nodeTitleInput.focus();
  }

  nodeForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const title = nodeTitleInput.value.trim();
    if (!title) return;
    const body = nodeBodyInput.value;
    if (dialogTarget.mode === 'edit') {
      const node = findNode(dialogTarget.id);
      if (node) { node.title = title; node.body = body; }
    } else {
      const newNode = { id: uid(), title, body, children: [] };
      if (dialogTarget.parentId) {
        const parent = findNode(dialogTarget.parentId);
        if (parent) { parent.children.push(newNode); openNodes.add(parent.id); persistOpen(); }
      } else {
        tree.push(newNode);
      }
    }
    persist();
    nodeDialog.close();
    renderEdit();
    flashSaved();
  });
  $('#nodeCancelBtn').addEventListener('click', () => nodeDialog.close());

  /* ---------- confirm dialog ---------- */
  const confirmDialog = $('#confirmDialog');
  const confirmMsg = $('#confirmMsg');
  const confirmOk = $('#confirmOk');
  let confirmAction = null;

  function askConfirm(msg, action, okLabel = '実行') {
    confirmMsg.textContent = msg;
    confirmOk.textContent = okLabel;
    confirmAction = action;
    confirmDialog.showModal();
  }
  confirmOk.addEventListener('click', () => {
    if (confirmAction) confirmAction();
    confirmAction = null;
    confirmDialog.close();
  });
  $('#confirmCancel').addEventListener('click', () => confirmDialog.close());

  function confirmDelete(id) {
    const node = findNode(id);
    if (!node) return;
    const count = countDescendants(node);
    const extra = count > 0 ? `（子項目 ${count} 件も一緒に削除されます）` : '';
    askConfirm(`「${node.title}」を削除しますか？${extra}`, () => {
      const loc = locate(id);
      if (loc) { loc.arr.splice(loc.index, 1); persist(); renderEdit(); flashSaved('削除しました'); }
    }, '削除');
  }
  function countDescendants(node) {
    let c = 0;
    for (const child of node.children || []) c += 1 + countDescendants(child);
    return c;
  }

  /* ---------- export / import / reset ---------- */
  $('#exportBtn').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(tree, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const ymd = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `tree-manual-${ymd}.json`;
    a.click();
    URL.revokeObjectURL(url);
    flashSaved('エクスポートしました');
  });

  const importFile = $('#importFile');
  $('#importBtn').addEventListener('click', () => importFile.click());
  importFile.addEventListener('change', () => {
    const file = importFile.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(reader.result);
        if (!Array.isArray(data)) throw new Error('形式が不正です');
        const clean = sanitize(data);
        askConfirm('現在のデータを、読み込んだ内容で置き換えますか？', () => {
          tree = clean;
          persist();
          navRestart();
          renderEdit();
          flashSaved('インポートしました');
        }, '置き換える');
      } catch (err) {
        saveStatus.textContent = '✗ 読み込みに失敗しました：' + err.message;
      }
      importFile.value = '';
    };
    reader.readAsText(file);
  });

  // Ensure imported nodes have the required shape and fresh-safe ids.
  function sanitize(nodes) {
    if (!Array.isArray(nodes)) return [];
    return nodes.map((n) => ({
      id: typeof n.id === 'string' && n.id ? n.id : uid(),
      title: String(n.title || '（無題）'),
      body: typeof n.body === 'string' ? n.body : '',
      children: sanitize(n.children || []),
    }));
  }

  $('#resetBtn').addEventListener('click', () => {
    askConfirm('すべてのデータを初期状態（サンプル）に戻しますか？この操作は元に戻せません。', () => {
      tree = seedData();
      openNodes = new Set();
      persist();
      persistOpen();
      navRestart();
      renderEdit();
      flashSaved('初期化しました');
    }, '初期化する');
  });

  /* ============================================================
     MODE SWITCH
     ============================================================ */
  const navView = $('#navView');
  const editView = $('#editView');
  const navModeBtn = $('#navModeBtn');
  const editModeBtn = $('#editModeBtn');

  function setMode(mode) {
    const isNav = mode === 'nav';
    navView.hidden = !isNav;
    editView.hidden = isNav;
    navModeBtn.classList.toggle('is-active', isNav);
    editModeBtn.classList.toggle('is-active', !isNav);
    breadcrumbBar.style.display = isNav ? '' : 'none';
    if (isNav) {
      // re-validate navPath against possibly edited tree
      navPath = navPath.filter((id) => findNode(id));
      let valid = [];
      let cursor = tree;
      for (const id of navPath) {
        const found = cursor.find((x) => x.id === id);
        if (!found) break;
        valid.push(id);
        cursor = found.children;
      }
      navPath = valid;
      renderNav();
    } else {
      renderEdit();
    }
  }
  navModeBtn.addEventListener('click', () => setMode('nav'));
  editModeBtn.addEventListener('click', () => setMode('edit'));

  /* ============================================================
     PWA
     ============================================================ */
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    });
  }
  let deferredPrompt = null;
  const installHint = $('#installHint');
  const installBtn = $('#installBtn');
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    installHint.hidden = false;
  });
  installBtn.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    installHint.hidden = true;
  });
  window.addEventListener('appinstalled', () => { installHint.hidden = true; });

  /* ---------- boot ---------- */
  setMode('nav');
})();

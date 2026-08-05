/* ============================================================
   Case By Case — manual.js  v2
   ツリー型 作業マニュアル（チャットボット風ナビゲーション）
   サーバー(PHP+DB)接続時は「サーバーが唯一の正データ」で複数人共有。
   未接続時は localStorage で単体動作。CSV入出力・画像添付に対応。
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

  const AUTHOR_KEY = 'treeManual.author.v1';
  function authorName() { return (localStorage.getItem(AUTHOR_KEY) || '').trim(); }

  function fmtTime(ms) {
    ms = Number(ms);
    if (!ms) return '';
    const d = new Date(ms);
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  // body 内のメディア種別（🖼 画像 / 📎 ファイル）
  function mediaFlag(body) {
    if (!body) return '';
    if (/<img\b/i.test(body) || /!\[[^\]]*\]\([^)]+\)/.test(body) || /\]\(\/?uploads\/[^)]+\.(png|jpe?g|gif|webp)\)/i.test(body)) return '🖼';
    if (/<a\b/i.test(body) || /\]\(\/?uploads\/[^)]+\)/.test(body) || /\]\(https?:\/\/[^)]+\)/.test(body)) return '📎';
    return '';
  }
  function nodeMetaText(node) {
    const parts = [];
    const by = node.updated_by || node.created_by;
    if (by) parts.push('✎ ' + by);
    const t = fmtTime(node.updated_at || node.created_at);
    if (t) parts.push(t);
    return parts.join(' · ');
  }
  // 階層の呼び名
  function depthLabel(level) {
    return level === 0 ? '大項目' : level === 1 ? '中項目' : '小項目';
  }
  // 本文末尾の「最終更新: 日付時刻 ・ 編集者」薄枠スタンプ
  function editStampHtml(node) {
    const t = fmtTime(node.updated_at || node.created_at);
    const by = node.updated_by || node.created_by;
    if (!t && !by) return '';
    const info = [t, by].filter(Boolean).join(' ・ ');
    return `<span class="tm-editstamp"><span class="tm-editstamp-label">最終更新</span>${esc(info)}</span>`;
  }

  /* ============================================================
     DATA
     node: { id, title, body, children: [] }
     ============================================================ */
  function seedData() {
    const n = (title, body, children = []) => ({ id: uid(), title, body, children });
    return [
      n('パソコンのトラブル', '', [
        n('起動しない', '', [
          n('電源が入らない',
            '# 対処手順\n- 電源ケーブルが正しく接続されているか確認します。\n- 電源ボタンを10秒ほど長押しして放電します。\n- 別のコンセントで試します。\n\n改善しない場合は情報システム担当へ連絡してください。'),
          n('画面が真っ暗のまま',
            '# 対処手順\n- モニターの電源と接続ケーブルを確認します。\n- 画面の明るさ設定を上げます。\n- 本体の電源ランプが点灯しているか確認します。'),
        ]),
        n('インターネットにつながらない',
          '# 対処手順\n- 無線／有線の接続状態を確認します。\n- ルーターを再起動します。\n- 他の端末でもつながらない場合は回線側の問題の可能性があります。'),
      ]),
      n('来客対応', '', [
        n('受付の基本の流れ',
          '# 受付手順\n1. 笑顔で挨拶し、会社名・お名前・ご用件を伺います。\n2. 担当者へ内線で連絡します。\n3. 応接室へご案内します。'),
        n('会議室の準備',
          '# 準備リスト\n- 人数分の椅子と資料を用意\n- プロジェクター／モニターの動作確認\n- お茶・お水の準備'),
      ]),
      n('備品・消耗品', '', [
        n('発注のしかた',
          '# 発注手順\n- 在庫が残りわずかになったら発注します。\n- 所定の発注フォームに記入します。\n- 上長の承認を得てから発注します。'),
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
     CSV  <->  TREE
     フラットな隣接リスト形式: id, parent_id, sort_order, title, body
     （将来の MySQL テーブルにそのまま対応）
     ============================================================ */
  const CSV_HEADER = ['id', 'parent_id', 'sort_order', 'title', 'body'];

  function csvCell(v) {
    v = v == null ? '' : String(v);
    return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
  }
  function rowsToCsv(rows) {
    const lines = [CSV_HEADER.join(',')];
    for (const r of rows) lines.push(CSV_HEADER.map((h) => csvCell(r[h])).join(','));
    return lines.join('\r\n') + '\r\n';
  }
  // RFC4180-ish parser: handles quotes, commas, and newlines inside fields.
  function parseCsv(text) {
    const rows = [];
    let row = [], field = '', inQ = false;
    text = String(text).replace(/^﻿/, '');
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (inQ) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i++; }
          else inQ = false;
        } else field += c;
      } else if (c === '"') {
        inQ = true;
      } else if (c === ',') {
        row.push(field); field = '';
      } else if (c === '\r') {
        /* ignore */
      } else if (c === '\n') {
        row.push(field); rows.push(row); row = []; field = '';
      } else field += c;
    }
    if (field.length || row.length) { row.push(field); rows.push(row); }
    return rows;
  }

  function treeToRows(nodes = tree, parentId = '', out = []) {
    nodes.forEach((n, idx) => {
      out.push({ id: n.id, parent_id: parentId, sort_order: idx, title: n.title, body: n.body || '' });
      treeToRows(n.children || [], n.id, out);
    });
    return out;
  }

  function csvToTree(text) {
    const grid = parseCsv(text).filter((r) => r.length && r.some((c) => c !== ''));
    if (grid.length === 0) return [];
    // header detection
    let start = 0;
    const first = grid[0].map((s) => s.trim().toLowerCase());
    const idx = { id: 0, parent_id: 1, sort_order: 2, title: 3, body: 4 };
    if (first.includes('title') || first.includes('id')) {
      CSV_HEADER.forEach((h) => { const p = first.indexOf(h); if (p >= 0) idx[h] = p; });
      start = 1;
    }
    const map = new Map();
    const orderOf = [];
    for (let i = start; i < grid.length; i++) {
      const r = grid[i];
      const id = (r[idx.id] || '').trim() || uid();
      map.set(id, {
        id,
        title: r[idx.title] != null ? r[idx.title] : '（無題）',
        body: r[idx.body] != null ? r[idx.body] : '',
        children: [],
        _parent: (r[idx.parent_id] || '').trim(),
        _order: Number(r[idx.sort_order]) || i,
      });
      orderOf.push(id);
    }
    const roots = [];
    for (const id of orderOf) {
      const node = map.get(id);
      const parent = node._parent && map.get(node._parent);
      if (parent && parent !== node) parent.children.push(node);
      else roots.push(node);
    }
    const clean = (arr) => {
      arr.sort((a, b) => a._order - b._order);
      arr.forEach((n) => { clean(n.children); delete n._order; delete n._parent; });
    };
    clean(roots);
    return roots;
  }

  /* ============================================================
     SERVER DATA LAYER
     サーバー(DB)接続時は「サーバーを唯一の正データ」とし、項目ごとに
     追加/更新/削除/並び替えする（丸ごと上書きしないので同時編集に強い）。
     未接続時は従来どおり localStorage で動作。
     ============================================================ */
  function buildTreeFromRows(rows) {
    const map = new Map();
    rows.forEach((r) => map.set(r.id, {
      id: r.id, title: r.title, body: r.body || '', children: [],
      created_by: r.created_by || '', updated_by: r.updated_by || '',
      updated_at: Number(r.updated_at) || 0, created_at: Number(r.created_at) || 0,
      locked: !!r.locked, lock: r.lock || '',
      _p: r.parent_id || '', _o: Number(r.sort_order) || 0,
    }));
    const roots = [];
    map.forEach((n) => {
      const p = n._p && map.get(n._p);
      if (p && p !== n) p.children.push(n); else roots.push(n);
    });
    const clean = (arr) => {
      arr.sort((a, b) => a._o - b._o);
      arr.forEach((n) => { clean(n.children); delete n._o; delete n._p; });
    };
    clean(roots);
    return roots;
  }
  function rowsHash(rows) {
    return JSON.stringify(rows.map((r) => [r.id, r.parent_id, r.sort_order, r.title, r.body, r.locked ? 1 : 0]));
  }
  let lastTreeHash = '';
  function applyRows(rows) {
    tree = buildTreeFromRows(rows);
    persist();
    lastTreeHash = rowsHash(rows);
  }
  async function reloadFromServer() {
    const data = await apiCall('tree');
    applyRows(data.nodes || []);
    await reapplyUnlocks();
    await fetchPins(); // 共有ピンも取得（全端末で同じピンを表示）
  }

  const serverMode = () => serverAvailable && dbConnected;

  /* ---------- 閲覧ロック（項目パスワード） ---------- */
  const unlockPw = new Map();     // id -> 入力済みパスワード（この端末のセッション中のみ保持）
  const unlockedIds = new Set();  // この端末で解錠済みの項目ID（保存はしない）

  // 実際に「今ロックされて見えない」状態か（解錠済みなら false）
  function isLocked(node) { return !!(node && node.locked) && !unlockedIds.has(node.id); }

  function mergeUnlockRows(id, rows) {
    // サーバーから返ってきた解錠済みサブツリーを、現在のツリーに反映する
    const sub = buildTreeFromRows(rows || []);
    const target = findNode(id);
    if (!target) return;
    const fresh = sub.find((n) => n.id === id) || (sub.length ? sub[0] : null);
    if (fresh) { target.body = fresh.body; target.children = fresh.children; }
    unlockedIds.add(id);
  }

  async function reapplyUnlocks() {
    // ツリー再取得後、記憶しているパスワードで自動的に解錠し直す
    if (!unlockPw.size) return;
    unlockedIds.clear();
    for (let pass = 0; pass < 6; pass++) {
      let did = false;
      for (const [id, pw] of unlockPw) {
        const node = findNode(id);
        if (!node || isLocked(node) === false) continue;
        try {
          if (serverMode()) {
            const res = await apiCall('unlock', { method: 'POST', body: { id, password: pw } });
            if (res && res.nodes) { mergeUnlockRows(id, res.nodes); did = true; }
          } else {
            if (pw === node.lock || pw === ADMIN_PW) { unlockedIds.add(id); did = true; }
          }
        } catch (_) { /* 失敗時は次回に委ねる */ }
      }
      if (!did) break;
    }
  }

  async function ensureUnlocked(node) {
    if (!isLocked(node)) return true;
    // 既知パスワードで自動解錠を試す
    if (unlockPw.has(node.id)) {
      const pw = unlockPw.get(node.id);
      if (serverMode()) {
        try { const res = await apiCall('unlock', { method: 'POST', body: { id: node.id, password: pw } }); if (res && res.nodes) { mergeUnlockRows(node.id, res.nodes); return true; } } catch (_) {}
      } else if (pw === node.lock || pw === ADMIN_PW) { unlockedIds.add(node.id); return true; }
    }
    let err = '';
    for (;;) {
      const pw = await askUnlock(node.title, err);
      if (pw == null) return false;
      if (serverMode()) {
        try {
          const res = await apiCall('unlock', { method: 'POST', body: { id: node.id, password: pw } });
          if (res && res.nodes) { mergeUnlockRows(node.id, res.nodes); unlockPw.set(node.id, pw); return true; }
          err = 'パスワードが違います。';
        } catch (_) { err = 'パスワードが違います。'; }
      } else {
        if (pw === node.lock || pw === ADMIN_PW) { unlockedIds.add(node.id); unlockPw.set(node.id, pw); return true; }
        err = 'パスワードが違います。';
      }
    }
  }

  function lockFields(lockOpt) {
    // サーバー送信用の閲覧ロック指定。lockOpt 未指定なら変更なし。
    if (!lockOpt) return {};
    return { lock_enabled: lockOpt.enabled ? 1 : 0, lock: lockOpt.pw || '' };
  }
  async function opCreate(parentId, title, body, author, lockOpt) {
    const who = author != null ? author : authorName();
    if (serverMode()) {
      await apiCall('node_create', { method: 'POST', body: Object.assign({ parent_id: parentId || '', title, body, author: who }, lockFields(lockOpt)) });
      await reloadFromServer();
    } else {
      const now = Date.now();
      const newNode = { id: uid(), title, body, children: [], created_by: who, updated_by: who, created_at: now, updated_at: now, locked: false, lock: '' };
      if (lockOpt && lockOpt.enabled && lockOpt.pw) { newNode.lock = lockOpt.pw; newNode.locked = true; unlockPw.set(newNode.id, lockOpt.pw); unlockedIds.add(newNode.id); }
      if (parentId) { const p = findNode(parentId); if (p) p.children.push(newNode); }
      else tree.push(newNode);
      persist();
    }
  }
  async function opUpdate(id, title, body, author, lockOpt, updatedAt) {
    const who = author != null ? author : authorName();
    const ua = (typeof updatedAt === 'number' && updatedAt > 0) ? updatedAt : null;
    if (serverMode()) {
      const body2 = Object.assign({ id, title, body, author: who }, lockFields(lockOpt));
      if (ua) body2.updated_at = ua;
      await apiCall('node_update', { method: 'POST', body: body2 });
      await reloadFromServer();
    } else {
      const n = findNode(id);
      if (n) {
        n.title = title; n.body = body; n.updated_by = who || n.updated_by; n.updated_at = ua || Date.now();
        if (lockOpt) {
          if (!lockOpt.enabled) { n.lock = ''; n.locked = false; unlockPw.delete(id); unlockedIds.delete(id); }
          else if (lockOpt.pw) { n.lock = lockOpt.pw; n.locked = true; unlockPw.set(id, lockOpt.pw); unlockedIds.add(id); }
          // enabled かつ pw 空 → 既存のロック状態・パスワードを維持（変更なし）
        }
      }
      persist();
    }
  }
  async function opDelete(id) {
    if (serverMode()) {
      await apiCall('node_delete', { method: 'POST', body: { id } });
      await reloadFromServer();
    } else {
      const loc = locate(id); if (loc) loc.arr.splice(loc.index, 1); persist();
    }
  }
  async function opMove(id, dir) {
    if (serverMode()) {
      await apiCall('node_move', { method: 'POST', body: { id, dir } });
      await reloadFromServer();
    } else {
      const loc = locate(id);
      if (loc) { const j = loc.index + dir; if (j >= 0 && j < loc.arr.length) [loc.arr[loc.index], loc.arr[j]] = [loc.arr[j], loc.arr[loc.index]]; }
      persist();
    }
  }
  async function opReparent(id, parentId, beforeId) {
    if (serverMode()) {
      await apiCall('node_reparent', {
        method: 'POST',
        body: { id, parent_id: parentId || '', before_id: beforeId || '' },
      });
      await reloadFromServer();
    } else {
      const loc = locate(id);
      if (!loc) return;
      const [node] = loc.arr.splice(loc.index, 1);
      const targetArr = parentId ? ((findNode(parentId) || {}).children) : tree;
      if (!targetArr) { loc.arr.splice(loc.index, 0, node); return; } // 失敗時は戻す
      let idx = targetArr.length;
      if (beforeId) { const bi = targetArr.findIndex((x) => x.id === beforeId); if (bi >= 0) idx = bi; }
      targetArr.splice(idx, 0, node);
      persist();
    }
  }

  async function opReplaceAll(nodes) {
    if (serverMode()) {
      await apiCall('replace_all', { method: 'POST', body: { nodes: treeToRowsMeta(nodes) } });
      await reloadFromServer();
    } else {
      tree = nodes; persist();
    }
  }

  /* ============================================================
     BODY rendering (very small markdown-ish)
     ============================================================ */
  function safeUrl(u) {
    u = String(u).trim();
    if (/^(https?:\/\/|\/?uploads\/)/i.test(u)) return u;
    if (/^[\w./-]+\.(png|jpe?g|gif|webp)$/i.test(u)) return u;
    return null;
  }
  // 文字装飾（**太字** / [color=red]色[/color] / [big]/[small] 等）
  const COLOR_MAP = {
    red: '#ff5a6e', blue: '#4aa3ff', green: '#38d39f', yellow: '#ffd54a',
    cyan: '#3fe0e0', white: '#ffffff', gray: '#9fb4bd', orange: '#ff9f45', pink: '#ff7ac6',
  };
  function cssColor(c) {
    c = String(c).toLowerCase();
    if (COLOR_MAP[c]) return COLOR_MAP[c];
    if (/^#[0-9a-f]{3}$|^#[0-9a-f]{6}$/.test(c)) return c;
    return '#d8f3f4';
  }
  function fmtInline(html) {
    html = html.replace(/\*\*([^*\n]+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\[color=([#a-z0-9]+)\]([\s\S]*?)\[\/color\]/gi, (m, c, t) => `<span style="color:${cssColor(c)}">${t}</span>`);
    html = html.replace(/\[(xbig|big|small)\]([\s\S]*?)\[\/\1\]/gi, (m, s, t) => `<span class="tm-fs-${s.toLowerCase()}">${t}</span>`);
    return html;
  }

  /* ---------- HTML サニタイズ（共有される本文HTMLを安全に描画） ---------- */
  const ALLOWED_TAGS = {
    b: {}, strong: {}, i: {}, em: {}, u: {},
    span: { attrs: ['style'] }, font: { attrs: ['color', 'size'] },
    br: { void: true }, div: { attrs: ['style', 'class', 'data-ff', 'data-value'] }, p: { attrs: ['style'] },
    a: { attrs: ['href', 'style', 'class'] }, img: { attrs: ['src', 'style', 'class', 'alt', 'title'], void: true },
    // 本文に保存する入力欄（記入フォーム）。スクリプトは持てない安全な素の入力要素のみ許可。
    label: { attrs: ['class'] },
    input: { attrs: ['type', 'class', 'value', 'checked', 'placeholder', 'maxlength'], void: true },
    textarea: { attrs: ['class', 'rows', 'placeholder'] },
  };
  function sanitizeClass(v) {
    const allow = {
      'tm-filechip': 1, 'tm-body-img': 1,
      'tm-formfield': 1, 'tm-ff-label': 1, 'tm-ff-input': 1, 'tm-ff-cb': 1,
    };
    return String(v).split(/\s+/).filter((t) => allow[t]).join(' ');
  }
  function safeLinkUrl(u) {
    u = String(u).trim();
    return /^(https?:\/\/|\/?uploads\/|mailto:)/i.test(u) ? u : null;
  }
  function sanitizeStyle(style) {
    const allow = { color: 1, 'font-size': 1, 'font-weight': 1, 'text-decoration': 1, 'font-style': 1, 'background-color': 1, 'text-align': 1 };
    const out = [];
    String(style).split(';').forEach((decl) => {
      const i = decl.indexOf(':');
      if (i < 0) return;
      const prop = decl.slice(0, i).trim().toLowerCase();
      const val = decl.slice(i + 1).trim();
      if (!allow[prop]) return;
      if (/url\(|expression|javascript:|@import/i.test(val)) return;
      if (!/^[#a-zA-Z0-9.,%()\s-]+$/.test(val)) return;
      out.push(`${prop}:${val}`);
    });
    return out.join(';');
  }
  // 素のURLを安全に <a> 化（既に<a>内のテキストは対象外）
  function linkifyText(text) {
    const s = String(text);
    if (s.indexOf('http') < 0) return esc(s);
    let out = '';
    let last = 0;
    const re = /https?:\/\/[^\s<>"'）)】」』]+/g;
    let m;
    while ((m = re.exec(s))) {
      out += esc(s.slice(last, m.index));
      let url = m[0];
      const tm = url.match(/[.,!?;:、。]+$/); // 末尾の句読点はリンクから除外
      let tail = '';
      if (tm) { tail = url.slice(url.length - tm[0].length); url = url.slice(0, url.length - tm[0].length); }
      const safe = safeLinkUrl(url);
      out += safe
        ? `<a href="${esc(safe)}" target="_blank" rel="noopener">${esc(url)}</a>${esc(tail)}`
        : esc(m[0]);
      last = re.lastIndex;
    }
    out += esc(s.slice(last));
    return out;
  }
  // プレーンテキスト（メモ等の貼り付け）を改行を保ったHTMLに変換
  function plainToHtml(text) {
    const norm = String(text == null ? '' : text).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    return norm.split('\n').map((l) => linkifyText(l) || '').join('<br>');
  }
  // 貼り付けHTMLを安全化（文字サイズ・色・太字などは保持、画像は除外＝画像は「画像を追加」から）
  function sanitizePastedHtml(html) {
    const clean = sanitizeHtml(html);
    const tmp = document.createElement('div');
    tmp.innerHTML = clean;
    tmp.querySelectorAll('img').forEach((im) => im.remove());
    return tmp.innerHTML;
  }
  function sanitizeInto(node, out, insideLink) {
    node.childNodes.forEach((child) => {
      if (child.nodeType === 3) { out.push(insideLink ? esc(child.nodeValue) : linkifyText(child.nodeValue)); return; }
      if (child.nodeType !== 1) return;
      const tag = child.tagName.toLowerCase();
      const spec = ALLOWED_TAGS[tag];
      if (!spec) {
        if (tag === 'script' || tag === 'style') return;
        sanitizeInto(child, out, insideLink); // 不許可タグは中身だけ残す
        return;
      }
      let attrs = '';
      (spec.attrs || []).forEach((a) => {
        let v = child.getAttribute(a);
        if (v == null) return;
        if (a === 'href') { v = safeLinkUrl(v); if (!v) return; }
        else if (a === 'src') { v = safeUrl(v); if (!v) return; }
        else if (a === 'class') { v = sanitizeClass(v); if (!v) return; }
        else if (a === 'style') { v = sanitizeStyle(v); if (!v) return; }
        else if (a === 'color') { v = cssColor(v); }
        else if (a === 'size') { if (!/^[1-7]$/.test(v)) return; }
        else if (a === 'type') { v = String(v).toLowerCase(); if (!/^(text|checkbox)$/.test(v)) return; }
        else if (a === 'checked') { v = 'checked'; }
        else if (a === 'rows') { if (!/^\d{1,3}$/.test(v)) return; }
        else if (a === 'maxlength') { if (!/^\d{1,4}$/.test(v)) return; }
        else if (a === 'data-ff') { if (!/^(text|textarea|check|time)$/.test(v)) return; }
        else if (a === 'data-value') { if (!/^\d{2}:\d{2}$/.test(v)) return; }
        attrs += ` ${a}="${esc(v)}"`;
      });
      if (tag === 'a') attrs += ' target="_blank" rel="noopener"';
      if (spec.void) { out.push(`<${tag}${attrs}>`); return; }
      // textarea は中身を「そのままの文字」として扱う（タグ化・リンク化しない）
      if (tag === 'textarea') { out.push(`<textarea${attrs}>${esc(child.textContent)}</textarea>`); return; }
      out.push(`<${tag}${attrs}>`);
      sanitizeInto(child, out, insideLink || tag === 'a');
      out.push(`</${tag}>`);
    });
  }
  function sanitizeHtml(html) {
    const tpl = document.createElement('template');
    tpl.innerHTML = String(html == null ? '' : html);
    const out = [];
    sanitizeInto(tpl.content, out);
    return out.join('');
  }
  // HTML本文をプレーンテキスト化（Excel出力・検索用）
  function htmlToPlain(html) {
    const s = String(html == null ? '' : html);
    if (!/<[a-z!/]/i.test(s)) return s; // 既にプレーン/旧記法
    let t = s.replace(/<\s*br\s*\/?>/gi, '\n').replace(/<\/(div|p|li|h[1-6])\s*>/gi, '\n');
    t = t.replace(/<[^>]+>/g, '');
    const ta = document.createElement('textarea');
    ta.innerHTML = t;
    t = ta.value;
    return t.replace(/\n{3,}/g, '\n\n').replace(/[ \t]+\n/g, '\n').trim();
  }
  // 本文がHTMLらしいか（新方式）／プレーン・旧記法か（レガシー）で描画を切替
  function looksLikeHtml(s) { return /<(b|strong|i|em|u|span|font|br|div|p|a|img)\b[^>]*>/i.test(String(s)); }
  function renderBody(text) {
    const s = String(text == null ? '' : text);
    if (looksLikeHtml(s)) return sanitizeHtml(s);
    return renderLegacyBody(s);
  }

  function renderLegacyBody(text) {
    const lines = String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
    let html = '';
    let inList = false;
    let para = [];
    const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
    // 連続した通常行は1段落にまとめ、改行(Enter)は<br>で表示。空行で段落を分ける。
    const flushPara = () => { if (para.length) { html += `<p>${para.join('<br>')}</p>`; para = []; } };
    // [ラベル](URL) のリンク と 素のURL を安全にリンク化
    const inline = (raw) => {
      let out = '';
      let last = 0;
      const re = /\[([^\]]+)\]\(([^)]+)\)|(https?:\/\/[^\s]+)/g;
      let m;
      while ((m = re.exec(raw))) {
        out += esc(raw.slice(last, m.index));
        if (m[1] !== undefined) {
          const u = safeUrl(m[2]);
          out += u
            ? `<a href="${esc(u)}" target="_blank" rel="noopener">${esc(m[1])}</a>`
            : esc(m[0]);
        } else {
          out += `<a href="${esc(m[3])}" target="_blank" rel="noopener">${esc(m[3])}</a>`;
        }
        last = re.lastIndex;
      }
      out += esc(raw.slice(last));
      return fmtInline(out);
    };

    for (const raw of lines) {
      const line = raw.trimEnd();
      const img = line.match(/^!\[([^\]]*)\]\(([^)]+)\)\s*$/);
      if (img) {
        flushPara(); closeList();
        const u = safeUrl(img[2]);
        if (u) html += `<img class="tm-body-img" src="${esc(u)}" alt="${esc(img[1])}" loading="lazy">`;
      } else if (/^#{1,3}\s+/.test(line)) {
        flushPara(); closeList();
        html += `<h3>${inline(line.replace(/^#{1,3}\s+/, ''))}</h3>`;
      } else if (/^[-*]\s+/.test(line)) {
        flushPara();
        if (!inList) { html += '<ul>'; inList = true; }
        html += `<li>${inline(line.replace(/^[-*]\s+/, ''))}</li>`;
      } else if (line.trim() === '') {
        flushPara(); closeList();
      } else {
        closeList();
        para.push(inline(line));
      }
    }
    flushPara(); closeList();
    return html;
  }

  /* ============================================================
     NAVIGATION (chat) MODE
     ============================================================ */
  const chatLog = $('#chatLog');
  const choiceDock = $('#choiceDock');
  const navAddDock = $('#navAddDock');
  const breadcrumbBar = $('#breadcrumbBar');
  const backBtn = $('#backBtn');
  const restartBtn = $('#restartBtn');
  const backBtnTop = $('#backBtnTop');
  const restartBtnTop = $('#restartBtnTop');
  const remainHint = $('#remainHint');

  /* ---------- ピン留め（よく使う項目をTOPに固定・サーバーで全端末共有） ---------- */
  const PIN_KEY = 'treeManual.pins.v1'; // オフライン表示用のローカルキャッシュ
  let pins = loadPinsCache();
  function loadPinsCache() {
    try { const a = JSON.parse(localStorage.getItem(PIN_KEY) || '[]'); return Array.isArray(a) ? a.filter((x) => typeof x === 'string') : []; }
    catch (_) { return []; }
  }
  function savePinsCache() { try { localStorage.setItem(PIN_KEY, JSON.stringify(pins)); } catch (_) {} }
  function isPinned(id) { return pins.indexOf(id) >= 0; }
  // サーバーから共有ピンを取得（全端末で同じピンが見える）
  async function fetchPins() {
    if (!serverMode()) return;
    try {
      const d = await apiCall('pins_get');
      let arr = [];
      try { arr = JSON.parse(d.pins || '[]'); } catch (_) { arr = []; }
      if (Array.isArray(arr)) { pins = arr.filter((x) => typeof x === 'string'); savePinsCache(); }
    } catch (_) { /* 取得できなければローカルキャッシュのまま */ }
  }
  // サーバーへ共有ピンを保存
  async function pushPins() {
    savePinsCache();
    if (!serverMode()) { syncMsg('サーバー未接続のため、ピンはこの端末のみ保存されました', true); return; }
    try { await apiCall('pins_set', { method: 'POST', body: { pins } }); }
    catch (e) { syncMsg('ピンの共有保存に失敗：' + e.message, true); }
  }
  function togglePin(id) {
    const i = pins.indexOf(id);
    if (i >= 0) pins.splice(i, 1); else pins.push(id);
    pushPins(); // ローカル保存＋サーバー共有
  }

  /* ---------- 記入内容の一覧（別ポップアップ・コピー用） ---------- */
  function hasFormFields(body) { return /class="tm-formfield"|data-ff=/.test(String(body || '')); }
  // 入力欄のラベルが空のときは、欄のすぐ上（直前）にある本文テキストを項目名として使う。
  // 文書順で1つずつ前のノードへ遡り（入れ子・段落分割・空行をまたいでも拾える）、
  // 別の記入欄に達したらそこで打ち切る（＝その欄の直前に書かれた名前だけを採用）。
  function precedingLabel(ff) {
    const root = ff.closest('.tm-content-body');
    if (!root) return '';
    const prev = (n) => {
      if (n === root) return null;
      if (n.previousSibling) { let p = n.previousSibling; while (p.lastChild) p = p.lastChild; return p; }
      return n.parentNode;
    };
    let n = prev(ff);
    let steps = 0;
    while (n && n !== root && steps < 400) {
      steps++;
      if (n.nodeType === 1 && n.classList && n.classList.contains('tm-formfield')) break;
      if (n.nodeType === 1 && n.closest && n.closest('.tm-formfield')) break;
      if (n.nodeType === 3) {
        const par = n.parentNode;
        const inField = par && par.closest && par.closest('.tm-formfield');
        if (!inField) {
          const s = n.nodeValue.replace(/\u00a0/g, ' ').replace(/[\uFF1A:\s]+$/, '').trim();
          if (s) return s.split('\n').pop().replace(/[\uFF1A:\s]+$/, '').trim();
        }
      }
      n = prev(n);
    }
    return '';
  }
  // 現在表示中（案内画面）の記入欄から、今入力されている値を集める（DOMを書き換えない＝入力は保持）
  function collectFieldValues() {
    const out = [];
    chatLog.querySelectorAll('.tm-content-body .tm-formfield').forEach((ff) => {
      const kind = ff.getAttribute('data-ff') || (ff.querySelector('.tm-ff-cb') ? 'check' : (ff.querySelector('textarea') ? 'textarea' : 'text'));
      const labelEl = ff.querySelector('.tm-ff-label');
      let label = labelEl ? labelEl.textContent.replace(/[\uFF1A:\s]+$/, '').trim() : '';
      if (!label) label = precedingLabel(ff); // ラベル未入力なら直前の本文を名前に使う
      if (!label) label = '記入欄' + (out.length + 1); // それも無ければ通し番号
      if (kind === 'check') {
        const cb = ff.querySelector('.tm-ff-cb');
        out.push({ label, kind, checked: !!(cb && cb.checked) });
      } else if (kind === 'time') {
        const h = ff.querySelector('.tm-ff-hour'), m = ff.querySelector('.tm-ff-min');
        out.push({ label, kind, value: (h && h.value && m && m.value) ? (h.value + ':' + m.value) : '' });
      } else {
        const valEl = ff.querySelector('.tm-ff-input');
        out.push({ label, kind, value: valEl ? valEl.value : '' });
      }
    });
    return out;
  }
  const fieldListDialog = $('#fieldListDialog');
  function openFieldList() {
    if (!fieldListDialog) return;
    const items = collectFieldValues();
    const bodyEl = $('#fieldListBody');
    if (bodyEl) {
      bodyEl.innerHTML = items.length ? items.map((it) => {
        let val;
        if (it.kind === 'check') val = it.checked ? '<span class="tm-list-check">&#9745; チェック済み</span>' : '<span class="tm-list-empty">□ 未チェック</span>';
        else val = (it.value && it.value.trim()) ? esc(it.value) : '<span class="tm-list-empty">（未入力）</span>';
        return `<div class="tm-list-row"><span class="tm-list-label">${esc(it.label || '（ラベルなし）')}</span><span class="tm-list-value">${val}</span></div>`;
      }).join('') : '<p class="tm-list-empty">この画面には記入欄がありません。</p>';
    }
    // コピー用テキスト（ラベル：値）
    const text = items.map((it) => {
      let v;
      if (it.kind === 'check') v = it.checked ? 'チェック済み' : '未チェック';
      else v = it.value || '';
      return (it.label || '') + '：' + v;
    }).join('\n');
    const ta = $('#fieldListText'); if (ta) ta.value = text;
    const msg = $('#fieldListMsg'); if (msg) msg.textContent = '';
    listOpen = true;
    fieldListDialog.showModal();
    syncTrap();
  }
  if (fieldListDialog) {
    fieldListDialog.addEventListener('close', () => { listOpen = false; syncTrap(); });
    const cl = $('#fieldListClose'); if (cl) cl.addEventListener('click', () => fieldListDialog.close());
    const cp = $('#fieldListCopy'); if (cp) cp.addEventListener('click', async () => {
      const ta = $('#fieldListText'); const msg = $('#fieldListMsg');
      if (!ta) return;
      let ok = false;
      try { await navigator.clipboard.writeText(ta.value); ok = true; }
      catch (_) { try { ta.focus(); ta.select(); ok = document.execCommand('copy'); } catch (__) { ok = false; } }
      if (msg) msg.textContent = ok ? 'コピーしました。' : 'コピーできませんでした。テキストを選択してコピーしてください。';
    });
  }

  /* ---------- AI要約（Gemini・サーバー経由） ---------- */
  let aiOpen = false;             // AI要約ダイアログ表示中
  let lastAiSummaryText = '';
  const aiSummaryDialog = $('#aiSummaryDialog');
  async function openAiSummary(id) {
    if (!aiSummaryDialog) return;
    const node = findNode(id);
    const forEl = $('#aiSummaryFor'), bodyEl = $('#aiSummaryBody'), msg = $('#aiSummaryMsg');
    if (forEl) forEl.textContent = node ? '対象：' + node.title : '';
    if (bodyEl) bodyEl.textContent = '要約を作成中…';
    if (msg) msg.textContent = '';
    lastAiSummaryText = '';
    aiOpen = true;
    aiSummaryDialog.showModal();
    syncTrap();
    try {
      const d = await apiCall('ai_summarize', { method: 'POST', body: { id } });
      lastAiSummaryText = (d.summary || '').trim();
      if (bodyEl) bodyEl.textContent = lastAiSummaryText || '（要約を取得できませんでした）';
    } catch (e) {
      if (bodyEl) bodyEl.textContent = 'AI要約に失敗しました：' + e.message;
    }
  }
  // 修正画面のAI ON/OFFトグルの表示を状態に合わせて更新
  function updateAiToggleUI() {
    const chk = $('#aiToggle'), hint = $('#aiToggleHint');
    if (chk) chk.checked = aiOn;
    if (hint) {
      hint.textContent = hasGemini
        ? 'サーバーにAPIキーが設定済みです。ONにすると各項目でAI要約・AIで探すが使えます。'
        : 'サーバーにAPIキー（config.php の GEMINI_API_KEY）を設定すると使用可能になります。ONにしても、キー未設定のうちはAIボタンは表示されません。';
    }
  }
  { const chk = $('#aiToggle'); if (chk) chk.addEventListener('change', async () => {
    const want = chk.checked;
    aiOn = want;
    aiEnabled = hasGemini && aiOn;
    try {
      await apiCall('ai_set_enabled', { method: 'POST', body: { on: want } });
      syncMsg(want ? 'AI機能をONにしました' : 'AI機能をOFFにしました（AIボタンを非表示）');
    } catch (e) {
      // 失敗したら表示を戻す
      aiOn = !want; chk.checked = aiOn; aiEnabled = hasGemini && aiOn;
      syncMsg('AI設定の保存に失敗：' + e.message, true);
    }
    updateAiToggleUI();
  }); }

  if (aiSummaryDialog) {
    aiSummaryDialog.addEventListener('close', () => { aiOpen = false; syncTrap(); });
    const cl = $('#aiSummaryClose'); if (cl) cl.addEventListener('click', () => aiSummaryDialog.close());
    const cp = $('#aiSummaryCopy'); if (cp) cp.addEventListener('click', async () => {
      const msg = $('#aiSummaryMsg');
      if (!lastAiSummaryText) { if (msg) msg.textContent = 'コピーする要約がありません。'; return; }
      let ok = false;
      try { await navigator.clipboard.writeText(lastAiSummaryText); ok = true; }
      catch (_) { ok = false; }
      if (msg) msg.textContent = ok ? 'コピーしました。' : 'コピーできませんでした。テキストを選択してコピーしてください。';
    });
  }

  // navPath: array of node ids representing current descent (empty = root)
  let navPath = [];
  let navReorder = false; // 案内モードの簡易並べ替えモード
  let invOpen = false;    // 在庫管理ビューを開いているか
  let searchOpen = false; // 検索ダイアログを開いているか
  let updatesOpen = false; // 更新履歴ダイアログを開いているか
  let helpOpen = false;    // 使い方ダイアログを開いているか
  let listOpen = false;    // 記入内容の一覧ダイアログを開いているか

  function currentChildren() {
    if (navPath.length === 0) return tree;
    const node = findNode(navPath[navPath.length - 1]);
    return node ? node.children : [];
  }

  function renderBreadcrumb() {
    let crumbs = `<span class="tm-crumb ${navPath.length ? '' : 'is-current'}" data-crumb-home>TOP</span>`;
    let cursor = tree;
    for (const id of navPath) {
      const node = cursor.find((x) => x.id === id);
      if (!node) break;
      const isCur = id === navPath[navPath.length - 1];
      crumbs += `<span class="tm-crumb-sep">&#8250;</span>` +
        `<span class="tm-crumb ${isCur ? 'is-current' : ''}" data-crumb="${node.id}">${esc(node.title)}</span>`;
      cursor = node.children;
    }
    breadcrumbBar.innerHTML = crumbs;
  }

  function categoryTile(c, i) {
    const count = (c.children || []).length;
    const flag = mediaFlag(c.body);
    const locked = isLocked(c);
    const sub = locked ? '要パスワード' : (count ? `${count} 項目` : (c.body && c.body.trim() ? '内容あり' : '未登録'));
    return `<button class="tm-cat-tile${locked ? ' is-locked' : ''}" data-goto="${c.id}">
      <span class="tm-cat-no">${locked ? '<span class="tm-lock-i">🔒</span>' : String(i + 1).padStart(2, '0')}</span>
      <span class="tm-cat-name">${esc(c.title)}</span>
      <span class="tm-cat-sub">${sub}${!locked && flag ? ' · ' + flag : ''}</span>
    </button>`;
  }

  // ピン留めした項目のパス表示（TOP › 親 › 子 …、末尾の自分は除く）
  function pinPathLabel(id) {
    const path = findPath(id);
    if (!path) return '';
    const names = path.slice(0, -1).map((n) => esc(n.title));
    return ['TOP', ...names].join(' › ');
  }
  // TOP画面のピン留めセクション（この端末のみ。存在しない項目は自動的に除外）
  function pinsSectionHtml() {
    const valid = pins.filter((id) => findNode(id));
    if (valid.length !== pins.length) { pins = valid; savePinsCache(); } // 消えた項目は掃除（この端末の表示のみ）
    if (!valid.length) return '';
    const tiles = valid.map((id) => {
      const node = findNode(id);
      const locked = isLocked(node);
      const path = pinPathLabel(id);
      return `<div class="tm-pin-tile" data-pingoto="${id}" role="button" tabindex="0">
        <span class="tm-pin-star">&#9733;</span>
        <span class="tm-pin-body">
          <span class="tm-pin-name">${locked ? '🔒 ' : ''}${esc(node.title)}</span>
          <span class="tm-pin-path">${path}</span>
        </span>
        <span class="tm-pin-remove" data-unpin="${id}" role="button" tabindex="0" title="ピン留めを外す">&#10005;</span>
      </div>`;
    }).join('');
    return `<div class="tm-pins">
      <div class="tm-pins-label">&#9733; ピン留め</div>
      <div class="tm-pins-grid">${tiles}</div>
    </div>`;
  }

  function choiceRow(c) {
    const count = (c.children || []).length;
    const flag = mediaFlag(c.body);
    const locked = isLocked(c);
    const meta = locked ? '要パスワード' : (count ? `${count} 項目` : (c.body && c.body.trim() ? '作業内容を表示' : '未登録'));
    return `<button class="tm-choice${locked ? ' is-locked' : ''}" data-goto="${c.id}">
      <span class="tm-choice-main">${locked ? '🔒 ' : ''}${esc(c.title)}</span>
      <span class="tm-choice-meta">${!locked && flag ? flag + ' · ' : ''}${meta}</span>
    </button>`;
  }

  // 簡易並べ替えモードの1行（長押し／ハンドルのドラッグで移動、▲▼でも移動可）
  function sortRow(c, i, n) {
    const locked = isLocked(c);
    return `<div class="tm-sortrow" data-sortid="${c.id}">
      <span class="tm-drag-handle" title="長押し／ハンドルをドラッグで並べ替え">&#8942;&#8942;</span>
      <span class="tm-sortrow-name">${locked ? '🔒 ' : ''}${esc(c.title)}</span>
      <span class="tm-sortrow-ctrls">
        <button class="tm-iconbtn" data-navup="${c.id}" type="button" ${i === 0 ? 'disabled' : ''} title="上へ">&#9650;</button>
        <button class="tm-iconbtn" data-navdown="${c.id}" type="button" ${i === n - 1 ? 'disabled' : ''} title="下へ">&#9660;</button>
      </span>
    </div>`;
  }

  function renderNav() {
    refreshUpdatesBadge();
    // サーバーに正しく接続できていないとき（未ログイン/接続不可/DB未接続）は
    // 全画面ゲートで中身を隠し、ローカルの古いデータは一切表示しない。
    if (gateReason()) {
      breadcrumbBar.innerHTML = `<span class="tm-crumb is-current" data-crumb-home>TOP</span>`;
      chatLog.innerHTML = ''; choiceDock.innerHTML = ''; navAddDock.innerHTML = '';
      backBtn.disabled = true; if (backBtnTop) backBtnTop.disabled = true;
      remainHint.textContent = '';
      updateAuthGate();
      return;
    }

    const atRoot = navPath.length === 0;
    const curNode = atRoot ? null : findNode(navPath[navPath.length - 1]);
    const kids = currentChildren();

    renderBreadcrumb();

    let html = '';
    if (atRoot) {
      const upd = recentUpdatesInfo();
      let notice = '';
      if (upd.count && upd.latest) {
        const l = upd.latest;
        const d = new Date(Number(l.when) || 0);
        const p = (n) => String(n).padStart(2, '0');
        const when = isNaN(d.getTime()) ? '' : `${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
        const more = upd.count > 1 ? ` ほか${upd.count - 1}件` : '';
        notice = `<button class="tm-hero-update" id="heroUpdate" type="button"><span class="tm-hero-update-ico">&#9679;</span>直近の更新：${esc(l.title)}${l.who ? '（' + esc(l.who) + '）' : ''}${when ? ' ' + when : ''}${more}</button>`;
      }
      html = `<div class="tm-hero">
        <div class="tm-hero-kicker">MANUAL NAVIGATOR</div>
        <h1 class="tm-hero-title">大項目を選択</h1>
        <p class="tm-hero-sub">該当のカテゴリを選択してください。</p>
        ${notice}
      </div>`;
      html += pinsSectionHtml();
    } else if (curNode) {
      const leaf = kids.length === 0;
      const stamp = editStampHtml(curNode);
      html += `<div class="tm-current">
        <div class="tm-current-head">
          <div class="tm-current-headtext">
            <div class="tm-current-kicker">現在地 · ${depthLabel(navPath.length - 1)}</div>
            <h2 class="tm-current-title">${esc(curNode.title)}</h2>
          </div>
          <div class="tm-current-actions">
            ${(aiEnabled && curNode.body && curNode.body.trim()) ? `<button class="tm-aibtn" data-aisummary="${curNode.id}" type="button" title="この項目をAIで要約">&#10024; AI要約</button>` : ''}
            ${hasFormFields(curNode.body) ? `<button class="tm-listbtn" data-listfields type="button" title="記入した内容を一覧で表示・コピー">&#128203; 記入内容を一覧</button>` : ''}
            <button class="tm-pinbtn ${isPinned(curNode.id) ? 'is-on' : ''}" data-pintoggle="${curNode.id}" type="button" title="TOP画面にピン留め">${isPinned(curNode.id) ? '&#9733; ピン留め中' : '&#9734; ピン留め'}</button>
            <button class="tm-editthis" data-editthis="${curNode.id}" type="button">&#9998; この項目を編集</button>
          </div>
        </div>`;
      if (curNode.body && curNode.body.trim()) {
        html += `<div class="tm-content-card ${leaf ? 'is-final' : ''}">
          ${leaf ? '<span class="tm-leaf-tag">最終作業項目</span>' : ''}
          <div class="tm-content-body">${renderBody(curNode.body)}</div>
          ${stamp ? `<div class="tm-stamp-row">${stamp}</div>` : ''}
        </div>`;
      } else {
        html += `<div class="tm-content-card tm-content-empty">
          <p class="tm-emptynote">まだ内容がありません。「この項目を編集」から手順や画像・ファイルを追加できます。</p>
          ${stamp ? `<div class="tm-stamp-row">${stamp}</div>` : ''}
        </div>`;
      }
      html += `</div>`;
    }
    chatLog.innerHTML = html;
    enhanceContentImages();
    buildTimeSelects(chatLog); // 保存済み時刻フィールドに時・分プルダウンを表示
    chatLog.scrollTop = 0;

    const canReorder = kids.length > 1;
    if (navReorder && !canReorder) navReorder = false; // 並べ替え対象が無ければ解除

    // 大項目一覧の先頭に出す「在庫管理」タイル（特別項目）
    const invTile = `<button class="tm-cat-tile tm-inv-tile" data-inv type="button">
      <span class="tm-cat-no">&#128230;</span>
      <span class="tm-cat-name">在庫管理</span>
      <span class="tm-cat-sub">持ち出し・返却・使用履歴</span>
    </button>`;

    if (navReorder && kids.length > 0) {
      choiceDock.className = 'tm-choicedock tm-sortmode';
      choiceDock.innerHTML =
        `<div class="tm-sort-hint">&#8645; 並べ替え中：<strong>長押し</strong>（またはハンドル&#8942;&#8942;をドラッグ）や ▲▼ でこの階層の順番を変更できます。</div>` +
        kids.map((c, i) => sortRow(c, i, kids.length)).join('');
    } else if (atRoot) {
      choiceDock.className = 'tm-choicedock tm-cat-grid';
      choiceDock.innerHTML = kids.map((c, i) => categoryTile(c, i)).join('') + invTile;
    } else if (kids.length > 0) {
      choiceDock.className = 'tm-choicedock';
      choiceDock.innerHTML = `<div class="tm-choice-label">${depthLabel(navPath.length)}を選択</div>` +
        kids.map((c) => choiceRow(c)).join('');
    } else {
      choiceDock.className = 'tm-choicedock';
      choiceDock.innerHTML = '';
    }

    // 直接追加ボタン ＋ 簡易並べ替えの切替
    const addParent = atRoot ? '' : curNode.id;
    let addHtml = `<button class="tm-addhere" data-add="${addParent}" type="button">&#43; ${depthLabel(navPath.length)}を追加</button>`;
    if (canReorder) {
      addHtml += `<button class="tm-sorttoggle ${navReorder ? 'is-active' : ''}" data-sorttoggle type="button">${navReorder ? '&#10003; 並べ替えを終了' : '&#8645; 並べ替え'}</button>`;
    }
    navAddDock.innerHTML = addHtml;

    backBtn.disabled = atRoot;
    if (backBtnTop) backBtnTop.disabled = atRoot;
    remainHint.textContent = '';
  }

  /* ---- 端末(Android等)の戻るで1つ前に戻るための履歴連携（単一センチネル方式） ----
     「戻るで閉じたい状態」がある間だけ、履歴マーカーを常に1個だけ積む。
     OSの戻る(popstate)で1階層だけ閉じ、まだ閉じたい状態が残っていれば積み直す。
     history.go(-n) を使わないので、ブラウザ毎の popstate 発火数の違いに影響されない
     （従来は go(-n) の発火数を数え違え、以降の「戻る」が効かなくなる不具合があった）。 */
  let trapped = false;    // センチネルを積んでいるか
  let absorbPop = false;  // 直後の1回の popstate を無視（プログラム的 back 用）
  try { history.replaceState({ cbcBase: 1 }, ''); } catch (_) {}
  function needTrap() { return navPath.length > 0 || !editView.hidden || navReorder || invOpen || searchOpen || updatesOpen || helpOpen || listOpen || aiOpen; }
  function syncTrap() {
    if (needTrap()) {
      if (!trapped) { try { history.pushState({ cbc: 1 }, ''); trapped = true; } catch (_) {} }
    } else if (trapped) {
      absorbPop = true; trapped = false;
      try { history.back(); } catch (_) { absorbPop = false; }
    }
  }

  async function navGoto(id) {
    const node = findNode(id);
    if (isLocked(node)) {
      if (!(await ensureUnlocked(node))) return;
    }
    navReorder = false;
    navPath.push(id);
    renderNav();
    syncTrap();
  }
  function navBack() {
    navReorder = false;
    navPath.pop();
    renderNav();
    syncTrap();
  }
  function navTo(id) {
    // jump to a specific ancestor in the path
    navReorder = false;
    const idx = navPath.indexOf(id);
    if (idx >= 0) navPath = navPath.slice(0, idx + 1);
    renderNav();
    syncTrap();
  }
  // 任意の項目（深い階層でも）へ、ルートからのフルパスで移動（ピン留めから使用）
  async function navGotoId(id) {
    const path = findPath(id); // ルート→対象 のノード配列
    if (!path) { syncMsg('項目が見つかりません（削除された可能性があります）', true); return; }
    navReorder = false;
    const newPath = [];
    for (const n of path) {
      if (isLocked(n)) { if (!(await ensureUnlocked(n))) return; }
      newPath.push(n.id);
    }
    navPath = newPath;
    renderNav();
    syncTrap();
  }
  function navRestart() {
    navReorder = false;
    navPath = [];
    renderNav();
    syncTrap();
  }

  // 端末の戻るボタン（popstate）: このpopstateでセンチネルが1つ消費されている
  window.addEventListener('popstate', () => {
    if (absorbPop) { absorbPop = false; return; } // プログラム的backの分は無視
    trapped = false;
    const dlg = document.querySelector('dialog[open]');
    if (dlg) { dlg.close(); }                                  // 開いているダイアログを閉じる
    else if (!editView.hidden) { setMode('nav'); }             // 編集モード→案内モード
    else if (invOpen) { closeInventory(); }                    // 在庫管理→大項目へ
    else if (navReorder) { navReorder = false; renderNav(); }  // 並べ替えを終了
    else if (navPath.length > 0) { navPath.pop(); renderNav(); } // 案内を1つ戻る
    // まだ閉じたい状態が残っていればセンチネルを積み直す（無ければ次の戻るで離脱）
    syncTrap();
  });

  async function navReorderMove(id, dir) {
    try {
      await opMove(id, dir);
      renderNav();
    } catch (err) { syncMsg('並べ替えに失敗：' + err.message, true); }
  }
  choiceDock.addEventListener('click', (e) => {
    if (Date.now() < suppressClickUntil) return; // 直前のドラッグのクリックを無視
    if (e.target.closest('[data-inv]')) { openInventory(); return; }
    const up = e.target.closest('[data-navup]');
    if (up) { if (!up.disabled) navReorderMove(up.dataset.navup, -1); return; }
    const down = e.target.closest('[data-navdown]');
    if (down) { if (!down.disabled) navReorderMove(down.dataset.navdown, 1); return; }
    const btn = e.target.closest('[data-goto]');
    if (btn) navGoto(btn.dataset.goto);
  });

  /* ---- 案内モードの簡易並べ替え：長押し／ハンドルのドラッグ（編集モードと同じ操作感） ---- */
  let sortDrag = null;
  function clearSortMarks() {
    choiceDock.querySelectorAll('.drop-before,.drop-after')
      .forEach((el) => el.classList.remove('drop-before', 'drop-after'));
  }
  function sortBeginDrag() {
    if (!sortDrag) return;
    sortDrag.active = true;
    sortDrag.row.classList.add('dragging');
    document.body.classList.add('tm-dragging');
    try { sortDrag.row.setPointerCapture(sortDrag.pointerId); } catch (_) {}
    if (navigator.vibrate) { try { navigator.vibrate(12); } catch (_) {} }
    sortUpdateTarget(sortDrag.startX, sortDrag.startY);
  }
  function sortUpdateTarget(x, y) {
    clearSortMarks();
    sortDrag.target = null;
    const el = document.elementFromPoint(x, y);
    const row = el && el.closest ? el.closest('.tm-sortrow') : null;
    if (!row || row === sortDrag.row) return;
    const rect = row.getBoundingClientRect();
    const pos = (y - rect.top) < rect.height / 2 ? 'before' : 'after';
    row.classList.add(pos === 'before' ? 'drop-before' : 'drop-after');
    sortDrag.target = { tid: row.dataset.sortid, pos };
  }
  function sortCancelDrag() {
    if (!sortDrag) return;
    if (sortDrag.timer) clearTimeout(sortDrag.timer);
    if (sortDrag.row) sortDrag.row.classList.remove('dragging');
    try { sortDrag.row.releasePointerCapture(sortDrag.pointerId); } catch (_) {}
    clearSortMarks();
    document.body.classList.remove('tm-dragging');
    sortDrag = null;
  }
  function sortFinishDrag() {
    if (!sortDrag) return;
    const wasActive = sortDrag.active;
    const info = sortDrag.target;
    const draggedId = sortDrag.id;
    if (sortDrag.timer) clearTimeout(sortDrag.timer);
    if (sortDrag.row) sortDrag.row.classList.remove('dragging');
    clearSortMarks();
    document.body.classList.remove('tm-dragging');
    sortDrag = null;
    if (!wasActive) return;              // タップ扱い
    suppressClickUntil = Date.now() + 500;
    if (!info || info.tid === draggedId) return;
    const parentId = navPath.length ? navPath[navPath.length - 1] : '';
    const order = currentChildren().map((k) => k.id);
    const tIdx = order.indexOf(info.tid);
    let beforeId = info.pos === 'before' ? info.tid : (tIdx + 1 < order.length ? order[tIdx + 1] : null);
    if (beforeId === draggedId) return;  // 位置変化なし
    (async () => {
      try { await opReparent(draggedId, parentId, beforeId); renderNav(); }
      catch (err) { syncMsg('並べ替えに失敗：' + err.message, true); }
    })();
  }
  choiceDock.addEventListener('pointerdown', (e) => {
    if (!navReorder) return;
    if (e.button != null && e.button > 0) return;
    const row = e.target.closest('.tm-sortrow');
    if (!row) return;
    const onHandle = !!e.target.closest('.tm-drag-handle');
    if (!onHandle && e.target.closest('.tm-iconbtn, button')) return; // ▲▼等では開始しない
    sortDrag = { id: row.dataset.sortid, pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, active: false, target: null, timer: null, row };
    if (onHandle) sortBeginDrag();
    else sortDrag.timer = setTimeout(sortBeginDrag, LONGPRESS_MS);
  });
  choiceDock.addEventListener('pointermove', (e) => {
    if (!sortDrag) return;
    if (!sortDrag.active) {
      const dx = e.clientX - sortDrag.startX, dy = e.clientY - sortDrag.startY;
      if (Math.abs(dx) > MOVE_TOL || Math.abs(dy) > MOVE_TOL) sortCancelDrag();
      return;
    }
    e.preventDefault();
    autoScroll(e.clientY);
    sortUpdateTarget(e.clientX, e.clientY);
  });
  choiceDock.addEventListener('pointerup', sortFinishDrag);
  choiceDock.addEventListener('pointercancel', sortCancelDrag);
  choiceDock.addEventListener('touchmove', (e) => { if (sortDrag && sortDrag.active) e.preventDefault(); }, { passive: false });
  // 本文の画像：名称をキャプション表示し、タップで拡大（ライトボックス）
  function enhanceContentImages() {
    chatLog.querySelectorAll('.tm-content-body img').forEach((img) => {
      if (img.dataset.enh) return;
      img.dataset.enh = '1';
      img.classList.add('tm-zoomable');
      const cap = (img.getAttribute('alt') || '').trim();
      if (cap) {
        const fig = document.createElement('figure');
        fig.className = 'tm-figure';
        img.replaceWith(fig);
        fig.appendChild(img);
        const fc = document.createElement('figcaption');
        fc.className = 'tm-figcaption';
        fc.textContent = cap;
        fig.appendChild(fc);
      }
    });
  }
  chatLog.addEventListener('click', (e) => {
    const zi = e.target.closest('.tm-content-body img.tm-zoomable');
    if (zi) { openLightbox(zi.getAttribute('src'), zi.getAttribute('alt')); return; }
    if (e.target.closest('#navRetryBtn')) { retryConnect(); return; }
    if (e.target.closest('#heroUpdate')) { openUpdates(); return; }
    // ピン留めを外す（タイル内の×。移動より先に判定）
    const un = e.target.closest('[data-unpin]');
    if (un) { e.stopPropagation(); togglePin(un.dataset.unpin); renderNav(); return; }
    // ピン留めタイルをタップ → その項目へ移動
    const pg = e.target.closest('[data-pingoto]');
    if (pg) { navGotoId(pg.dataset.pingoto); return; }
    // 現在の項目をピン留め／解除
    const pt = e.target.closest('[data-pintoggle]');
    if (pt) { togglePin(pt.dataset.pintoggle); renderNav(); return; }
    // 記入内容の一覧（別ポップアップ）を開く
    if (e.target.closest('[data-listfields]')) { openFieldList(); return; }
    // AI要約
    const asb = e.target.closest('[data-aisummary]');
    if (asb) { openAiSummary(asb.dataset.aisummary); return; }
    const eb = e.target.closest('[data-editthis]');
    if (eb) { // 案内モードから直接編集
      const node = findNode(eb.dataset.editthis);
      if (isLocked(node)) { ensureUnlocked(node).then((ok) => { if (ok) openNodeDialog(eb.dataset.editthis); }); }
      else openNodeDialog(eb.dataset.editthis);
    }
  });
  async function retryConnect() {
    await detectServer();
    if (serverMode()) { try { await reloadFromServer(); } catch (e) {} }
    renderNav();
  }

  /* ---------- 画像の拡大表示（ライトボックス） ---------- */
  const lightbox = $('#lightbox');
  function openLightbox(src, caption) {
    if (!lightbox || !src) return;
    const img = $('#lbImg'), cap = $('#lbCap');
    img.src = src;
    img.alt = caption || '';
    const c = (caption || '').trim();
    cap.textContent = c;
    cap.hidden = !c;
    lightbox.showModal();
    syncTrap();
  }
  if (lightbox) {
    lightbox.addEventListener('close', () => syncTrap());
    // 画像以外（背景・閉じるボタン）をクリックで閉じる
    lightbox.addEventListener('click', (e) => {
      if (!e.target.closest('#lbImg')) lightbox.close();
    });
    $('#lbClose').addEventListener('click', () => lightbox.close());
  }
  navAddDock.addEventListener('click', (e) => {
    if (e.target.closest('[data-sorttoggle]')) { navReorder = !navReorder; renderNav(); syncTrap(); return; }
    const el = e.target.closest('[data-add]');
    if (el) openNodeDialog(null, el.dataset.add || null); // 案内モードから直接追加
  });
  breadcrumbBar.addEventListener('click', (e) => {
    if (e.target.closest('[data-crumb-home]')) return navRestart();
    const c = e.target.closest('[data-crumb]');
    if (c) navTo(c.dataset.crumb);
  });
  backBtn.addEventListener('click', () => history.back()); // 端末の戻ると同じ挙動
  restartBtn.addEventListener('click', navRestart);
  // 上部にも同じ「一つ戻る／最初から」を配置
  if (backBtnTop) backBtnTop.addEventListener('click', () => history.back());
  if (restartBtnTop) restartBtnTop.addEventListener('click', navRestart);

  /* ============================================================
     EDIT MODE
     ============================================================ */
  const editTree = $('#editTree');
  const saveStatus = $('#saveStatus');

  const authorInput = $('#authorInput');
  authorInput.value = localStorage.getItem(AUTHOR_KEY) || '';
  authorInput.addEventListener('input', () => localStorage.setItem(AUTHOR_KEY, authorInput.value.trim()));

  function flashSaved(msg = '保存しました') {
    saveStatus.textContent = '✓ ' + msg;
    clearTimeout(flashSaved._t);
    flashSaved._t = setTimeout(() => { saveStatus.textContent = ''; }, 2200);
  }

  function renderEdit() {
    refreshUpdatesBadge();
    // サーバー未接続時は編集画面も出さない（ローカルの古いデータを見せない）
    if (gateReason()) { editTree.innerHTML = ''; updateAuthGate(); return; }
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

    const flag = mediaFlag(node.body);
    const metaText = [flag, nodeMetaText(node)].filter(Boolean).join(' ');

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
        <div class="tm-treenode-row" data-id="${node.id}">
          <span class="tm-drag-handle" title="長押し／ハンドルをドラッグで移動（並べ替え・階層変更）">&#8942;&#8942;</span>
          ${toggle}
          <button class="tm-treenode-main" data-edit="${node.id}" title="クリックで内容を編集">
            <span class="tm-treenode-title">${node.locked ? '<span class="tm-lock-badge">🔒</span> ' : ''}${esc(node.title)}</span>
            ${metaText ? `<span class="tm-treenode-meta">${esc(metaText)}</span>` : ''}
          </button>
          ${badge}
          <button class="tm-iconbtn" data-up="${node.id}" title="上へ" ${index === 0 ? 'disabled' : ''}>&#9650;</button>
          <button class="tm-iconbtn" data-down="${node.id}" title="下へ" ${index === siblingCount - 1 ? 'disabled' : ''}>&#9660;</button>
          <button class="tm-iconbtn tm-iconbtn-edit" data-edit="${node.id}" title="編集">&#9998;</button>
          <button class="tm-iconbtn tm-iconbtn-danger" data-del="${node.id}" title="削除">&#128465;</button>
        </div>
        ${childrenHtml}
      </div>`;
  }

  editTree.addEventListener('click', (e) => {
    if (Date.now() < suppressClickUntil) return; // 直前のドラッグ操作のクリックを無視
    const t = e.target.closest('button');
    if (!t) return;
    const d = t.dataset;
    if (d.toggle) {
      const node = findNode(d.toggle);
      if (isLocked(node)) { ensureUnlocked(node).then((ok) => { if (ok) { openNodes.add(d.toggle); persistOpen(); renderEdit(); } }); return; }
      if (openNodes.has(d.toggle)) openNodes.delete(d.toggle);
      else openNodes.add(d.toggle);
      persistOpen();
      renderEdit();
    } else if (d.edit) {
      const node = findNode(d.edit);
      if (isLocked(node)) { ensureUnlocked(node).then((ok) => { if (ok) openNodeDialog(d.edit); }); return; }
      openNodeDialog(d.edit);
    } else if (d.addchild) {
      const node = findNode(d.addchild);
      if (isLocked(node)) { ensureUnlocked(node).then((ok) => { if (ok) openNodeDialog(null, d.addchild); }); return; }
      openNodeDialog(null, d.addchild);
    } else if (d.del) {
      confirmDelete(d.del);
    } else if (d.up) {
      moveNode(d.up, -1);
    } else if (d.down) {
      moveNode(d.down, 1);
    }
  });

  async function moveNode(id, dir) {
    try {
      await opMove(id, dir);
      renderEdit();
      flashSaved('並び順を変更しました');
    } catch (e) { flashSaved('変更に失敗：' + e.message); }
  }

  /* ---------- ドラッグ&ドロップ（並べ替え・階層変更） ---------- */
  function findParentId(id) {
    const p = findPath(id);
    return (p && p.length >= 2) ? p[p.length - 2].id : '';
  }
  function nextSiblingId(id) {
    const p = findPath(id);
    const arr = (p && p.length >= 2) ? p[p.length - 2].children : tree;
    const i = arr.findIndex((x) => x.id === id);
    return (i >= 0 && i + 1 < arr.length) ? arr[i + 1].id : null;
  }
  // maybeChild が ancestorId のサブツリー内にあるか
  function isInSubtree(maybeChild, ancestorId) {
    const anc = findNode(ancestorId);
    if (!anc) return false;
    const stack = [...(anc.children || [])];
    while (stack.length) {
      const n = stack.pop();
      if (n.id === maybeChild) return true;
      if (n.children) stack.push(...n.children);
    }
    return false;
  }
  function clearDropMarks() {
    editTree.querySelectorAll('.drop-before,.drop-after,.drop-inside')
      .forEach((el) => el.classList.remove('drop-before', 'drop-after', 'drop-inside'));
    editTree.classList.remove('drop-root');
  }

  /* ポインタ操作：タッチは「長押し」、マウスはハンドルのドラッグで移動 */
  const LONGPRESS_MS = 350;
  const MOVE_TOL = 8;
  let drag = null;                // 進行中のドラッグ状態
  let suppressClickUntil = 0;     // ドラッグ直後のクリック抑制

  function beginDrag() {
    if (!drag) return;
    drag.active = true;
    drag.row.classList.add('dragging');
    document.body.classList.add('tm-dragging');
    try { drag.row.setPointerCapture(drag.pointerId); } catch (_) {}
    if (navigator.vibrate) { try { navigator.vibrate(12); } catch (_) {} }
    updateDropTarget(drag.startX, drag.startY);
  }
  function updateDropTarget(x, y) {
    clearDropMarks();
    drag.target = null;
    const el = document.elementFromPoint(x, y);
    const row = el && el.closest ? el.closest('.tm-treenode-row') : null;
    if (!row) { editTree.classList.add('drop-root'); drag.target = { root: true }; return; }
    const tid = row.dataset.id;
    if (tid === drag.id || isInSubtree(tid, drag.id)) return;
    const rect = row.getBoundingClientRect();
    const ry = y - rect.top;
    const pos = ry < rect.height * 0.30 ? 'before' : (ry > rect.height * 0.70 ? 'after' : 'inside');
    row.classList.add(pos === 'before' ? 'drop-before' : pos === 'after' ? 'drop-after' : 'drop-inside');
    drag.target = { tid, pos };
  }
  function autoScroll(y) {
    const m = 64;
    if (y < m) window.scrollBy(0, -14);
    else if (y > window.innerHeight - m) window.scrollBy(0, 14);
  }
  function cancelDrag() {
    if (!drag) return;
    if (drag.timer) clearTimeout(drag.timer);
    if (drag.row) drag.row.classList.remove('dragging');
    try { drag.row.releasePointerCapture(drag.pointerId); } catch (_) {}
    clearDropMarks();
    document.body.classList.remove('tm-dragging');
    drag = null;
  }
  function finishDrag() {
    if (!drag) return;
    const wasActive = drag.active;
    const info = drag.target;
    const draggedId = drag.id;
    if (drag.timer) clearTimeout(drag.timer);
    if (drag.row) drag.row.classList.remove('dragging');
    clearDropMarks();
    document.body.classList.remove('tm-dragging');
    drag = null;
    if (!wasActive) return;             // タップ扱い（クリックへ）
    suppressClickUntil = Date.now() + 500;
    if (!info) return;                  // 移動先なし
    let parentId = '', beforeId = null;
    if (!info.root) {
      if (info.tid === draggedId || isInSubtree(info.tid, draggedId)) return;
      if (info.pos === 'inside') { parentId = info.tid; beforeId = null; openNodes.add(info.tid); persistOpen(); }
      else { parentId = findParentId(info.tid) || ''; beforeId = info.pos === 'before' ? info.tid : nextSiblingId(info.tid); }
    }
    (async () => {
      try { await opReparent(draggedId, parentId, beforeId); renderEdit(); flashSaved('移動しました'); }
      catch (err) { flashSaved('移動に失敗：' + err.message); }
    })();
  }

  editTree.addEventListener('pointerdown', (e) => {
    if (e.button != null && e.button > 0) return;
    const row = e.target.closest('.tm-treenode-row');
    if (!row) return;
    const onHandle = !!e.target.closest('.tm-drag-handle');
    // アイコン操作・入力欄などでは開始しない（ハンドルは常に開始）
    if (!onHandle && e.target.closest('.tm-treenode-toggle, .tm-iconbtn, .tm-treenode-addchild, input, textarea, select, a')) return;
    drag = { id: row.dataset.id, pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, active: false, target: null, timer: null, row };
    if (onHandle) beginDrag();
    else drag.timer = setTimeout(beginDrag, LONGPRESS_MS);
  });
  editTree.addEventListener('pointermove', (e) => {
    if (!drag) return;
    if (!drag.active) {
      const dx = e.clientX - drag.startX, dy = e.clientY - drag.startY;
      if (Math.abs(dx) > MOVE_TOL || Math.abs(dy) > MOVE_TOL) cancelDrag(); // 長押し前に動いた＝スクロール
      return;
    }
    e.preventDefault();
    autoScroll(e.clientY);
    updateDropTarget(e.clientX, e.clientY);
  });
  editTree.addEventListener('pointerup', finishDrag);
  editTree.addEventListener('pointercancel', cancelDrag);
  // ドラッグ中はタッチスクロールを抑止
  editTree.addEventListener('touchmove', (e) => { if (drag && drag.active) e.preventDefault(); }, { passive: false });

  $('#addRootBtn').addEventListener('click', () => openNodeDialog(null, null));

  /* ---------- node dialog ---------- */
  const nodeDialog = $('#nodeDialog');
  const nodeForm = $('#nodeForm');
  const nodeTitleInput = $('#nodeTitleInput');
  const nodeAuthorInput = $('#nodeAuthorInput');
  const nodeBodyEditor = $('#nodeBodyEditor');
  const nodeDialogTitle = $('#nodeDialogTitle');
  const nodeLockChk = $('#nodeLockChk');
  const nodeLockPw = $('#nodeLockPw');
  const nodeLockHint = $('#nodeLockHint');
  if (nodeLockChk) {
    nodeLockChk.addEventListener('change', () => {
      const on = nodeLockChk.checked;
      if (nodeLockPw) nodeLockPw.hidden = !on;
      if (nodeLockHint) nodeLockHint.hidden = !on;
      if (on && nodeLockPw) nodeLockPw.focus();
    });
  }
  const nodeDateField = $('#nodeDateField');
  const nodeDateChk = $('#nodeDateChk');
  const nodeDateInput = $('#nodeDateInput');
  const nodeDateHint = $('#nodeDateHint');

  /* ---------- 本文に足す「記入フォーム（入力欄）」 ----------
     説明中のカーソル位置に ラベル＋白い入力欄（1行/複数行）や チェック欄 を差し込める。
     入力欄と記入内容は本文の一部として保存され、閲覧画面や再編集時にも表示される。
     編集中は「編集ウィジェット（ラベル入力＋×削除）」、保存/表示時は「素の入力欄」に変換する。 */
  const delBtnHtml = '<button type="button" class="tm-ff-del" title="この入力欄を削除" contenteditable="false">&#10005;</button>';
  // 時刻プルダウン（時00-23：分00-59）。マウスだけで 00:00〜23:59 を選べる。value は "HH:MM"。
  function timeSelectsHtml(value) {
    const mm = /^(\d{1,2}):(\d{1,2})$/.exec(value || '');
    const hSel = mm ? Math.min(23, parseInt(mm[1], 10)) : -1;
    const mSel = mm ? Math.min(59, parseInt(mm[2], 10)) : -1;
    let h = '<select class="tm-ff-hour tm-ff-timesel" aria-label="時"><option value="">--</option>';
    for (let i = 0; i < 24; i++) { const s = String(i).padStart(2, '0'); h += '<option value="' + s + '"' + (i === hSel ? ' selected' : '') + '>' + s + '</option>'; }
    h += '</select>';
    let m = '<select class="tm-ff-min tm-ff-timesel" aria-label="分"><option value="">--</option>';
    for (let i = 0; i < 60; i++) { const s = String(i).padStart(2, '0'); m += '<option value="' + s + '"' + (i === mSel ? ' selected' : '') + '>' + s + '</option>'; }
    m += '</select>';
    return '<span class="tm-ff-time">' + h + '<span class="tm-ff-timecolon">:</span>' + m + '<span class="tm-ff-timeunit">（時 : 分）</span></span>';
  }
  function editorFieldHtml(kind) {
    if (kind === 'check') {
      return '<div class="tm-formfield tm-ff-edit" data-ff="check" contenteditable="false">'
        + '<input type="checkbox" class="tm-ff-cb">'
        + '<input type="text" class="tm-ff-label" placeholder="チェック項目（例：確認した）">'
        + delBtnHtml + '</div>';
    }
    const head = '<div class="tm-ff-head">'
      + '<input type="text" class="tm-ff-label" placeholder="ラベル（例：お名前）">' + delBtnHtml + '</div>';
    if (kind === 'time') {
      const th = '<div class="tm-ff-head"><input type="text" class="tm-ff-label" placeholder="ラベル（例：開始時刻）">' + delBtnHtml + '</div>';
      return '<div class="tm-formfield tm-ff-edit" data-ff="time" contenteditable="false">' + th + timeSelectsHtml('') + '</div>';
    }
    if (kind === 'textarea') {
      return '<div class="tm-formfield tm-ff-edit" data-ff="textarea" contenteditable="false">' + head
        + '<textarea class="tm-ff-input" rows="4"></textarea></div>';
    }
    return '<div class="tm-formfield tm-ff-edit" data-ff="text" contenteditable="false">' + head
      + '<input type="text" class="tm-ff-input"></div>';
  }
  // 保存/表示された時刻フィールド（data-value のみ）に、時・分のプルダウンを組み立てる
  function buildTimeSelects(root) {
    if (!root) return;
    root.querySelectorAll('.tm-formfield[data-ff="time"]').forEach((ff) => {
      if (ff.querySelector('.tm-ff-time')) return; // 既に構築済み（編集ウィジェット等）
      ff.insertAdjacentHTML('beforeend', timeSelectsHtml(ff.getAttribute('data-value') || ''));
    });
  }
  // 本文内（フィールド外）の直近キャレット位置を覚えておく（白い入力欄にフォーカス中でも本文へ差し込めるように）
  let lastEditorRange = null;
  function inFormField(node) {
    const host = node && (node.nodeType === 1 ? node : node.parentNode);
    return !!(host && host.closest && host.closest('.tm-formfield'));
  }
  function saveEditorRange() {
    const sel = window.getSelection();
    if (sel && sel.rangeCount) {
      const r = sel.getRangeAt(0);
      if (nodeBodyEditor.contains(r.commonAncestorContainer) && !inFormField(r.commonAncestorContainer)) lastEditorRange = r.cloneRange();
    }
  }
  nodeBodyEditor.addEventListener('keyup', saveEditorRange);
  nodeBodyEditor.addEventListener('mouseup', saveEditorRange);
  function insertFormField(kind) {
    nodeBodyEditor.focus();
    const sel = window.getSelection();
    // 現在のキャレットが本文内（フィールド外）ならそこ、無ければ記憶した位置、それも無ければ末尾へ
    let range = null;
    if (sel && sel.rangeCount) {
      const cur = sel.getRangeAt(0);
      if (nodeBodyEditor.contains(cur.commonAncestorContainer) && !inFormField(cur.commonAncestorContainer)) range = cur;
    }
    if (!range && lastEditorRange && nodeBodyEditor.contains(lastEditorRange.commonAncestorContainer)) range = lastEditorRange;
    if (!range) { range = document.createRange(); range.selectNodeContents(nodeBodyEditor); range.collapse(false); }
    sel.removeAllRanges(); sel.addRange(range);
    insertEditorHtml(editorFieldHtml(kind) + '<p><br></p>');
    // 差し込み直後のキャレット（挿入した欄の直後）を記憶 → 連続追加は上から順に並ぶ
    saveEditorRange();
    // 追加した欄のラベルにフォーカス
    const fields = nodeBodyEditor.querySelectorAll('.tm-formfield');
    const last = fields[fields.length - 1];
    if (last) { const lbl = last.querySelector('.tm-ff-label'); if (lbl) lbl.focus(); }
  }
  { const fb = $('#fieldBar'); if (fb) {
    fb.addEventListener('mousedown', (e) => { if (e.target.closest('button')) e.preventDefault(); });
    fb.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-field]');
      if (!btn) return;
      e.preventDefault();
      insertFormField(btn.dataset.field);
    });
  } }
  nodeBodyEditor.addEventListener('click', (e) => {
    const del = e.target.closest('.tm-ff-del');
    if (del) { e.preventDefault(); const ff = del.closest('.tm-formfield'); if (ff) ff.remove(); }
  });
  nodeBodyEditor.addEventListener('keydown', (e) => {
    // 1行の入力欄・ラベル・時刻プルダウンで Enter を押しても項目が保存されないようにする（複数行は改行を許可）
    if (e.key === 'Enter' && e.target.matches('input.tm-ff-input, input.tm-ff-label, select.tm-ff-timesel')) e.preventDefault();
  });
  // 保存/表示用の「素の入力欄」HTML（現在の記入値を value/checked/本文に焼き込む）
  function fieldCanonicalHtml(ff) {
    const kind = ff.getAttribute('data-ff') || 'text';
    const labelEl = ff.querySelector('.tm-ff-label');
    const label = labelEl ? (labelEl.tagName === 'INPUT' ? labelEl.value : labelEl.textContent) : '';
    if (kind === 'check') {
      const cb = ff.querySelector('.tm-ff-cb');
      const checked = cb && cb.checked ? ' checked' : '';
      return '<div class="tm-formfield" data-ff="check"><label class="tm-ff-label">'
        + '<input class="tm-ff-cb" type="checkbox"' + checked + '>' + esc(label) + '</label></div>';
    }
    if (kind === 'time') {
      const hs = ff.querySelector('.tm-ff-hour'), ms = ff.querySelector('.tm-ff-min');
      const hv = hs && hs.value ? hs.value : '', mv = ms && ms.value ? ms.value : '';
      const dv = (hv && mv) ? ' data-value="' + esc(hv + ':' + mv) + '"' : '';
      return '<div class="tm-formfield" data-ff="time"' + dv + '><label class="tm-ff-label">' + esc(label) + '</label></div>';
    }
    const valEl = ff.querySelector('.tm-ff-input');
    if (kind === 'textarea') {
      const rows = (valEl && valEl.rows) ? valEl.rows : 4;
      const val = valEl ? valEl.value : '';
      return '<div class="tm-formfield" data-ff="textarea"><label class="tm-ff-label">' + esc(label)
        + '</label><textarea class="tm-ff-input" rows="' + rows + '">' + esc(val) + '</textarea></div>';
    }
    const val = valEl ? valEl.value : '';
    return '<div class="tm-formfield" data-ff="text"><label class="tm-ff-label">' + esc(label)
      + '</label><input class="tm-ff-input" type="text" value="' + esc(val) + '"></div>';
  }
  // 保存用の本文（編集ウィジェットを「素の入力欄＋現在値」に変換して保存する）
  function bodyForSave() {
    const live = nodeBodyEditor;
    const fields = Array.from(live.querySelectorAll('.tm-formfield'));
    const canon = fields.map(fieldCanonicalHtml);
    // 各フィールドを一意トークンのテキストに一時置換 → innerHTML 取得 → 元に戻す
    const tokens = fields.map((ff, i) => { const t = document.createTextNode('@@CBCFFPH' + i + '@@'); ff.replaceWith(t); return t; });
    let html = live.innerHTML;
    tokens.forEach((t, i) => t.replaceWith(fields[i]));
    html = html.replace(/@@CBCFFPH(\d+)@@/g, (m, i) => canon[Number(i)] || '');
    // 入力欄差し込み時に付く末尾の空段落を軽く掃除
    html = html.replace(/(?:<p><br\s*\/?><\/p>|<div><br\s*\/?><\/div>|<br\s*\/?>)+\s*$/i, '');
    return normalizeBody(html);
  }
  // 保存済みの「素の入力欄」を、編集用ウィジェット（ラベル入力＋×削除）に復元する
  function hydrateEditorFields() {
    nodeBodyEditor.querySelectorAll('.tm-formfield').forEach((ff) => {
      if (ff.classList.contains('tm-ff-edit')) return;
      const kind = ff.getAttribute('data-ff')
        || (ff.querySelector('.tm-ff-cb') ? 'check' : (ff.querySelector('textarea') ? 'textarea' : 'text'));
      const labelEl = ff.querySelector('.tm-ff-label');
      const label = labelEl ? labelEl.textContent.trim() : '';
      if (kind === 'check') {
        const cb = ff.querySelector('.tm-ff-cb');
        const checked = !!(cb && (cb.checked || cb.hasAttribute('checked')));
        ff.className = 'tm-formfield tm-ff-edit';
        ff.setAttribute('data-ff', 'check');
        ff.setAttribute('contenteditable', 'false');
        ff.innerHTML = '<input type="checkbox" class="tm-ff-cb">'
          + '<input type="text" class="tm-ff-label" placeholder="チェック項目">' + delBtnHtml;
        ff.querySelector('.tm-ff-cb').checked = checked;
        ff.querySelector('.tm-ff-label').value = label;
      } else if (kind === 'time') {
        const val = ff.getAttribute('data-value') || '';
        ff.className = 'tm-formfield tm-ff-edit';
        ff.setAttribute('data-ff', 'time');
        ff.setAttribute('contenteditable', 'false');
        ff.innerHTML = '<div class="tm-ff-head"><input type="text" class="tm-ff-label" placeholder="ラベル（例：開始時刻）">' + delBtnHtml + '</div>' + timeSelectsHtml(val);
        ff.querySelector('.tm-ff-label').value = label;
      } else {
        const valEl = ff.querySelector('.tm-ff-input');
        const isTextarea = kind === 'textarea' || (valEl && valEl.tagName === 'TEXTAREA');
        const val = valEl ? (valEl.tagName === 'TEXTAREA' ? valEl.textContent : (valEl.getAttribute('value') || '')) : '';
        const head = '<div class="tm-ff-head"><input type="text" class="tm-ff-label" placeholder="ラベル（例：お名前）">' + delBtnHtml + '</div>';
        ff.className = 'tm-formfield tm-ff-edit';
        ff.setAttribute('contenteditable', 'false');
        if (isTextarea) {
          ff.setAttribute('data-ff', 'textarea');
          ff.innerHTML = head + '<textarea class="tm-ff-input" rows="4"></textarea>';
          ff.querySelector('textarea').value = val;
        } else {
          ff.setAttribute('data-ff', 'text');
          ff.innerHTML = head + '<input type="text" class="tm-ff-input">';
          ff.querySelector('.tm-ff-input').value = val;
        }
        ff.querySelector('.tm-ff-label').value = label;
      }
    });
  }
  // ms → datetime-local の値（YYYY-MM-DDTHH:MM）
  function msToLocalInput(ms) {
    const d = new Date(Number(ms) || Date.now());
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  if (nodeDateChk) {
    nodeDateChk.addEventListener('change', () => {
      const on = nodeDateChk.checked;
      if (nodeDateInput) nodeDateInput.hidden = !on;
      if (nodeDateHint) nodeDateHint.hidden = !on;
    });
  }

  // エディタのHTMLをサニタイズし、空なら '' を返す
  function normalizeBody(html) {
    const clean = sanitizeHtml(html);
    const tmp = document.createElement('div');
    tmp.innerHTML = clean;
    if (!tmp.textContent.trim() && !tmp.querySelector('img, a, input, textarea, .tm-formfield')) return '';
    return clean;
  }
  function insertEditorHtml(html) {
    nodeBodyEditor.focus();
    document.execCommand('insertHTML', false, html);
  }
  // 添付（画像・ファイル）は本文の末尾に追加する
  function appendEditorHtml(html) {
    nodeBodyEditor.insertAdjacentHTML('beforeend', html);
    nodeBodyEditor.scrollTop = nodeBodyEditor.scrollHeight;
  }
  // 拡張子に応じた小さなファイルアイコン（絵文字）
  function fileIcon(name) {
    const ext = String(name || '').split('.').pop().toLowerCase();
    if (ext === 'pdf') return '📕';
    if (['xls', 'xlsx', 'xlsm', 'csv'].includes(ext)) return '📊';
    if (['doc', 'docx'].includes(ext)) return '📘';
    if (['ppt', 'pptx'].includes(ext)) return '📙';
    if (['zip', 'rar', '7z'].includes(ext)) return '📦';
    if (['txt', 'md'].includes(ext)) return '📃';
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext)) return '🖼️';
    return '📎';
  }
  let dialogTarget = null; // { mode: 'edit'|'add', id, parentId }

  const nodeMetaEl = $('#nodeMeta');
  function openNodeDialog(editId, parentId) {
    let metaStr = '';
    if (editId) {
      const node = findNode(editId);
      if (!node) return;
      dialogTarget = { mode: 'edit', id: editId };
      nodeDialogTitle.textContent = '項目を編集';
      nodeTitleInput.value = node.title;
      nodeBodyEditor.innerHTML = node.body ? renderBody(node.body) : ''; // レガシーは自動でHTML化
      const upd = fmtTime(node.updated_at);
      const crt = fmtTime(node.created_at);
      const bits = [];
      if (node.updated_by || upd) bits.push(`最終更新: ${node.updated_by || '—'}${upd ? ' · ' + upd : ''}`);
      if (node.created_by || crt) bits.push(`作成: ${node.created_by || '—'}${crt ? ' · ' + crt : ''}`);
      metaStr = bits.join('　／　');
      if (nodeLockChk) {
        nodeLockChk.checked = !!node.locked;
        if (nodeLockPw) { nodeLockPw.value = ''; nodeLockPw.hidden = !node.locked; nodeLockPw.placeholder = node.locked ? '変更する場合のみ入力' : 'パスワードを設定'; }
        if (nodeLockHint) { nodeLockHint.hidden = !node.locked; nodeLockHint.textContent = node.locked ? '空欄のままなら現在のパスワードを維持します。管理者パスワードでも上書きできます。' : ''; }
      }
      if (nodeDateField) {
        nodeDateField.hidden = false; // 更新日時の指定は編集時のみ
        if (nodeDateChk) nodeDateChk.checked = false;
        if (nodeDateInput) { nodeDateInput.hidden = true; nodeDateInput.value = msToLocalInput(node.updated_at); }
        if (nodeDateHint) nodeDateHint.hidden = true;
      }
    } else {
      dialogTarget = { mode: 'add', parentId: parentId || null };
      nodeDialogTitle.textContent = parentId ? '子項目を追加' : '大項目（カテゴリ）を追加';
      nodeTitleInput.value = '';
      nodeBodyEditor.innerHTML = '';
      if (nodeLockChk) {
        nodeLockChk.checked = false;
        if (nodeLockPw) { nodeLockPw.value = ''; nodeLockPw.hidden = true; nodeLockPw.placeholder = 'パスワードを設定'; }
        if (nodeLockHint) { nodeLockHint.hidden = true; nodeLockHint.textContent = ''; }
      }
      if (nodeDateField) nodeDateField.hidden = true; // 追加時は非表示
    }
    if (nodeAuthorInput) nodeAuthorInput.value = authorName(); // 既定は記入者名。項目ごとに変更可
    if (nodeMetaEl) nodeMetaEl.textContent = metaStr;
    if (nodeErrorEl) nodeErrorEl.textContent = '';
    const canAttach = serverMode();
    [nodeImgBtn, nodeFileBtn].forEach((b) => {
      if (b) { b.disabled = false; b.title = canAttach ? '' : '添付はサーバー(DB)接続時のみ'; }
    });
    hydrateEditorFields(); // 保存済みの入力欄を編集用ウィジェットに復元
    nodeDialog.showModal();
    nodeTitleInput.focus();
  }

  nodeForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const title = nodeTitleInput.value.trim();
    if (!title) return;
    const body = bodyForSave();
    const author = (nodeAuthorInput ? nodeAuthorInput.value : '').trim();
    // 入力した登録者名を既定値としても記憶
    localStorage.setItem(AUTHOR_KEY, author);
    if (typeof authorInput !== 'undefined' && authorInput) authorInput.value = author;
    const lockOpt = nodeLockChk
      ? { enabled: nodeLockChk.checked, pw: nodeLockPw ? nodeLockPw.value : '' }
      : null;
    // 更新日時の手動指定（チェックが入っていて有効な日時のときのみ）
    let updatedAt = null;
    if (nodeDateChk && nodeDateChk.checked && nodeDateInput && nodeDateInput.value) {
      const t = new Date(nodeDateInput.value).getTime();
      if (!isNaN(t)) updatedAt = t;
    }
    const submitBtn = nodeForm.querySelector('button[type=submit]');
    submitBtn.disabled = true;
    nodeError('');
    try {
      if (dialogTarget.mode === 'edit') {
        await opUpdate(dialogTarget.id, title, body, author, lockOpt, updatedAt);
      } else {
        if (dialogTarget.parentId) { openNodes.add(dialogTarget.parentId); persistOpen(); }
        await opCreate(dialogTarget.parentId, title, body, author, lockOpt);
      }
      nodeDialog.close();
      renderEdit();
      if (!navView.hidden) renderNav(); // 案内モードから編集した場合は即反映
      flashSaved();
    } catch (err) {
      nodeError('保存に失敗しました：' + err.message);
    } finally {
      submitBtn.disabled = false;
    }
  });
  $('#nodeCancelBtn').addEventListener('click', () => nodeDialog.close());

  /* ---------- 文字装飾ツールバー（選択部分にWord風に反映） ---------- */
  const fmtBar = $('#fmtBar');
  if (fmtBar) {
    // ボタン押下でエディタの選択が外れないように
    fmtBar.addEventListener('mousedown', (e) => { if (e.target.closest('button')) e.preventDefault(); });
    fmtBar.addEventListener('click', (e) => {
      const btn = e.target.closest('button');
      if (!btn) return;
      e.preventDefault();
      nodeBodyEditor.focus();
      try { document.execCommand('styleWithCSS', false, true); } catch (_) {}
      if (btn.dataset.cmd === 'bold') document.execCommand('bold');
      else if (btn.dataset.size) document.execCommand('fontSize', false, btn.dataset.size);
      else if (btn.dataset.color) document.execCommand('foreColor', false, btn.dataset.color);
      else if (btn.dataset.clear) { document.execCommand('removeFormat'); }
    });
  }

  /* ---------- 画像アップロード / 貼り付け ---------- */
  const nodeImgBtn = $('#nodeImgBtn');
  const nodeImgFile = $('#nodeImgFile');
  const nodeErrorEl = $('#nodeError');
  function nodeError(msg) { if (nodeErrorEl) nodeErrorEl.textContent = msg || ''; }

  const nodeFileBtn = $('#nodeFileBtn');
  const nodeFileFile = $('#nodeFileFile');

  async function uploadAttachment(file) {
    if (!serverMode()) { nodeError('添付はサーバー(DB)接続時のみ利用できます'); return; }
    async function send(token) {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('author', authorName());
      const headers = {};
      if (token) headers['X-Api-Token'] = token;
      const res = await fetch(`${API}?action=upload`, { method: 'POST', headers, body: fd });
      let data = null; try { data = await res.json(); } catch (e) {}
      return { res, data };
    }
    try {
      nodeError('アップロード中…');
      while (true) {
        const { res, data } = await send(apiToken());
        if (res.status === 401) {
          const t = await askToken(data && data.error);
          if (t != null) { apiTokenInput.value = t; localStorage.setItem(TOKEN_KEY, t); updateEditLock(); continue; }
          throw new Error('編集には合言葉（トークン）が必要です');
        }
        if (!data || typeof data !== 'object') throw new Error('サーバー応答が不正です');
        if (!res.ok || data.ok === false) throw new Error(data.error || ('HTTP ' + res.status));
        if (data.is_image) {
          appendEditorHtml(`<img src="${esc(data.url)}" alt=""><br>`);
          nodeError('画像を本文の下に追加しました');
          // 挿入した画像の名称をすぐ入力できるようにする
          const imgs = nodeBodyEditor.querySelectorAll('img');
          const last = imgs[imgs.length - 1];
          if (last) openImgNameDialog(last);
        } else {
          const nm = data.name || 'ファイル';
          appendEditorHtml(`<a class="tm-filechip" href="${esc(data.url)}">${fileIcon(nm)} ${esc(nm)}</a> `);
          nodeError('ファイルを文末に追加しました');
        }
        break;
      }
    } catch (e) { nodeError('アップロード失敗：' + e.message); }
  }
  if (nodeImgBtn) {
    nodeImgBtn.addEventListener('click', () => {
      if (!serverMode()) { nodeError('添付はサーバー(DB)接続時のみ利用できます'); return; }
      nodeImgFile.click();
    });
    nodeImgFile.addEventListener('change', () => {
      const f = nodeImgFile.files[0]; if (f) uploadAttachment(f); nodeImgFile.value = '';
    });
    nodeFileBtn.addEventListener('click', () => {
      if (!serverMode()) { nodeError('添付はサーバー(DB)接続時のみ利用できます'); return; }
      nodeFileFile.click();
    });
    nodeFileFile.addEventListener('change', () => {
      const f = nodeFileFile.files[0]; if (f) uploadAttachment(f); nodeFileFile.value = '';
    });
    nodeBodyEditor.addEventListener('paste', (e) => {
      const cd = e.clipboardData;
      const items = cd && cd.items;
      // 画像（スクリーンショット等）の貼り付けはアップロード
      if (items) {
        for (const it of items) {
          if (it.type && it.type.indexOf('image/') === 0) {
            e.preventDefault();
            const f = it.getAsFile();
            if (f) uploadAttachment(f);
            return;
          }
        }
      }
      if (!cd) return;
      // リッチテキスト（文字サイズ・色・太字など）があればそれを保持して貼り付け
      const html = cd.getData('text/html');
      if (html && html.trim()) {
        e.preventDefault();
        insertEditorHtml(sanitizePastedHtml(html));
        return;
      }
      // プレーンテキストのみの場合は改行を保ちつつURLをリンク化
      const text = cd.getData('text/plain');
      if (text == null || text === '') return; // ファイル等はブラウザ既定に任せる
      e.preventDefault();
      insertEditorHtml(plainToHtml(text));
    });
    // 編集画面：画像タップで「名称の入力・削除」、添付ファイルはタップで削除
    nodeBodyEditor.addEventListener('click', (e) => {
      const img = e.target.closest('img');
      if (img) { e.preventDefault(); openImgNameDialog(img); return; }
      const chip = e.target.closest('a.tm-filechip');
      if (!chip) return;
      e.preventDefault();
      askConfirm('この添付ファイルを削除しますか？', () => { removeEditorEl(chip); }, '削除');
    });
  }
  function removeEditorEl(el) {
    const next = el.nextSibling;
    el.remove();
    if (next && next.nodeType === 1 && next.tagName === 'BR') next.remove();
    else if (next && next.nodeType === 3 && !next.nodeValue.trim()) next.remove();
    nodeBodyEditor.focus();
  }

  /* ---------- 画像の名称ダイアログ（キャプション＋削除） ---------- */
  const imgNameDialog = $('#imgNameDialog');
  const imgNameForm = $('#imgNameForm');
  let imgNameTarget = null;
  function openImgNameDialog(img) {
    imgNameTarget = img;
    const prev = $('#imgNamePreview');
    if (prev) prev.src = img.getAttribute('src') || '';
    $('#imgNameInput').value = img.getAttribute('alt') || '';
    imgNameDialog.showModal();
    setTimeout(() => $('#imgNameInput').focus(), 30);
  }
  imgNameForm.addEventListener('submit', (e) => {
    e.preventDefault();
    if (imgNameTarget) {
      const v = $('#imgNameInput').value.trim();
      if (v) { imgNameTarget.setAttribute('alt', v); imgNameTarget.setAttribute('title', v); }
      else { imgNameTarget.setAttribute('alt', ''); imgNameTarget.removeAttribute('title'); }
    }
    imgNameDialog.close();
  });
  $('#imgNameCancel').addEventListener('click', () => imgNameDialog.close());
  $('#imgNameDelete').addEventListener('click', () => {
    if (!imgNameTarget) { imgNameDialog.close(); return; }
    const img = imgNameTarget;
    askConfirm('この画像を削除しますか？', () => { removeEditorEl(img); imgNameDialog.close(); }, '削除');
  });

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

  /* ---------- token dialog（編集の合言葉） ---------- */
  const tokenDialog = $('#tokenDialog');
  const tokenForm = $('#tokenForm');
  const tokenInput = $('#tokenInput');
  const tokenErrorEl = $('#tokenError');
  let tokenResolve = null;
  function askToken(message) {
    return new Promise((resolve) => {
      tokenResolve = resolve;
      tokenInput.value = apiToken() || '';
      tokenErrorEl.textContent = (message && /トークン|合言葉|401/.test(message))
        ? '合言葉が違います。もう一度入力してください。'
        : (message || '');
      tokenDialog.showModal();
      tokenInput.focus();
      tokenInput.select();
    });
  }
  function resolveToken(v) { const r = tokenResolve; tokenResolve = null; if (r) r(v); }
  tokenForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const v = tokenInput.value.trim();
    tokenDialog.close();
    resolveToken(v || null);
  });
  $('#tokenCancel').addEventListener('click', () => { tokenDialog.close(); resolveToken(null); });
  tokenDialog.addEventListener('cancel', () => resolveToken(null));

  /* ---------- admin password dialog（初期化などの保護） ---------- */
  const ADMIN_PW = 'Welsys1234'; // 管理者パスワード（変更する場合はこの値を書き換え）
  const adminDialog = $('#adminDialog');
  const adminForm = $('#adminForm');
  const adminInput = $('#adminInput');
  const adminErrorEl = $('#adminError');
  let adminResolve = null;
  function askAdmin() {
    return new Promise((resolve) => {
      adminResolve = resolve;
      adminInput.value = '';
      adminErrorEl.textContent = '';
      adminDialog.showModal();
      adminInput.focus();
    });
  }
  function resolveAdmin(ok) { const r = adminResolve; adminResolve = null; if (r) r(ok); }
  adminForm.addEventListener('submit', (e) => {
    e.preventDefault();
    if (adminInput.value === ADMIN_PW) { adminDialog.close(); resolveAdmin(true); }
    else { adminErrorEl.textContent = 'パスワードが違います。'; adminInput.select(); }
  });
  $('#adminCancel').addEventListener('click', () => { adminDialog.close(); resolveAdmin(false); });
  adminDialog.addEventListener('cancel', () => resolveAdmin(false));

  /* ---------- unlock dialog（項目の閲覧パスワード） ---------- */
  const unlockDialog = $('#unlockDialog');
  const unlockForm = $('#unlockForm');
  const unlockInput = $('#unlockInput');
  const unlockMsg = $('#unlockMsg');
  const unlockErrorEl = $('#unlockError');
  let unlockResolve = null;
  function askUnlock(title, err) {
    return new Promise((resolve) => {
      unlockResolve = resolve;
      unlockInput.value = '';
      if (unlockMsg) unlockMsg.textContent = `「${title || ''}」は閲覧パスワードで保護されています。`;
      unlockErrorEl.textContent = err || '';
      unlockDialog.showModal();
      unlockInput.focus();
    });
  }
  function resolveUnlock(v) { const r = unlockResolve; unlockResolve = null; if (r) r(v); }
  unlockForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const v = unlockInput.value;
    unlockDialog.close();
    resolveUnlock(v === '' ? null : v);
  });
  $('#unlockCancel').addEventListener('click', () => { unlockDialog.close(); resolveUnlock(null); });
  unlockDialog.addEventListener('cancel', () => resolveUnlock(null));

  function confirmDelete(id) {
    const node = findNode(id);
    if (!node) return;
    const count = countDescendants(node);
    const extra = count > 0 ? `（子項目 ${count} 件も一緒に削除されます）` : '';
    const shared = serverMode() ? '（共有データから削除されます）' : '';
    askConfirm(`「${node.title}」を削除しますか？${extra}${shared}`, async () => {
      try { await opDelete(id); renderEdit(); flashSaved('削除しました'); }
      catch (e) { flashSaved('削除に失敗：' + e.message); }
    }, '削除');
  }
  function countDescendants(node) {
    let c = 0;
    for (const child of node.children || []) c += 1 + countDescendants(child);
    return c;
  }

  /* ---------- CSV export / import / reset ---------- */
  $('#exportBtn').addEventListener('click', () => {
    // UTF-8 BOM 付きで Excel でも文字化けしにくくする
    const csv = '﻿' + rowsToCsv(treeToRows());
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const ymd = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `manual-${ymd}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    flashSaved('CSVを出力しました');
  });

  const importFile = $('#importFile');
  $('#importBtn').addEventListener('click', () => importFile.click());
  importFile.addEventListener('change', () => {
    const file = importFile.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const clean = sanitize(csvToTree(reader.result));
        const shared = serverMode() ? '（サーバー上の全員の共有データが置き換わります）' : '';
        askConfirm(`現在のデータを、読み込んだCSVの内容で置き換えますか？${shared}`, async () => {
          try {
            await opReplaceAll(clean);
            navRestart();
            renderEdit();
            flashSaved('CSVを取り込みました');
          } catch (e) { saveStatus.textContent = '✗ 取込に失敗しました：' + e.message; }
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

  $('#resetBtn').addEventListener('click', async () => {
    if (!(await askAdmin())) return; // 管理者パスワードが必要
    const shared = serverMode() ? ' サーバー上の全員の共有データがサンプルに置き換わります。' : '';
    askConfirm('すべてのデータを初期状態（サンプル）に戻しますか？この操作は元に戻せません。' + shared, async () => {
      try {
        await opReplaceAll(seedData());
        openNodes = new Set();
        persistOpen();
        navRestart();
        renderEdit();
        flashSaved('初期化しました');
      } catch (e) { saveStatus.textContent = '✗ 初期化に失敗しました：' + e.message; }
    }, '初期化する');
  });

  /* ============================================================
     EXCEL  (.xlsx) 一括登録 — 大項目/中項目/小項目/内容/記入者 の階層形式
     SheetJS(vendor/xlsx.full.min.js) を利用
     ============================================================ */
  const XLS_HIER = ['大項目', '中項目', '小項目', '第4階層', '第5階層', '第6階層'];
  const XLS_BODY = '内容（手順・説明）';
  const XLS_AUTHOR = '記入者';
  const xlsxOk = () => typeof XLSX !== 'undefined';

  // 記録用メタ付きのフラット行（replace_all 用）
  function treeToRowsMeta(nodes = tree, parentId = '', out = []) {
    nodes.forEach((n, idx) => {
      out.push({ id: n.id, parent_id: parentId, sort_order: idx, title: n.title, body: n.body || '', created_by: n.created_by || '', updated_by: n.updated_by || '' });
      treeToRowsMeta(n.children || [], n.id, out);
    });
    return out;
  }
  function maxDepth(nodes) {
    let m = 0;
    const walk = (a, d) => a.forEach((n) => { m = Math.max(m, d); walk(n.children || [], d + 1); });
    walk(nodes, 1);
    return m;
  }
  function countAll(nodes) {
    let c = 0;
    nodes.forEach((n) => { c += 1 + countAll(n.children || []); });
    return c;
  }
  function fillAuthor(nodes, name) {
    if (!name) return;
    nodes.forEach((n) => {
      if (!n.updated_by) n.updated_by = name;
      if (!n.created_by) n.created_by = name;
      fillAuthor(n.children || [], name);
    });
  }

  // tree -> アウトライン形式の二次元配列（Excel出力）
  function treeToOutlineAoa() {
    const depth = Math.max(3, Math.min(maxDepth(tree) || 1, XLS_HIER.length));
    const cols = XLS_HIER.slice(0, depth);
    const head = [...cols, XLS_BODY, XLS_AUTHOR];
    const aoa = [head];
    const walk = (nodes, d) => {
      nodes.forEach((n) => {
        const row = new Array(head.length).fill('');
        row[Math.min(d, cols.length - 1)] = n.title;
        const plainBody = htmlToPlain(n.body);
        if (plainBody) row[cols.length] = plainBody;
        const by = n.updated_by || n.created_by;
        if (by) row[cols.length + 1] = by;
        aoa.push(row);
        walk(n.children || [], d + 1);
      });
    };
    walk(tree, 0);
    return aoa;
  }

  // アウトライン形式 -> tree
  function outlineToTree(aoa) {
    if (!aoa || !aoa.length) return [];
    const header = aoa[0].map((h) => String(h == null ? '' : h).trim());
    const hierIdx = [];
    XLS_HIER.forEach((name) => { const i = header.indexOf(name); if (i >= 0) hierIdx.push(i); });
    let bodyIdx = header.findIndex((h) => /内容|手順|説明/.test(h));
    let authIdx = header.findIndex((h) => /記入|担当|作成者/.test(h));
    if (hierIdx.length === 0) {
      const end = bodyIdx >= 0 ? bodyIdx : header.length;
      for (let i = 0; i < end; i++) hierIdx.push(i);
      if (bodyIdx < 0 && header.length > hierIdx.length) bodyIdx = hierIdx.length;
    }
    const roots = [];
    const stack = [];
    for (let r = 1; r < aoa.length; r++) {
      const row = aoa[r] || [];
      let deepest = -1;
      for (let l = 0; l < hierIdx.length; l++) {
        const val = String(row[hierIdx[l]] == null ? '' : row[hierIdx[l]]).trim();
        if (val !== '') {
          const parentArr = l === 0 ? roots : (stack[l - 1] ? stack[l - 1].children : roots);
          let node = parentArr.find((x) => x.title === val);
          if (!node) { node = { id: uid(), title: val, body: '', children: [], created_by: '', updated_by: '' }; parentArr.push(node); }
          stack[l] = node; stack.length = l + 1;
          deepest = l;
        }
      }
      const body = bodyIdx >= 0 ? String(row[bodyIdx] == null ? '' : row[bodyIdx]).trim() : '';
      const auth = authIdx >= 0 ? String(row[authIdx] == null ? '' : row[authIdx]).trim() : '';
      const target = deepest >= 0 ? stack[deepest] : stack[stack.length - 1];
      if (target) {
        if (body) target.body = target.body ? target.body + '\n' + body : body;
        if (auth) { target.updated_by = auth; if (!target.created_by) target.created_by = auth; }
      }
    }
    return roots;
  }

  // サーバー/ローカルに部分木を作成（追加登録）
  async function createSubtree(nodes, parentId) {
    for (const n of nodes) {
      if (serverMode()) {
        const res = await apiCall('node_create', { method: 'POST', body: { parent_id: parentId || '', title: n.title, body: n.body || '', author: n.updated_by || authorName() } });
        const newId = res.node && res.node.id;
        if (newId && n.children && n.children.length) await createSubtree(n.children, newId);
      } else {
        const now = Date.now();
        const nn = { id: uid(), title: n.title, body: n.body || '', children: [], created_by: n.updated_by || authorName(), updated_by: n.updated_by || authorName(), created_at: now, updated_at: now };
        if (parentId) { const p = findNode(parentId); if (p) p.children.push(nn); } else tree.push(nn);
        if (n.children && n.children.length) await createSubtree(n.children, nn.id);
      }
    }
  }

  function buildTemplateWorkbook() {
    const head = ['大項目', '中項目', '小項目', XLS_BODY, XLS_AUTHOR];
    const rows = [
      head,
      ['リテイルオンサイト', '搬入', '台車の手配', '1. 台車を1階に用意\n2. 搬入経路を確認', '山田'],
      ['リテイルオンサイト', '搬入', 'エレベーター確認', '使用可能時間を管理室に確認する', ''],
      ['リテイルオンサイト', '設置', '', '什器を所定位置に設置し水平を確認', '佐藤'],
      ['トラブル対応', 'ネットワーク', 'つながらない', '1. ルーターを再起動\n2. LANケーブルを挿し直す', '田中'],
    ];
    const ws = XLSX.utils.aoa_to_sheet(rows);
    ws['!cols'] = [{ wch: 18 }, { wch: 16 }, { wch: 18 }, { wch: 44 }, { wch: 10 }];
    const help = [
      ['Case By Case 一括登録用ひな型 — 使い方'], [''],
      ['1) 「登録」シートの2行目以降に、項目を1行ずつ入力してください。'],
      ['2) 列の意味'],
      ['   大項目/中項目/小項目 … 階層（大きい分類→小さい項目）'],
      ['   内容（手順・説明） … 選んだときに表示する手順。セル内改行(Alt+Enter)で段落、'],
      ['                        行頭「- 」で箇条書き、「# 」で見出しになります。'],
      ['   記入者 … 入力した人の名前（任意）'],
      ['3) 同じ大項目・中項目は各行に繰り返してOK（自動でまとめられます）。'],
      ['4) 中項目・小項目を空欄にすると、上位の項目に内容が付きます。'],
      ['5) さらに深い階層は「第4階層」「第5階層」列を追加できます。'],
      ['6) アプリの編集モード →「Excel一括登録」で読み込みます（置き換え/追加）。'],
    ];
    const wsH = XLSX.utils.aoa_to_sheet(help);
    wsH['!cols'] = [{ wch: 72 }];
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, '登録');
    XLSX.utils.book_append_sheet(wb, wsH, '使い方');
    return wb;
  }

  $('#xlsxExportBtn').addEventListener('click', () => {
    if (!xlsxOk()) { flashSaved('Excelライブラリを読み込めませんでした'); return; }
    const ws = XLSX.utils.aoa_to_sheet(treeToOutlineAoa());
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'マニュアル');
    XLSX.writeFile(wb, `manual-${new Date().toISOString().slice(0, 10)}.xlsx`);
    flashSaved('Excelを出力しました');
  });
  $('#xlsxTemplateBtn').addEventListener('click', () => {
    if (!xlsxOk()) { flashSaved('Excelライブラリを読み込めませんでした'); return; }
    XLSX.writeFile(buildTemplateWorkbook(), '登録用ひな型.xlsx');
    flashSaved('ひな型を出力しました');
  });

  const xlsxDialog = $('#xlsxDialog');
  const xlsxFile = $('#xlsxFile');
  let xlsxParsed = null;
  $('#xlsxImportBtn').addEventListener('click', () => {
    if (!xlsxOk()) { flashSaved('Excelライブラリを読み込めませんでした'); return; }
    xlsxFile.click();
  });
  xlsxFile.addEventListener('change', () => {
    const f = xlsxFile.files[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const wb = XLSX.read(new Uint8Array(reader.result), { type: 'array' });
        const sheet = wb.SheetNames.includes('登録') ? '登録' : wb.SheetNames[0];
        const aoa = XLSX.utils.sheet_to_json(wb.Sheets[sheet], { header: 1, defval: '' });
        const roots = outlineToTree(aoa);
        fillAuthor(roots, authorName());
        const count = countAll(roots);
        if (count === 0) { flashSaved('取り込める項目がありませんでした'); xlsxFile.value = ''; return; }
        xlsxParsed = roots;
        $('#xlsxMsg').innerHTML = `Excelから <b>${count}</b> 項目を読み取りました。<br>「置き換え」＝現在の内容をすべて入れ替え、「追加登録」＝現在の内容に加えます。`;
        $('#xlsxError').textContent = '';
        xlsxDialog.showModal();
      } catch (err) {
        flashSaved('Excel読込に失敗：' + err.message);
      }
      xlsxFile.value = '';
    };
    reader.readAsArrayBuffer(f);
  });
  async function runXlsxImport(mode) {
    if (!xlsxParsed) return;
    const roots = xlsxParsed;
    try {
      if (mode === 'replace') {
        if (serverMode()) { await apiCall('replace_all', { method: 'POST', body: { nodes: treeToRowsMeta(roots) } }); await reloadFromServer(); }
        else { tree = roots; persist(); }
      } else {
        await createSubtree(roots, null);
        if (serverMode()) await reloadFromServer(); else persist();
      }
      xlsxDialog.close();
      xlsxParsed = null;
      navRestart();
      renderEdit();
      flashSaved(mode === 'replace' ? 'Excelで置き換えました' : 'Excelから追加しました');
    } catch (err) {
      $('#xlsxError').textContent = '取込に失敗：' + err.message;
    }
  }
  $('#xlsxReplace').addEventListener('click', () => runXlsxImport('replace'));
  $('#xlsxAppend').addEventListener('click', () => runXlsxImport('append'));
  $('#xlsxCancel').addEventListener('click', () => { xlsxDialog.close(); xlsxParsed = null; });

  /* ============================================================
     SERVER SYNC  (PHP + DB backend: api.php)
     ============================================================ */
  const API = 'api.php';
  const TOKEN_KEY = 'treeManual.apiToken.v1';
  const apiTokenInput = $('#apiToken');
  const serverStatusEl = $('#serverStatus');
  const syncStatusEl = $('#syncStatus');
  const serverNoteEl = $('#serverNote');

  let serverAvailable = false;   // api.php に到達できる
  let dbConnected = false;       // DB が使える → サーバーが正データ
  let hasToken = false;          // サーバー側でトークン必須か
  let hasGemini = false;         // サーバーに Gemini APIキーが設定済みか
  let aiOn = true;               // 管理者による AI表示ON/OFF（全端末共有・既定ON）
  let aiEnabled = false;         // 実際にAIボタンを表示するか（hasGemini かつ aiOn）
  let dbError = null;
  let authRequired = false;      // Basic認証等でログインが必要（401）→ 中身を出さない

  /* ---------- ログアウト / 自動ログアウト（無操作タイマー） ---------- */
  const LOGOUT_KEY = 'treeManual.loggedOut.v1';
  // 無操作で自動ログアウトするまでの時間（既定30分）。?idlemin=... で上書き可（テスト/運用調整用）
  let IDLE_LIMIT = 30 * 60 * 1000;
  try {
    const qs = new URLSearchParams(location.search);
    if (qs.has('idlemin')) IDLE_LIMIT = Math.max(0.05, parseFloat(qs.get('idlemin')) || 30) * 60 * 1000;
    else if (qs.has('idlems')) IDLE_LIMIT = Math.max(3000, parseInt(qs.get('idlems'), 10) || IDLE_LIMIT);
    else {
      const m = parseFloat(localStorage.getItem('treeManual.idleMin.v1'));
      if (m > 0) IDLE_LIMIT = m * 60 * 1000;
    }
  } catch (_) {}
  let loggedOut = false;         // アプリ側でログアウト状態（サーバーに繋がっても中身を出さない）
  let logoutReason = '';         // 'manual' | 'timeout'
  try {
    const raw = localStorage.getItem(LOGOUT_KEY);
    if (raw) { const o = JSON.parse(raw); loggedOut = !!o.out; logoutReason = o.reason || 'manual'; }
  } catch (_) {}
  let idleTimer = null;
  function resetIdleTimer() {
    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
    if (loggedOut) return;             // 既にログアウト済みなら計測しない
    if (!serverMode()) return;          // 接続できていない間は計測しない（ゲート表示中）
    if (!(IDLE_LIMIT > 0)) return;
    idleTimer = setTimeout(() => { doLogout('timeout'); }, IDLE_LIMIT);
  }
  ['pointerdown', 'keydown', 'touchstart', 'wheel', 'mousemove'].forEach((ev) => {
    document.addEventListener(ev, () => { if (!loggedOut) resetIdleTimer(); }, { passive: true });
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && !loggedOut) resetIdleTimer(); });

  // ブラウザにキャッシュされた Basic認証の資格情報をできる限り消す（再ログインでパスワードを求めるため）
  function clearBasicAuth() {
    try {
      return fetch(`${API}?action=config&_logout=${Date.now()}`, {
        headers: { 'Authorization': 'Basic ' + btoa('logout:' + Date.now()) },
        cache: 'no-store',
      }).catch(() => {});
    } catch (_) { return Promise.resolve(); }
  }

  async function doLogout(reason = 'manual') {
    loggedOut = true;
    logoutReason = reason;
    try { localStorage.setItem(LOGOUT_KEY, JSON.stringify({ out: true, reason, at: Date.now() })); } catch (_) {}
    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
    // 編集トークンも破棄（再ログイン時に入れ直し）
    try { localStorage.removeItem(TOKEN_KEY); } catch (_) {}
    if (apiTokenInput) apiTokenInput.value = '';
    updateAuthGate();          // 「ログアウトしました」ゲートを表示し中身を隠す
    clearGatedContent();
    await clearBasicAuth();     // ベストエフォートで資格情報を破棄
    setServerStatus();
  }

  function doLogin() {
    // ログアウト状態を解除し、ページを再読み込み → 必要ならログイン画面（Basic認証）が表示される
    loggedOut = false; logoutReason = '';
    try { localStorage.removeItem(LOGOUT_KEY); } catch (_) {}
    try { location.reload(); } catch (_) {}
  }

  apiTokenInput.value = localStorage.getItem(TOKEN_KEY) || '';
  apiTokenInput.addEventListener('change', async () => {
    localStorage.setItem(TOKEN_KEY, apiTokenInput.value.trim());
    await detectServer();
    if (serverMode()) {
      try { await reloadFromServer(); if (!editView.hidden) renderEdit(); else navRestart(); } catch (e) {}
    }
  });

  function apiToken() { return (apiTokenInput.value || '').trim(); }

  function syncMsg(msg, isErr = false) {
    syncStatusEl.textContent = msg;
    syncStatusEl.classList.toggle('is-err', isErr);
    if (!isErr) {
      clearTimeout(syncMsg._t);
      syncMsg._t = setTimeout(() => { syncStatusEl.textContent = ''; }, 4000);
    }
  }

  async function apiCall(action, callOpts = {}) {
    const { method = 'GET', body = null } = callOpts;
    // 401（トークン不足/相違）の間は、合言葉を入力してもらい繰り返しリトライ
    while (true) {
      const opts = { method, headers: {}, cache: 'no-store' };
      const tok = apiToken();
      if (tok) opts.headers['X-Api-Token'] = tok;
      if (body != null) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
      const res = await fetch(`${API}?action=${encodeURIComponent(action)}`, opts);
      let data = null;
      try { data = await res.json(); } catch (e) { /* non-JSON */ }
      if (data === null || typeof data !== 'object') throw new Error('サーバー応答が不正です（PHP未対応の可能性）');
      if (res.status === 401) {
        const t = await askToken(data.error);
        if (t != null) {
          apiTokenInput.value = t;
          localStorage.setItem(TOKEN_KEY, t);
          updateEditLock();
          continue; // 新しい合言葉で再試行
        }
        throw new Error(data.error || '編集には合言葉（トークン）が必要です');
      }
      if (!res.ok || data.ok === false) throw new Error(data.error || data.message || `HTTP ${res.status}`);
      return data;
    }
  }

  function setServerStatus() {
    serverStatusEl.classList.remove('is-off', 'is-on', 'is-ftp');
    if (serverAvailable && dbConnected) {
      serverStatusEl.classList.add('is-on');
      serverStatusEl.textContent = hasToken ? 'DB接続済み（共有中・要トークン）' : 'DB接続済み（共有中）';
    } else if (serverAvailable && !dbConnected) {
      serverStatusEl.classList.add('is-ftp');
      serverStatusEl.textContent = 'DB未接続（設定を確認）';
    } else {
      serverStatusEl.classList.add('is-off');
      serverStatusEl.textContent = 'サーバー未接続（この端末のみ）';
    }
    const dis = !(serverAvailable && dbConnected);
    const rb = $('#reloadBtn'), mb = $('#migrateBtn');
    if (rb) rb.disabled = dis;
    if (mb) mb.disabled = dis;
    // ヘッダーの共有状態チップ（常時表示・端末間の食い違い診断用）
    const chip = $('#connChip');
    if (chip) {
      chip.classList.remove('is-shared', 'is-nodb', 'is-local', 'is-busy');
      if (serverAvailable && dbConnected) { chip.textContent = '● 共有中'; chip.classList.add('is-shared'); }
      else if (serverAvailable && !dbConnected) { chip.textContent = '● DB未接続'; chip.classList.add('is-nodb'); }
      else { chip.textContent = '● この端末のみ'; chip.classList.add('is-local'); }
      // 共有中でないときは、タップでサーバー接続（必要ならログイン）できるようにする
      const canConnect = !(serverAvailable && dbConnected);
      chip.classList.toggle('is-clickable', canConnect);
      chip.title = canConnect
        ? 'タップでサーバーに接続し、最新の情報を取得します'
        : 'データの共有状態（全端末で共有中）';
    }
    // DB接続エラーの内容を画面に表示（原因特定用）
    const eb = $('#dbErrorBar');
    if (eb) {
      if (serverAvailable && !dbConnected) {
        eb.hidden = false;
        eb.innerHTML = '⚠ サーバーのデータベースに接続できません。<b>config.php</b> の設定'
          + '（データベースホスト・データベース名・ユーザー名・パスワード）と、PHPのバージョン（7.4以上）をご確認ください。'
          + (dbError ? `<span class="tm-dberror-detail">詳細: ${esc(dbError)}</span>` : '');
      } else {
        eb.hidden = true;
      }
    }
    if (serverNoteEl && serverAvailable && !dbConnected && dbError) {
      serverNoteEl.textContent = 'DBに接続できません（config.php の設定をご確認ください）: ' + dbError;
    }
    updateEditLock();
  }

  // 編集にトークンが必要なのに未入力のとき、目立つ通知を出す
  function updateEditLock() {
    const lock = $('#editLock');
    if (lock) lock.hidden = !(serverMode() && hasToken && !apiToken());
  }
  $('#editLockBtn').addEventListener('click', async () => {
    const t = await askToken();
    if (t != null) {
      apiTokenInput.value = t;
      localStorage.setItem(TOKEN_KEY, t);
      updateEditLock();
      syncMsg('合言葉を設定しました');
    }
  });

  async function detectServer() {
    authRequired = false;
    try {
      // config は認証不要のはずなので、401 はサーバー側のログイン(Basic認証)未通過を意味する
      const headers = {};
      const tok = apiToken();
      if (tok) headers['X-Api-Token'] = tok;
      const res = await fetch(`${API}?action=config`, { headers, cache: 'no-store' });
      if (res.status === 401 || res.status === 403) {
        authRequired = true;
        serverAvailable = false; dbConnected = false; hasToken = false; dbError = null;
        updateAuthGate();
        setServerStatus();
        return;
      }
      const cfg = await res.json();
      serverAvailable = true;
      dbConnected = !!cfg.dbConnected;
      hasToken = !!cfg.hasToken;
      hasGemini = !!cfg.hasGemini;
      aiOn = (cfg.aiOn !== false);            // 管理者の表示ON/OFF（既定ON）
      aiEnabled = hasGemini && aiOn;          // 実際にAIボタンを出すか
      dbError = cfg.error || null;
      updateAiToggleUI();
    } catch (e) {
      serverAvailable = false; dbConnected = false; hasToken = false; dbError = null;
    }
    updateAuthGate();
    setServerStatus();
    resetIdleTimer();   // 接続できていれば自動ログアウトの計測を開始/更新
  }
  // サーバー（ログイン＋DB）に正しく接続できているときだけ中身を表示する。
  // それ以外（未ログイン / 接続不可 / DB未接続）は全画面ゲートで中身を隠し、
  // この端末に残ったローカルの古いデータは一切表示しない。
  // 戻り値: null=接続OK / 'auth'=未ログイン / 'offline'=サーバー接続不可 / 'nodb'=DB未接続
  function gateReason() {
    if (loggedOut) return 'loggedout';
    if (authRequired) return 'auth';
    if (!serverAvailable) return 'offline';
    if (!dbConnected) return 'nodb';
    return null;
  }
  // 接続確認中（起動直後・再接続中）にゲートを「接続中」表示にして中身を隠す
  function showConnecting() {
    if (loggedOut) { updateAuthGate(); return; }  // ログアウト中は「ログアウトしました」を優先
    const gate = $('#authGate'); if (!gate) return;
    gate.hidden = false;
    const ico = $('#authGateIco'), title = $('#authGateTitle'), msg = $('#authGateMsg');
    const detail = $('#authGateDetail'), reload = $('#authReloadBtn'), retry = $('#authRetryBtn');
    if (ico) ico.innerHTML = '&#128246;';
    if (title) title.textContent = 'サーバーに接続しています…';
    if (msg) msg.innerHTML = '最新の共有データを読み込んでいます。しばらくお待ちください。';
    if (detail) { detail.hidden = true; detail.textContent = ''; }
    if (reload) reload.hidden = true;
    if (retry) retry.hidden = true;
    clearGatedContent();
  }
  function clearGatedContent() {
    document.querySelectorAll('dialog[open]').forEach((d) => { try { d.close(); } catch (_) {} });
    if (chatLog) chatLog.innerHTML = '';
    if (choiceDock) choiceDock.innerHTML = '';
    if (navAddDock) navAddDock.innerHTML = '';
    if (typeof editTree !== 'undefined' && editTree) editTree.innerHTML = '';
  }
  function updateAuthGate() {
    const gate = $('#authGate');
    const reason = gateReason();
    const active = !!reason;
    if (gate) {
      gate.hidden = !active;
      if (active) {
        const ico = $('#authGateIco'), title = $('#authGateTitle'), msg = $('#authGateMsg');
        const detail = $('#authGateDetail'), reload = $('#authReloadBtn'), retry = $('#authRetryBtn');
        let showRetry = true;
        if (reason === 'loggedout') {
          if (ico) ico.innerHTML = '&#128274;';
          if (title) title.textContent = 'ログアウトしました';
          if (msg) msg.innerHTML = (logoutReason === 'timeout'
            ? '一定時間操作がなかったため、自動的にログアウトしました。<br>'
            : 'ログアウトしました。<br>')
            + '続けるには、もう一度ログインしてください。';
          if (reload) reload.innerHTML = '&#128274; ログイン';
          showRetry = false;  // 再ログインは必ず再読み込み経由（パスワードを求めるため）
        } else if (reason === 'auth') {
          if (ico) ico.innerHTML = '&#128274;';
          if (title) title.textContent = 'ログインが必要です';
          if (msg) msg.innerHTML = 'このマニュアルを見るには、ID・パスワードでのログインが必要です。<br>'
            + '下のボタンで再読み込みし、表示されるログイン画面で入力してください。';
          if (reload) reload.innerHTML = '&#8635; 再読み込みしてログイン';
        } else if (reason === 'nodb') {
          if (ico) ico.innerHTML = '&#9888;';
          if (title) title.textContent = 'データベースに接続できません';
          if (msg) msg.innerHTML = 'サーバーのデータベースに接続できないため、内容を表示できません。<br>'
            + '管理者は <b>config.php</b> の設定とPHPのバージョンをご確認ください。';
          if (reload) reload.innerHTML = '&#8635; 再読み込みしてログイン';
        } else { // offline / unreachable
          if (ico) ico.innerHTML = '&#128246;';
          if (title) title.textContent = 'サーバーに接続してください';
          if (msg) msg.innerHTML = 'サーバー（共有データ）に接続できないため、内容を表示できません。<br>'
            + 'この端末に保存された内容は表示しません。ネットワークを確認し、ログインし直してください。';
          if (reload) reload.innerHTML = '&#8635; 再読み込みしてログイン';
        }
        if (reload) reload.hidden = false;
        if (retry) retry.hidden = !showRetry;
        if (detail) {
          if (dbError && reason !== 'auth' && reason !== 'loggedout') { detail.hidden = false; detail.textContent = '詳細: ' + dbError; }
          else { detail.hidden = true; detail.textContent = ''; }
        }
      }
    }
    if (active) clearGatedContent();
  }

  { const arb = $('#authReloadBtn'); if (arb) arb.addEventListener('click', () => {
    if (loggedOut) { doLogin(); return; }  // ログアウト状態を解除してから再読み込み
    try { location.reload(); } catch (_) {}
  }); }
  { const lo = $('#footerLogout'); if (lo) lo.addEventListener('click', () => {
    askConfirm('ログアウトします。再び見るにはログインが必要です。よろしいですか？',
      () => { doLogout('manual'); }, 'ログアウト');
  }); }
  { const rb = $('#authRetryBtn'); if (rb) rb.addEventListener('click', async () => {
    rb.disabled = true; const t = rb.textContent; rb.textContent = '接続中…';
    showConnecting();
    try { await detectServer(); } catch (_) {}
    if (serverMode()) { try { await reloadFromServer(); } catch (_) {} setMode('nav'); }
    rb.disabled = false; rb.textContent = t;
  }); }

  // ヘッダーの接続チップ：未接続（この端末のみ）のときタップでサーバーへ接続し直す。
  // まず再接続を試み、それでも繋がらなければログイン画面を出すため再読み込みを促す。
  { const chip = $('#connChip'); if (chip) chip.addEventListener('click', async () => {
    if (serverMode()) return; // 共有中のときは何もしない
    chip.classList.add('is-busy');
    chip.textContent = '● 接続中…';
    try { await detectServer(); } catch (_) {}
    if (authRequired) { return; } // ログインゲートが表示される
    if (serverMode()) {
      try { syncMsg('サーバーに接続中…'); await reloadFromServer(); } catch (_) {}
      if (!editView.hidden) renderEdit(); else navRestart();
      syncMsg('サーバーに接続しました。最新の情報を表示しています');
      return;
    }
    setServerStatus(); // チップ表示を元に戻す
    if (serverAvailable && !dbConnected) {
      syncMsg('サーバーには繋がりましたが、データベースに接続できません（管理者に確認してください）', true);
      return;
    }
    askConfirm(
      'サーバーに接続できませんでした。ログイン画面を表示して接続をやり直しますか？（ログイン後、最新の情報が表示されます）',
      () => { try { location.reload(); } catch (_) {} },
      'ログインして接続'
    );
  }); }

  $('#reloadBtn').addEventListener('click', async () => {
    if (!serverMode()) { syncMsg('サーバー未接続です', true); return; }
    try {
      syncMsg('最新に更新中…');
      await reloadFromServer();
      if (!editView.hidden) renderEdit(); else navRestart();
      syncMsg('最新に更新しました');
    } catch (e) { syncMsg('更新に失敗：' + e.message, true); }
  });

  $('#migrateBtn').addEventListener('click', () => {
    if (!serverMode()) { syncMsg('サーバー未接続です', true); return; }
    askConfirm('この端末の現在の内容で、サーバー（全員の共有データ）を置き換えて登録します。よろしいですか？', async () => {
      try {
        syncMsg('サーバーへ登録中…');
        await apiCall('replace_all', { method: 'POST', body: { nodes: treeToRowsMeta() } });
        await reloadFromServer();
        renderEdit();
        syncMsg('サーバーへ登録しました');
      } catch (e) { syncMsg('登録に失敗：' + e.message, true); }
    }, '登録する');
  });

  /* ---------- 他端末の変更を反映（軽いポーリング + 可視化時） ---------- */
  async function pollTick() {
    if (!serverMode() || document.hidden) return;
    if (nodeDialog.open || confirmDialog.open) return; // 編集中は邪魔しない
    try {
      const data = await apiCall('tree');
      const rows = data.nodes || [];
      const h = rowsHash(rows);
      if (h !== lastTreeHash) {
        applyRows(rows);
        await reapplyUnlocks();
        if (!editView.hidden) renderEdit(); else renderNav();
        syncMsg('他の端末の変更を反映しました');
      }
    } catch (e) { /* 一時的なエラーは無視 */ }
  }
  setInterval(pollTick, 30000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) pollTick(); });

  /* ============================================================
     MODE SWITCH
     ============================================================ */
  const navView = $('#navView');
  const editView = $('#editView');
  const invViewEl = $('#invView');
  const navModeBtn = $('#navModeBtn');
  const editModeBtn = $('#editModeBtn');

  function setMode(mode) {
    const isNav = mode === 'nav';
    navReorder = false; // モード切替時は簡易並べ替えを解除
    invOpen = false;    // モード切替時は在庫管理を閉じる
    invAdmin = false;
    if (invViewEl) invViewEl.hidden = true;
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
      updateEditLock();
      renderEdit();
    }
  }
  navModeBtn.addEventListener('click', () => { setMode('nav'); syncTrap(); });
  editModeBtn.addEventListener('click', () => { setMode('edit'); syncTrap(); });

  /* ============================================================
     INVENTORY（在庫管理）
     ============================================================ */
  const INV_ITEMS_KEY = 'treeManual.inv.items.v1';
  const INV_LOGS_KEY = 'treeManual.inv.logs.v1';
  let invItems = [];
  let invAdmin = false;         // 在庫の管理者編集モード（数量修正・履歴修正・商品編集/削除）
  let invReturnMode = 'nav';    // 在庫を閉じたときに戻るモード（nav / edit）
  const invAdminCreds = () => ({ admin: ADMIN_PW }); // 管理者操作の送信用

  const invTableWrap = $('#invTableWrap');
  const invMsgEl = $('#invMsg');
  function invSetMsg(m, isErr) { if (invMsgEl) { invMsgEl.textContent = m || ''; invMsgEl.style.color = isErr ? 'var(--tm-danger)' : ''; } }

  // オフライン（この端末のみ）用ストア
  function invLoadLocal() { try { return JSON.parse(localStorage.getItem(INV_ITEMS_KEY) || '[]'); } catch (_) { return []; } }
  function invSaveLocal(items) { try { localStorage.setItem(INV_ITEMS_KEY, JSON.stringify(items)); } catch (_) {} }
  function invLoadLogs() { try { return JSON.parse(localStorage.getItem(INV_LOGS_KEY) || '[]'); } catch (_) { return []; } }
  function invSaveLogs(logs) { try { localStorage.setItem(INV_LOGS_KEY, JSON.stringify(logs)); } catch (_) {} }
  function invAddLocalLog(item_id, action, qty, balance, person, note) {
    const logs = invLoadLogs();
    logs.push({ id: uid(), item_id, action, qty, balance, person, note, created_at: Date.now() });
    invSaveLogs(logs);
  }

  async function invFetch() {
    if (serverMode()) { const d = await apiCall('inv_list'); invItems = d.items || []; }
    else { invItems = invLoadLocal(); }
  }

  function openInventory(opts) {
    opts = opts || {};
    invReturnMode = !editView.hidden ? 'edit' : 'nav';
    invAdmin = !!opts.admin;
    invOpen = true;
    navView.hidden = true; if (invViewEl) invViewEl.hidden = false; editView.hidden = true;
    breadcrumbBar.style.display = 'none';
    syncTrap();
    invSetMsg(serverMode() ? '読み込み中…' : 'この端末のみで保存中（サーバー未接続）');
    invTableWrap.innerHTML = '';
    invFetch().then(() => { if (serverMode()) invSetMsg(''); renderInventory(); })
      .catch((e) => { invSetMsg('読み込みに失敗：' + e.message, true); renderInventory(); });
  }
  function closeInventory() {
    invAdmin = false;
    setMode(invReturnMode === 'edit' ? 'edit' : 'nav'); // invOpen=false・invView非表示・元モードを表示
    syncTrap();
  }

  function renderInventory() {
    if (!invTableWrap) return;
    const adminBar = `<div class="tm-inv-adminbar">
      ${invAdmin
        ? '<span class="tm-inv-adminlabel">&#128295; 管理者編集モード（数量・履歴の修正が可能）</span><button class="tm-inv-btn is-plain" data-adm="off" type="button">通常表示に戻す</button>'
        : '<span class="tm-inv-adminlabel" style="color:var(--tm-muted)">数量や過去履歴の修正には管理者パスワードが必要です</span><button class="tm-inv-btn is-adjust" data-adm="on" type="button">&#128274; 管理者編集</button>'}
    </div>`;
    if (!invItems.length) {
      invTableWrap.innerHTML = adminBar + '<div class="tm-inv-empty">まだ商品が登録されていません。「＋ 商品を追加」から登録してください。</div>';
      return;
    }
    const rows = invItems.map((it) => {
      const q = Number(it.qty) || 0;
      const cls = q <= 0 ? 'is-zero' : (q <= 2 ? 'is-low' : 'is-ok');
      const adminBtns = invAdmin ? `
          <button class="tm-inv-btn is-adjust" data-act="adjust" data-id="${it.id}" type="button">数量修正</button>
          <button class="tm-inv-btn is-plain" data-act="edit" data-id="${it.id}" type="button">編集</button>
          <button class="tm-inv-btn is-danger" data-act="del" data-id="${it.id}" type="button">削除</button>` : '';
      return `<tr>
        <td><div class="tm-inv-name">${esc(it.name)}</div>${it.note ? `<div class="tm-inv-itemnote">${esc(it.note)}</div>` : ''}</td>
        <td><span class="tm-inv-model">${esc(it.model || '—')}</span></td>
        <td><div class="tm-inv-qty ${cls}">${q}</div></td>
        <td><div class="tm-inv-actions">
          <button class="tm-inv-btn is-out" data-act="out" data-id="${it.id}" type="button">− 持ち出し</button>
          <button class="tm-inv-btn is-return" data-act="return" data-id="${it.id}" type="button">＋ 返却</button>
          <button class="tm-inv-btn is-plain" data-act="history" data-id="${it.id}" type="button">履歴</button>${adminBtns}
        </div></td>
      </tr>`;
    }).join('');
    invTableWrap.innerHTML = adminBar + `<table class="tm-inv-table">
      <thead><tr><th>商品名</th><th>型番</th><th>現在個数</th><th>操作</th></tr></thead>
      <tbody>${rows}</tbody></table>`;
  }
  async function toggleInvAdmin(on) {
    if (on) { if (!(await askAdmin())) return; invAdmin = true; }
    else invAdmin = false;
    renderInventory();
  }

  if (invTableWrap) {
    invTableWrap.addEventListener('click', (e) => {
      const adm = e.target.closest('[data-adm]');
      if (adm) { toggleInvAdmin(adm.dataset.adm === 'on'); return; }
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      const id = btn.dataset.id, act = btn.dataset.act;
      if (act === 'history') openInvHistory(id);
      else if (act === 'edit') openInvItemDialog(id);
      else if (act === 'del') confirmInvDelete(id);
      else openInvActionDialog(id, act); // out / return / adjust
    });
  }
  $('#invAddBtn').addEventListener('click', () => openInvItemDialog(null));
  $('#invReloadBtn').addEventListener('click', async () => {
    invSetMsg('更新中…');
    try { await invFetch(); invSetMsg(''); renderInventory(); }
    catch (e) { invSetMsg('更新に失敗：' + e.message, true); }
  });
  $('#invBackBtn').addEventListener('click', () => closeInventory());
  // 編集モードから「在庫を修正（管理者）」で在庫を管理者編集モードで開く
  const invEditBtn = $('#invEditBtn');
  if (invEditBtn) invEditBtn.addEventListener('click', async () => {
    if (!(await askAdmin())) return;
    openInventory({ admin: true });
  });

  /* ---- 在庫: 商品の追加/編集ダイアログ ---- */
  const invItemDialog = $('#invItemDialog');
  const invItemForm = $('#invItemForm');
  let invEditId = null;
  function openInvItemDialog(id) {
    invEditId = id;
    const qtyField = $('#invQtyField');
    const delBtn = $('#invItemDelete');
    $('#invItemError').textContent = '';
    if (id) {
      const it = invItems.find((x) => x.id === id);
      if (!it) return;
      $('#invItemTitle').textContent = '商品を編集';
      $('#invName').value = it.name || '';
      $('#invModel').value = it.model || '';
      $('#invItemNote').value = it.note || '';
      if (qtyField) qtyField.hidden = true; // 個数は持ち出し/返却で変更
      if (delBtn) delBtn.hidden = false;
    } else {
      $('#invItemTitle').textContent = '商品を追加';
      $('#invName').value = ''; $('#invModel').value = ''; $('#invItemNote').value = ''; $('#invQty').value = '0';
      if (qtyField) qtyField.hidden = false;
      if (delBtn) delBtn.hidden = true;
    }
    invItemDialog.showModal();
    $('#invName').focus();
  }
  invItemForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = $('#invName').value.trim();
    if (!name) { $('#invItemError').textContent = '商品名を入力してください'; return; }
    const model = $('#invModel').value.trim();
    const note = $('#invItemNote').value.trim();
    const qty = parseInt($('#invQty').value, 10) || 0;
    const btn = invItemForm.querySelector('button[type=submit]');
    btn.disabled = true;
    try {
      if (invEditId) await invUpdateItem(invEditId, { name, model, note });
      else await invCreateItem({ name, model, note, qty });
      invItemDialog.close();
      await invFetch(); renderInventory();
    } catch (err) { $('#invItemError').textContent = '保存に失敗：' + err.message; }
    finally { btn.disabled = false; }
  });
  $('#invItemCancel').addEventListener('click', () => invItemDialog.close());
  function confirmInvDelete(id) {
    const it = invItems.find((x) => x.id === id);
    if (!it) return;
    askConfirm(`商品「${it.name}」と、その使用履歴を削除しますか？この操作は元に戻せません。`, async () => {
      try { await invDeleteItem(id); if (invItemDialog.open) invItemDialog.close(); await invFetch(); renderInventory(); }
      catch (err) { invSetMsg('削除に失敗：' + err.message, true); }
    }, '削除する');
  }
  $('#invItemDelete').addEventListener('click', () => { if (invEditId) confirmInvDelete(invEditId); });

  /* ---- 在庫: 持ち出し/返却/使用ダイアログ ---- */
  const invActionDialog = $('#invActionDialog');
  const invActionForm = $('#invActionForm');
  let invActionId = null, invActionKind = 'out';
  const INV_KIND_LABEL = { out: '持ち出し', return: '返却（戻す）', use: '使用（消費）', adjust: '数量修正' };
  function setInvKind(kind) {
    invActionKind = kind;
    const box = $('#invActionKind');
    box.querySelectorAll('.tm-inv-kind').forEach((b) => b.classList.toggle('is-active', b.dataset.kind === kind));
    $('#invActionTitle').textContent = INV_KIND_LABEL[kind] || '操作';
    $('#invActionSubmit').textContent = INV_KIND_LABEL[kind] || '実行';
    const qtyLabel = $('#invActionQtyLabel');
    if (qtyLabel) qtyLabel.firstChild.textContent = (kind === 'adjust' ? '正しい在庫数（棚卸し） ' : '個数 ');
  }
  function openInvActionDialog(id, kind) {
    const it = invItems.find((x) => x.id === id);
    if (!it) return;
    invActionId = id;
    const cur = Number(it.qty) || 0;
    kind = kind || 'out';
    $('#invActionItem').textContent = `${it.name}${it.model ? '（' + it.model + '）' : ''} ／ 現在 ${cur} 個`;
    // 管理者編集モードのときだけ「数量修正」種別を表示
    const adjBtn = $('#invActionKind').querySelector('[data-kind=adjust]');
    if (adjBtn) adjBtn.hidden = !invAdmin;
    $('#invActionQty').value = kind === 'adjust' ? String(cur) : '1';
    $('#invActionQty').min = kind === 'adjust' ? '0' : '1';
    $('#invActionPerson').value = authorName();
    $('#invActionNote').value = '';
    $('#invActionError').textContent = '';
    setInvKind(kind);
    invActionDialog.showModal();
    $('#invActionQty').focus(); $('#invActionQty').select();
  }
  $('#invActionKind').addEventListener('click', (e) => {
    const b = e.target.closest('[data-kind]');
    if (!b) return;
    setInvKind(b.dataset.kind);
    if (b.dataset.kind === 'adjust') { const it = invItems.find((x) => x.id === invActionId); $('#invActionQty').value = String(Number(it && it.qty) || 0); $('#invActionQty').min = '0'; }
    else { $('#invActionQty').min = '1'; }
  });
  invActionForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const qty = parseInt($('#invActionQty').value, 10);
    if (invActionKind !== 'adjust' && (!qty || qty <= 0)) { $('#invActionError').textContent = '個数は1以上を入力してください'; return; }
    if (invActionKind === 'adjust' && (isNaN(qty) || qty < 0)) { $('#invActionError').textContent = '在庫数は0以上を入力してください'; return; }
    const person = $('#invActionPerson').value.trim();
    const note = $('#invActionNote').value.trim();
    const btn = $('#invActionSubmit');
    btn.disabled = true;
    try {
      await invDoAction(invActionId, invActionKind, qty, person, note);
      invActionDialog.close();
      await invFetch(); renderInventory();
    } catch (err) { $('#invActionError').textContent = err.message; }
    finally { btn.disabled = false; }
  });
  $('#invActionCancel').addEventListener('click', () => invActionDialog.close());

  /* ---- 在庫: 使用履歴ダイアログ ---- */
  const invHistoryDialog = $('#invHistoryDialog');
  const INV_ACT_LABEL = { out: '持ち出し', return: '返却', use: '使用', init: '初期登録', adjust: '調整' };
  function invFmtWhen(ms) {
    const d = new Date(Number(ms) || 0);
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  let invHistoryItemId = null;
  let invHistoryLogs = [];
  async function openInvHistory(id) {
    const it = invItems.find((x) => x.id === id);
    invHistoryItemId = id;
    $('#invHistoryTitle').textContent = `使用履歴：${it ? it.name : ''}`;
    const body = $('#invHistoryBody');
    body.innerHTML = '<div class="tm-inv-empty">読み込み中…</div>';
    invHistoryDialog.showModal();
    try {
      const logs = await invFetchHistory(id);
      invHistoryLogs = logs;
      if (!logs.length) { body.innerHTML = '<div class="tm-inv-empty">履歴はありません。</div>'; return; }
      body.innerHTML = logs.map((l) => {
        const sign = (l.action === 'return' || l.action === 'init') ? '＋'
          : (l.action === 'adjust' ? (Number(l.qty) >= 0 ? '＋' : '−') : '−');
        const qtyAbs = Math.abs(Number(l.qty) || 0);
        const editRow = invAdmin ? `<span class="tm-inv-logedit">
          <button type="button" data-logedit="${l.id}">修正</button>
          <button type="button" data-logdel="${l.id}">削除</button>
        </span>` : '';
        return `<div class="tm-inv-logrow">
          <span class="tm-inv-when">${invFmtWhen(l.created_at)}</span>
          <span class="tm-inv-badge a-${l.action}">${INV_ACT_LABEL[l.action] || l.action}</span>
          <span class="tm-inv-logmain">${sign}${qtyAbs}個　${esc(l.person || '—')}</span>
          <span class="tm-inv-logbal">残 ${Number(l.balance) || 0}</span>
          ${l.note ? `<span class="tm-inv-lognote">${esc(l.note)}</span>` : ''}
          ${editRow}
        </div>`;
      }).join('');
    } catch (e) { body.innerHTML = '<div class="tm-inv-empty">履歴の取得に失敗しました。</div>'; }
  }
  $('#invHistoryClose').addEventListener('click', () => invHistoryDialog.close());
  $('#invHistoryBody').addEventListener('click', (e) => {
    const ed = e.target.closest('[data-logedit]');
    if (ed) { openInvLogDialog(ed.dataset.logedit); return; }
    const dl = e.target.closest('[data-logdel]');
    if (dl) {
      askConfirm('この履歴を削除しますか？（在庫数は履歴から再計算されます）', async () => {
        try { await invLogDelete(dl.dataset.logdel); await refreshHistoryAndTable(); }
        catch (err) { alert('削除に失敗：' + err.message); }
      }, '削除する');
    }
  });
  async function refreshHistoryAndTable() {
    await invFetch(); renderInventory();
    if (invHistoryItemId) await openInvHistory(invHistoryItemId);
  }

  /* ---- 在庫: 履歴の修正ダイアログ（管理者） ---- */
  const invLogDialog = $('#invLogDialog');
  const invLogForm = $('#invLogForm');
  let invLogEditId = null;
  function toLocalInput(ms) {
    const d = new Date(Number(ms) || 0);
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  function openInvLogDialog(logId) {
    const l = invHistoryLogs.find((x) => x.id === logId);
    if (!l) return;
    invLogEditId = logId;
    $('#invLogKind').textContent = `種別：${INV_ACT_LABEL[l.action] || l.action}（持ち出し/使用は減算、返却/初期は加算、調整は差分）`;
    $('#invLogQty').value = String(Number(l.qty) || 0);
    $('#invLogPerson').value = l.person || '';
    $('#invLogWhen').value = toLocalInput(l.created_at);
    $('#invLogNote').value = l.note || '';
    $('#invLogError').textContent = '';
    invLogDialog.showModal();
  }
  invLogForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const qty = parseInt($('#invLogQty').value, 10);
    const person = $('#invLogPerson').value.trim();
    const note = $('#invLogNote').value.trim();
    const whenVal = $('#invLogWhen').value;
    const created_at = whenVal ? new Date(whenVal).getTime() : null;
    const btn = invLogForm.querySelector('button[type=submit]');
    btn.disabled = true;
    try {
      await invLogUpdate(invLogEditId, { qty: isNaN(qty) ? undefined : qty, person, note, created_at });
      invLogDialog.close();
      await refreshHistoryAndTable();
    } catch (err) { $('#invLogError').textContent = '保存に失敗：' + err.message; }
    finally { btn.disabled = false; }
  });
  $('#invLogCancel').addEventListener('click', () => invLogDialog.close());
  $('#invLogDelete').addEventListener('click', () => {
    if (!invLogEditId) return;
    const lid = invLogEditId;
    askConfirm('この履歴を削除しますか？（在庫数は履歴から再計算されます）', async () => {
      try { await invLogDelete(lid); invLogDialog.close(); await refreshHistoryAndTable(); }
      catch (err) { $('#invLogError').textContent = '削除に失敗：' + err.message; }
    }, '削除する');
  });

  /* ---- 在庫: 操作（サーバー / オフライン） ---- */
  async function invCreateItem({ name, model, note, qty }) {
    if (serverMode()) {
      await apiCall('inv_item_create', { method: 'POST', body: { name, model, note, qty, author: authorName() } });
    } else {
      const now = Date.now(), id = uid();
      invItems = invLoadLocal();
      const q = Math.max(0, qty | 0);
      invItems.push({ id, name, model, note, qty: q, created_at: now, updated_at: now });
      invSaveLocal(invItems);
      if (q > 0) invAddLocalLog(id, 'init', q, q, authorName(), '初期登録');
    }
  }
  async function invUpdateItem(id, { name, model, note }) {
    if (serverMode()) {
      await apiCall('inv_item_update', { method: 'POST', body: Object.assign({ id, name, model, note }, invAdminCreds()) });
    } else {
      invItems = invLoadLocal();
      const it = invItems.find((x) => x.id === id);
      if (it) { it.name = name; it.model = model; it.note = note; it.updated_at = Date.now(); invSaveLocal(invItems); }
    }
  }
  async function invDeleteItem(id) {
    if (serverMode()) {
      await apiCall('inv_item_delete', { method: 'POST', body: Object.assign({ id }, invAdminCreds()) });
    } else {
      invSaveLocal(invLoadLocal().filter((x) => x.id !== id));
      invSaveLogs(invLoadLogs().filter((l) => l.item_id !== id));
    }
  }
  async function invDoAction(id, kind, qty, person, note) {
    if (serverMode()) {
      const body = { id, action: kind, qty, author: person, note };
      if (kind === 'adjust') Object.assign(body, invAdminCreds());
      await apiCall('inv_action', { method: 'POST', body });
    } else {
      invItems = invLoadLocal();
      const it = invItems.find((x) => x.id === id);
      if (!it) throw new Error('商品が存在しません');
      const cur = Number(it.qty) || 0;
      let newQty, logQty;
      if (kind === 'out' || kind === 'use') {
        if (qty > cur) throw new Error('現在個数（' + cur + '）を超える数は指定できません');
        newQty = cur - qty; logQty = qty;
      } else if (kind === 'return') { newQty = cur + qty; logQty = qty; }
      else { newQty = qty < 0 ? 0 : qty; logQty = newQty - cur; }
      it.qty = newQty; it.updated_at = Date.now(); invSaveLocal(invItems);
      invAddLocalLog(id, kind, logQty, newQty, person || authorName(), note);
    }
  }
  // オフライン用：履歴から在庫と残数を再計算
  function invRecalcLocal(itemId) {
    const logs = invLoadLogs().filter((l) => l.item_id === itemId)
      .sort((a, b) => (Number(a.created_at) || 0) - (Number(b.created_at) || 0));
    let bal = 0;
    const all = invLoadLogs();
    logs.forEach((l) => {
      const n = Number(l.qty) || 0;
      bal += (l.action === 'out' || l.action === 'use') ? -n : n;
      const t = all.find((x) => x.id === l.id); if (t) t.balance = bal;
    });
    invSaveLogs(all);
    const items = invLoadLocal();
    const it = items.find((x) => x.id === itemId);
    if (it) { it.qty = bal; it.updated_at = Date.now(); invSaveLocal(items); }
    return bal;
  }
  async function invLogUpdate(logId, fields) {
    if (serverMode()) {
      const body = Object.assign({ id: logId }, invAdminCreds());
      if (fields.qty !== undefined) body.qty = fields.qty;
      if (fields.person !== undefined) body.person = fields.person;
      if (fields.note !== undefined) body.note = fields.note;
      if (fields.created_at) body.created_at = fields.created_at;
      await apiCall('inv_log_update', { method: 'POST', body });
    } else {
      const all = invLoadLogs();
      const l = all.find((x) => x.id === logId);
      if (!l) throw new Error('履歴が存在しません');
      if (fields.qty !== undefined) l.qty = fields.qty;
      if (fields.person !== undefined) l.person = fields.person;
      if (fields.note !== undefined) l.note = fields.note;
      if (fields.created_at) l.created_at = fields.created_at;
      invSaveLogs(all);
      invRecalcLocal(l.item_id);
    }
  }
  async function invLogDelete(logId) {
    if (serverMode()) {
      await apiCall('inv_log_delete', { method: 'POST', body: Object.assign({ id: logId }, invAdminCreds()) });
    } else {
      const all = invLoadLogs();
      const l = all.find((x) => x.id === logId);
      if (!l) return;
      const itemId = l.item_id;
      invSaveLogs(all.filter((x) => x.id !== logId));
      invRecalcLocal(itemId);
    }
  }
  async function invFetchHistory(id) {
    if (serverMode()) {
      const d = await apiCall('inv_history', { method: 'POST', body: { item_id: id || '' } });
      return d.logs || [];
    }
    let logs = invLoadLogs().filter((l) => !id || l.item_id === id);
    logs.sort((a, b) => (Number(b.created_at) || 0) - (Number(a.created_at) || 0));
    return logs;
  }

  /* ============================================================
     検索（キーワード）— どのページからでも利用可
     ============================================================ */
  const searchDialog = $('#searchDialog');
  const searchInput = $('#searchInput');
  const searchResultsEl = $('#searchResults');
  const searchMetaEl = $('#searchMeta');
  let searchResultData = [];

  // 一致部分を <mark> で強調（エスケープ済み）
  function highlightHtml(text, q) {
    const s = String(text);
    if (!q) return esc(s);
    const lower = s.toLowerCase(), ql = q.toLowerCase();
    let out = '', i = 0;
    for (;;) {
      const idx = lower.indexOf(ql, i);
      if (idx < 0) { out += esc(s.slice(i)); break; }
      out += esc(s.slice(i, idx)) + '<mark class="tm-sr-mark">' + esc(s.slice(idx, idx + ql.length)) + '</mark>';
      i = idx + ql.length;
    }
    return out;
  }
  function makeSnippet(plain, q) {
    const ql = q.toLowerCase();
    const idx = plain.toLowerCase().indexOf(ql);
    if (idx < 0) return plain.slice(0, 70);
    const start = Math.max(0, idx - 26);
    const end = Math.min(plain.length, idx + q.length + 44);
    return (start > 0 ? '…' : '') + plain.slice(start, end) + (end < plain.length ? '…' : '');
  }
  function searchAll(query) {
    const q = query.trim();
    if (!q) return [];
    const ql = q.toLowerCase();
    const out = [];
    const walk = (nodes, pathTitles, pathIds) => {
      for (const n of nodes) {
        const title = n.title || '';
        const inTitle = title.toLowerCase().includes(ql);
        const bodyPlain = htmlToPlain(n.body || '');
        const inBody = bodyPlain.toLowerCase().includes(ql);
        if (inTitle || inBody) {
          out.push({
            kind: 'node', id: n.id, title,
            pathTitles: pathTitles.slice(), idPath: pathIds.concat(n.id),
            locked: isLocked(n), snippet: inBody ? makeSnippet(bodyPlain, q) : '',
          });
        }
        if (n.children && n.children.length) walk(n.children, pathTitles.concat(title), pathIds.concat(n.id));
      }
    };
    walk(tree, [], []);
    // 在庫（商品名・型番・メモ）も対象
    (invItems || []).forEach((it) => {
      const hay = ((it.name || '') + ' ' + (it.model || '') + ' ' + (it.note || '')).toLowerCase();
      if (hay.includes(ql)) out.push({ kind: 'inv', title: it.name || '', model: it.model || '', qty: it.qty });
    });
    return out;
  }
  function renderSearchResults() {
    const q = searchInput.value;
    if (!q.trim()) {
      searchMetaEl.textContent = '';
      searchResultsEl.innerHTML = '<div class="tm-sr-empty">キーワードを入力すると、一致する項目（と在庫商品）を探します。<br>結果には、その項目がどの階層にあるかも表示されます。</div>';
      searchResultData = [];
      return;
    }
    const results = searchAll(q);
    searchResultData = results;
    searchMetaEl.textContent = `${results.length} 件ヒット`;
    if (!results.length) {
      searchResultsEl.innerHTML = '<div class="tm-sr-empty">一致する項目は見つかりませんでした。</div>';
      return;
    }
    searchResultsEl.innerHTML = results.slice(0, 100).map((r, i) => {
      if (r.kind === 'inv') {
        return `<button class="tm-sr-item is-inv" data-idx="${i}" type="button">
          <div class="tm-sr-path"><span class="tm-sr-root">&#128230; 在庫管理</span></div>
          <div class="tm-sr-title">${highlightHtml(r.title, q)}${r.model ? `<span class="tm-sr-tag">${esc(r.model)}</span>` : ''}<span class="tm-sr-tag">在庫 ${Number(r.qty) || 0}</span></div>
        </button>`;
      }
      const pathHtml = '<span class="tm-sr-root">TOP</span>' +
        r.pathTitles.map((t) => `<span class="tm-sr-sep">&#8250;</span><span class="tm-sr-seg">${esc(t)}</span>`).join('');
      return `<button class="tm-sr-item" data-idx="${i}" type="button">
        <div class="tm-sr-path">${pathHtml}</div>
        <div class="tm-sr-title">${highlightHtml(r.title, q)}${r.locked ? '<span class="tm-sr-tag">&#128274;</span>' : ''}</div>
        ${r.snippet ? `<div class="tm-sr-snippet">${highlightHtml(r.snippet, q)}</div>` : ''}
      </button>`;
    }).join('');
  }

  function openSearch() {
    searchOpen = true;
    searchInput.value = '';
    renderSearchResults();
    const aiRow = $('#aiSearchRow'); if (aiRow) aiRow.hidden = !aiEnabled; // AIキー未設定なら非表示
    searchDialog.showModal();
    syncTrap();
    setTimeout(() => searchInput.focus(), 30);
    // 在庫商品も検索対象にするため、サーバー接続時は最新を取得（ベストエフォート）
    if (serverMode()) { invFetch().then(() => { if (searchOpen) renderSearchResults(); }).catch(() => {}); }
  }
  // AIで探す（意味検索）。質問文から関連項目をAIが選ぶ。
  async function runAiSearch() {
    const q = searchInput.value.trim();
    if (!q) { searchMetaEl.textContent = ''; searchResultsEl.innerHTML = '<div class="tm-sr-empty">質問やキーワードを入力してから「AIで探す」を押してください。</div>'; return; }
    if (!serverMode()) { searchResultsEl.innerHTML = '<div class="tm-sr-empty">AI検索はサーバー接続時のみ利用できます。</div>'; return; }
    searchMetaEl.textContent = 'AIが探しています…';
    searchResultsEl.innerHTML = '<div class="tm-sr-empty">&#10024; AIが関連する項目を探しています…</div>';
    try {
      const d = await apiCall('ai_search', { method: 'POST', body: { q } });
      const results = (d.results || []).map((r) => {
        const path = findPath(r.id);
        if (!path) return null;
        const node = path[path.length - 1];
        return { kind: 'node', id: r.id, title: node.title, reason: r.reason || '',
          pathTitles: path.slice(0, -1).map((n) => n.title), idPath: path.map((n) => n.id), locked: isLocked(node) };
      }).filter(Boolean);
      searchResultData = results;
      searchMetaEl.innerHTML = `&#10024; AIの候補 ${results.length} 件`;
      if (!results.length) { searchResultsEl.innerHTML = '<div class="tm-sr-empty">関連する項目が見つかりませんでした。言い方を変えて再度お試しください。</div>'; return; }
      searchResultsEl.innerHTML = results.map((r, i) => {
        const pathHtml = '<span class="tm-sr-root">TOP</span>' +
          r.pathTitles.map((t) => `<span class="tm-sr-sep">&#8250;</span><span class="tm-sr-seg">${esc(t)}</span>`).join('');
        return `<button class="tm-sr-item" data-idx="${i}" type="button">
          <div class="tm-sr-path"><span class="tm-sr-aibadge">&#10024; AI</span>${pathHtml}</div>
          <div class="tm-sr-title">${esc(r.title)}${r.locked ? '<span class="tm-sr-tag">&#128274;</span>' : ''}</div>
          ${r.reason ? `<div class="tm-sr-snippet">${esc(r.reason)}</div>` : ''}
        </button>`;
      }).join('');
    } catch (e) {
      searchMetaEl.textContent = '';
      searchResultsEl.innerHTML = `<div class="tm-sr-empty">AI検索に失敗しました：${esc(e.message)}</div>`;
    }
  }
  { const aib = $('#aiSearchBtn'); if (aib) aib.addEventListener('click', runAiSearch); }
  let searchDebounce = null;
  searchInput.addEventListener('input', () => {
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(renderSearchResults, 110);
  });
  searchResultsEl.addEventListener('click', (e) => {
    const el = e.target.closest('.tm-sr-item');
    if (!el) return;
    const r = searchResultData[parseInt(el.dataset.idx, 10)];
    if (!r) return;
    if (r.kind === 'inv') { closeSearchThen(() => openInventory()); return; }
    gotoNodePath(r.idPath);
  });
  function closeSearchThen(after) {
    searchOpen = false;
    if (searchDialog.open) searchDialog.close();
    if (after) after();
  }
  async function gotoNodePath(idPath) {
    // ロックされた祖先はパスワードを確認しながら辿る
    const newPath = [];
    for (const id of idPath) {
      const node = findNode(id);
      if (!node) break;
      if (isLocked(node)) { if (!(await ensureUnlocked(node))) break; }
      newPath.push(id);
    }
    searchOpen = false; updatesOpen = false;
    setMode('nav');
    navPath = newPath;
    renderNav();
    if (searchDialog.open) searchDialog.close();
    if (updatesDialog.open) updatesDialog.close();
    syncTrap();
  }
  // ダイアログが閉じられたら（ボタン・Escape・戻る いずれでも）状態を同期
  searchDialog.addEventListener('close', () => { searchOpen = false; syncTrap(); });
  $('#searchBtn').addEventListener('click', openSearch);
  $('#searchClose').addEventListener('click', () => searchDialog.close());

  /* ============================================================
     更新履歴（新規登録・更新のバックナンバー）
     ============================================================ */
  const updatesDialog = $('#updatesDialog');
  const updatesResultsEl = $('#updatesResults');
  const updatesMetaEl = $('#updatesMeta');
  let updatesData = [];
  function updFmtWhen(ms) {
    const d = new Date(Number(ms) || 0);
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  function updFmtDay(ms) {
    const d = new Date(Number(ms) || 0);
    if (isNaN(d.getTime())) return '日付なし';
    const p = (n) => String(n).padStart(2, '0');
    const w = ['日', '月', '火', '水', '木', '金', '土'][d.getDay()];
    return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())}（${w}）`;
  }
  function buildRecentUpdates() {
    const out = [];
    const walk = (nodes, pathTitles, pathIds) => {
      for (const n of nodes) {
        const ua = Number(n.updated_at) || 0, ca = Number(n.created_at) || 0;
        const when = ua || ca;
        const isNew = !ca || ua <= ca; // 作成後に更新されていなければ「新規」
        out.push({
          id: n.id, title: n.title || '', pathTitles: pathTitles.slice(), idPath: pathIds.concat(n.id),
          when, isNew, locked: isLocked(n),
          who: (isNew ? (n.created_by || n.updated_by) : (n.updated_by || n.created_by)) || '',
        });
        if (n.children && n.children.length) walk(n.children, pathTitles.concat(n.title || ''), pathIds.concat(n.id));
      }
    };
    walk(tree, [], []);
    out.sort((a, b) => (b.when || 0) - (a.when || 0));
    return out;
  }
  function renderUpdates() {
    const list = buildRecentUpdates();
    updatesData = list;
    if (!list.length) {
      updatesMetaEl.textContent = '';
      updatesResultsEl.innerHTML = '<div class="tm-sr-empty">まだ登録・更新の履歴はありません。</div>';
      return;
    }
    updatesMetaEl.textContent = `直近の登録・更新（新しい順・最大80件）`;
    const top = list.slice(0, 80);
    let html = '', lastDay = '';
    top.forEach((r, i) => {
      const day = updFmtDay(r.when);
      if (day !== lastDay) { html += `<div class="tm-upd-day">${day}</div>`; lastDay = day; }
      const pathHtml = '<span class="tm-sr-root">TOP</span>' +
        r.pathTitles.map((t) => `<span class="tm-sr-sep">&#8250;</span><span class="tm-sr-seg">${esc(t)}</span>`).join('');
      html += `<button class="tm-sr-item" data-idx="${i}" type="button">
        <div class="tm-sr-path">${pathHtml}</div>
        <div class="tm-sr-title">${esc(r.title)}${r.locked ? ' &#128274;' : ''} <span class="tm-sr-tag ${r.isNew ? 'is-new' : 'is-upd'}">${r.isNew ? '新規' : '更新'}</span></div>
        <div class="tm-sr-snippet">${r.who ? esc(r.who) + '　' : ''}${updFmtWhen(r.when)}</div>
      </button>`;
    });
    updatesResultsEl.innerHTML = html;
  }
  function openUpdates() {
    updatesOpen = true;
    renderUpdates();
    updatesDialog.showModal();
    syncTrap();
  }
  // 直近3日以内に登録・更新があれば履歴ボタンをピンクで強調
  function anyRecentUpdate() {
    const cutoff = Date.now() - 3 * 24 * 3600 * 1000;
    let found = false;
    const walk = (arr) => {
      for (const n of arr) {
        if (found) return;
        const t = Number(n.updated_at) || Number(n.created_at) || 0;
        if (t >= cutoff) { found = true; return; }
        if (n.children && n.children.length) walk(n.children);
      }
    };
    walk(tree);
    return found;
  }
  function refreshUpdatesBadge() {
    const btn = $('#updatesBtn');
    if (btn) btn.classList.toggle('is-fresh', anyRecentUpdate());
  }
  // 直近3日以内の更新の概要（件数と最新1件）
  function recentUpdatesInfo() {
    const cutoff = Date.now() - 3 * 24 * 3600 * 1000;
    const recent = buildRecentUpdates().filter((r) => (r.when || 0) >= cutoff);
    return { count: recent.length, latest: recent[0] || null };
  }
  updatesResultsEl.addEventListener('click', (e) => {
    const el = e.target.closest('.tm-sr-item');
    if (!el) return;
    const r = updatesData[parseInt(el.dataset.idx, 10)];
    if (r) gotoNodePath(r.idPath);
  });
  updatesDialog.addEventListener('close', () => { updatesOpen = false; syncTrap(); });
  $('#updatesBtn').addEventListener('click', openUpdates);
  $('#updatesClose').addEventListener('click', () => updatesDialog.close());

  /* ---------- 使い方（ヘルプ）ダイアログ ---------- */
  const helpDialog = $('#helpDialog');
  function openHelp() {
    if (!helpDialog) return;
    helpOpen = true;
    helpDialog.showModal();
    const body = helpDialog.querySelector('.tm-help-body');
    if (body) body.scrollTop = 0;
    syncTrap();
  }
  if (helpDialog) {
    helpDialog.addEventListener('close', () => { helpOpen = false; syncTrap(); });
    $('#helpClose').addEventListener('click', () => helpDialog.close());
    const footerHelp = $('#footerHelp');
    if (footerHelp) footerHelp.addEventListener('click', openHelp);
  }

  /* ============================================================
     PWA
     ============================================================ */
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    });
  }
  const DISMISS_KEY = 'treeManual.installDismissed.v1';
  const RENAG_MS = 7 * 24 * 60 * 60 * 1000; // 「あとで」から7日後に再表示
  let deferredPrompt = null;

  const installHint = $('#installHint');   // フッターの手動トリガー
  const installBtn = $('#installBtn');
  const banner = $('#installBanner');
  const installMsg = $('#installMsg');

  const isStandalone = () =>
    window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  const isIOS = () =>
    /iphone|ipad|ipod/i.test(navigator.userAgent) && !window.MSStream;
  const recentlyDismissed = () => {
    const t = Number(localStorage.getItem(DISMISS_KEY) || 0);
    return t && (Date.now() - t) < RENAG_MS;
  };

  function showBanner(mode) {
    if (isStandalone() || recentlyDismissed()) return;
    if (mode === 'ios') {
      banner.classList.add('is-ios');
      installMsg.innerHTML =
        'Safari で <b>共有</b> ボタン → <b>「ホーム画面に追加」</b> を選ぶと、アプリのように使えます。';
    }
    banner.hidden = false;
  }
  function hideBanner(remember) {
    banner.hidden = true;
    if (remember) localStorage.setItem(DISMISS_KEY, String(Date.now()));
  }

  async function runInstall() {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const res = await deferredPrompt.userChoice;
    deferredPrompt = null;
    installHint.hidden = true;
    hideBanner(res && res.outcome === 'dismissed');
  }

  // Android / デスクトップ Chrome 等：インストール可能になったら確認バナー表示
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    installHint.hidden = false;   // フッターにも常設の導線
    showBanner('prompt');
  });

  $('#installNow').addEventListener('click', runInstall);
  installBtn.addEventListener('click', () => {
    if (deferredPrompt) runInstall();
    else if (isIOS()) { localStorage.removeItem(DISMISS_KEY); showBanner('ios'); }
  });
  $('#installLater').addEventListener('click', () => hideBanner(true));
  $('#installClose').addEventListener('click', () => hideBanner(true));

  window.addEventListener('appinstalled', () => {
    installHint.hidden = true;
    hideBanner(false);
    deferredPrompt = null;
  });

  // iOS Safari は beforeinstallprompt 非対応 → 手動で案内バナーを表示
  if (isIOS() && !isStandalone()) {
    installHint.hidden = false;
    setTimeout(() => showBanner('ios'), 800);
  }

  /* ---------- boot ---------- */
  (async () => {
    // 接続が確認できるまではゲートを表示して中身（ローカルの古いデータ）を出さない
    showConnecting();
    setServerStatus();
    await detectServer();
    if (serverMode() && !loggedOut) {
      try { await reloadFromServer(); } catch (e) { /* サーバー接続済みなら再取得のみ */ }
    }
    setMode('nav');
    resetIdleTimer();
  })();
})();

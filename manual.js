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
  async function opUpdate(id, title, body, author, lockOpt) {
    const who = author != null ? author : authorName();
    if (serverMode()) {
      await apiCall('node_update', { method: 'POST', body: Object.assign({ id, title, body, author: who }, lockFields(lockOpt)) });
      await reloadFromServer();
    } else {
      const n = findNode(id);
      if (n) {
        n.title = title; n.body = body; n.updated_by = who || n.updated_by; n.updated_at = Date.now();
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
    br: { void: true }, div: { attrs: ['style'] }, p: { attrs: ['style'] },
    a: { attrs: ['href', 'style', 'class'] }, img: { attrs: ['src', 'style', 'class'], void: true },
  };
  function sanitizeClass(v) {
    const allow = { 'tm-filechip': 1, 'tm-body-img': 1 };
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
        attrs += ` ${a}="${esc(v)}"`;
      });
      if (tag === 'a') attrs += ' target="_blank" rel="noopener"';
      if (spec.void) { out.push(`<${tag}${attrs}>`); return; }
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
  const remainHint = $('#remainHint');

  // navPath: array of node ids representing current descent (empty = root)
  let navPath = [];
  let navReorder = false; // 案内モードの簡易並べ替えモード

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
    // DB未接続（サーバーには繋がるがDBに接続できない）ときは項目を出さずエラー表示
    if (serverAvailable && !dbConnected) {
      breadcrumbBar.innerHTML = `<span class="tm-crumb is-current" data-crumb-home>TOP</span>`;
      chatLog.innerHTML = `<div class="tm-naverror">
        <div class="tm-naverror-title">&#9888; データベースに接続できません</div>
        <p class="tm-naverror-msg">サーバーのデータベースに接続できないため、内容を表示できません。<br>
          管理者は <b>config.php</b> の設定（ホスト・DB名・ユーザー・パスワード）とPHPのバージョンをご確認ください。</p>
        ${dbError ? `<div class="tm-naverror-detail">詳細: ${esc(dbError)}</div>` : ''}
        <button class="tm-btn tm-btn-outline" id="navRetryBtn" type="button">&#8635; 再接続を試す</button>
      </div>`;
      choiceDock.className = 'tm-choicedock';
      choiceDock.innerHTML = '';
      navAddDock.innerHTML = '';
      backBtn.disabled = true;
      remainHint.textContent = '';
      return;
    }

    const atRoot = navPath.length === 0;
    const curNode = atRoot ? null : findNode(navPath[navPath.length - 1]);
    const kids = currentChildren();

    renderBreadcrumb();

    let html = '';
    if (atRoot) {
      html = `<div class="tm-hero">
        <div class="tm-hero-kicker">MANUAL NAVIGATOR</div>
        <h1 class="tm-hero-title">大項目を選択</h1>
        <p class="tm-hero-sub">当てはまるカテゴリを選ぶと、順に絞り込んで作業手順まで案内します。</p>
      </div>`;
    } else if (curNode) {
      const leaf = kids.length === 0;
      const stamp = editStampHtml(curNode);
      html += `<div class="tm-current">
        <div class="tm-current-head">
          <div class="tm-current-headtext">
            <div class="tm-current-kicker">現在地 · ${depthLabel(navPath.length - 1)}</div>
            <h2 class="tm-current-title">${esc(curNode.title)}</h2>
          </div>
          <button class="tm-editthis" data-editthis="${curNode.id}" type="button">&#9998; この項目を編集</button>
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
    chatLog.scrollTop = 0;

    const canReorder = kids.length > 1;
    if (navReorder && !canReorder) navReorder = false; // 並べ替え対象が無ければ解除

    if (kids.length > 0) {
      if (navReorder) {
        choiceDock.className = 'tm-choicedock tm-sortmode';
        choiceDock.innerHTML =
          `<div class="tm-sort-hint">&#8645; 並べ替え中：<strong>長押し</strong>（またはハンドル&#8942;&#8942;をドラッグ）や ▲▼ でこの階層の順番を変更できます。</div>` +
          kids.map((c, i) => sortRow(c, i, kids.length)).join('');
      } else if (atRoot) {
        choiceDock.className = 'tm-choicedock tm-cat-grid';
        choiceDock.innerHTML = kids.map((c, i) => categoryTile(c, i)).join('');
      } else {
        choiceDock.className = 'tm-choicedock';
        choiceDock.innerHTML = `<div class="tm-choice-label">${depthLabel(navPath.length)}を選択</div>` +
          kids.map((c) => choiceRow(c)).join('');
      }
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
  function needTrap() { return navPath.length > 0 || !editView.hidden || navReorder; }
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
  chatLog.addEventListener('click', (e) => {
    if (e.target.closest('#navRetryBtn')) { retryConnect(); return; }
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

  // エディタのHTMLをサニタイズし、空なら '' を返す
  function normalizeBody(html) {
    const clean = sanitizeHtml(html);
    const tmp = document.createElement('div');
    tmp.innerHTML = clean;
    if (!tmp.textContent.trim() && !tmp.querySelector('img, a')) return '';
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
    }
    if (nodeAuthorInput) nodeAuthorInput.value = authorName(); // 既定は記入者名。項目ごとに変更可
    if (nodeMetaEl) nodeMetaEl.textContent = metaStr;
    if (nodeErrorEl) nodeErrorEl.textContent = '';
    const canAttach = serverMode();
    [nodeImgBtn, nodeFileBtn].forEach((b) => {
      if (b) { b.disabled = false; b.title = canAttach ? '' : '添付はサーバー(DB)接続時のみ'; }
    });
    nodeDialog.showModal();
    nodeTitleInput.focus();
  }

  nodeForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const title = nodeTitleInput.value.trim();
    if (!title) return;
    const body = normalizeBody(nodeBodyEditor.innerHTML);
    const author = (nodeAuthorInput ? nodeAuthorInput.value : '').trim();
    // 入力した登録者名を既定値としても記憶
    localStorage.setItem(AUTHOR_KEY, author);
    if (typeof authorInput !== 'undefined' && authorInput) authorInput.value = author;
    const lockOpt = nodeLockChk
      ? { enabled: nodeLockChk.checked, pw: nodeLockPw ? nodeLockPw.value : '' }
      : null;
    const submitBtn = nodeForm.querySelector('button[type=submit]');
    submitBtn.disabled = true;
    nodeError('');
    try {
      if (dialogTarget.mode === 'edit') {
        await opUpdate(dialogTarget.id, title, body, author, lockOpt);
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
          appendEditorHtml(`<img src="${esc(data.url)}"><br>`);
          nodeError('画像を本文の下に追加しました');
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
      // 画像の貼り付けはアップロード
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
      // メモ等からの貼り付けは改行を確実に反映し、URLはリンク化する
      const text = cd.getData('text/plain');
      if (text == null || text === '') return; // ファイル等はブラウザ既定に任せる
      e.preventDefault();
      insertEditorHtml(plainToHtml(text));
    });
    // 編集画面で画像・添付ファイルをタップすると削除できる
    nodeBodyEditor.addEventListener('click', (e) => {
      const el = e.target.closest('img, a.tm-filechip');
      if (!el) return;
      e.preventDefault();
      const isImg = el.tagName === 'IMG';
      askConfirm(isImg ? 'この画像を削除しますか？' : 'この添付ファイルを削除しますか？', () => {
        const next = el.nextSibling;
        el.remove();
        // 直後の改行や空白（挿入時に付けたもの）も取り除く
        if (next && next.nodeType === 1 && next.tagName === 'BR') next.remove();
        else if (next && next.nodeType === 3 && !next.nodeValue.trim()) next.remove();
        nodeBodyEditor.focus();
      }, '削除');
    });
  }

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
  let dbError = null;

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
      chip.classList.remove('is-shared', 'is-nodb', 'is-local');
      if (serverAvailable && dbConnected) { chip.textContent = '● 共有中'; chip.classList.add('is-shared'); }
      else if (serverAvailable && !dbConnected) { chip.textContent = '● DB未接続'; chip.classList.add('is-nodb'); }
      else { chip.textContent = '● この端末のみ'; chip.classList.add('is-local'); }
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
    try {
      const cfg = await apiCall('config');
      serverAvailable = true;
      dbConnected = !!cfg.dbConnected;
      hasToken = !!cfg.hasToken;
      dbError = cfg.error || null;
    } catch (e) {
      serverAvailable = false; dbConnected = false; hasToken = false; dbError = null;
    }
    setServerStatus();
  }

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
  const navModeBtn = $('#navModeBtn');
  const editModeBtn = $('#editModeBtn');

  function setMode(mode) {
    const isNav = mode === 'nav';
    navReorder = false; // モード切替時は簡易並べ替えを解除
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
    setServerStatus();
    await detectServer();
    if (serverMode()) {
      try { await reloadFromServer(); } catch (e) { /* fallback: localStorage */ }
    }
    setMode('nav');
  })();
})();

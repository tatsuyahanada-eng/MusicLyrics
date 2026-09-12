/* ============================================================
   ライブラリポータル — library.js  v4
   本棚ビュー（背表紙を並べ、選ぶと見開きで開く）と
   一覧ビュー（1行 = 1アイテムのアコーディオン）の2つを持つ

   データ供給元：
     ・index.php（本番）… window.LP が定義され、api/items.php から取得
     ・preview.html（デザイン確認用）… window.LP_SAMPLE のサンプルを表示
   ============================================================ */
'use strict';

const LP_CFG = window.LP || null;              // 本番なら PHP から埋め込まれる
const CAN_EDIT = !!(LP_CFG && LP_CFG.canEdit); // 管理者のみ true
const API = LP_CFG ? LP_CFG.apiBase : null;

// sql/upgrade.sql をまだ実行していないサーバーでは、この項目は選んでも保存されない。
// フォーム側で選べないようにして、「保存したのに反映されない」を防ぐ
const DB_MISSING = (LP_CFG && LP_CFG.dbMissing) || [];
const DB_HAS_BUMP   = !DB_MISSING.includes('lp_updates.bump_type');
const DB_HAS_SERIES = !DB_MISSING.includes('lp_items.series');
const DB_HAS_FILES  = !DB_MISSING.includes('lp_updates.file_path');

/** バイト数を「1.2MB」のように短く表示する */
function fmtBytes(n) {
  n = Number(n) || 0;
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
  return `${(n / (1024 * 1024)).toFixed(1)}MB`;
}

const KIND_CLASS = {
  '機能追加': 'feature',
  '不具合修正': 'bugfix',
  '改善': 'improve',
  '資料改訂': 'doc',
  '初版公開': 'initial'
};
const CAT_CLASS = { 'アプリ': 'app', 'プログラム': 'prg', '資料': 'doc', 'マニュアル': 'man' };
/* 管理IDの接頭辞（種別を選ぶと、この接頭辞の続き番号を自動で入れる） */
const CAT_PREFIX = { 'アプリ': 'APP', 'プログラム': 'PRG', '資料': 'DOC', 'マニュアル': 'MAN' };

/** その種別で次に使う管理ID（接頭辞＋連番）を、登録済みのIDから組み立てる */
function nextItemId(category) {
  const prefix = CAT_PREFIX[category];
  if (!prefix) return '';
  let maxNum = 0;
  let width = 3;                          // 既存が APP-001 のように3桁なら、それに合わせる
  items.forEach((it) => {
    const m = /^([A-Za-z]+)-(\d+)$/.exec(it.id || '');
    if (m && m[1] === prefix) {
      maxNum = Math.max(maxNum, parseInt(m[2], 10));
      width = Math.max(width, m[2].length);
    }
  });
  return `${prefix}-${String(maxNum + 1).padStart(width, '0')}`;
}

/* 絵文字ではなく線画のアイコンを使用（サイズ・太さを他要素と揃えるため） */
const ICON_CHEVRON = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
  stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>`;
const ICON_EXTERNAL = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
  stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round">
  <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
  <path d="M15 3h6v6"/><path d="M10 14 21 3"/></svg>`;
/* 版数の遷移（v1.2.0 → v1.4.0）に使う矢印 */
const ICON_ARROW = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
  stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h13"/><path d="m12 5 7 7-7 7"/></svg>`;
/* 添付ファイル（クリップ）。年表の一覧で「この回にファイルが付いている」ことを示す */
const ICON_CLIP = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
  stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M21.44 11.05l-9.19 9.19a5.5 5.5 0 0 1-7.78-7.78l9.19-9.19a3.5 3.5 0 0 1 4.95 4.95l-9.2 9.19a1.5 1.5 0 0 1-2.12-2.12l8.49-8.48"/></svg>`;
/* 添付ファイルの種類を示すアイコン（画像はサムネイル自体が示すので使わない） */
const ICON_PDF = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>`;
const ICON_ZIP = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <rect x="3" y="7" width="18" height="14" rx="2"/><path d="M3 7l3-4h6l3 4"/><path d="M12 12v5"/></svg>`;

/** 添付ファイルが画像かどうか（画像はサムネイルで見せる） */
const isImageMime = (mime) => /^image\//.test(mime || '');

/** 画像以外の添付ファイルにつけるアイコン */
function attachmentTypeIcon(mime) {
  if (mime === 'application/pdf') return ICON_PDF;
  return ICON_ZIP;
}

/** 添付ファイルの表示（画像はサムネイル、それ以外はアイコン＋ファイル名）を組み立てる */
function attachmentBoxHtml(a, big) {
  if (!a) return '';
  if (isImageMime(a.mime)) {
    return `
      <p class="lp-attach-box lp-attach-box-img${big ? ' lp-attach-box-lg' : ''}">
        <a class="lp-attach-thumb-link" href="${esc(a.url)}" target="_blank" rel="noopener" title="画像を大きく見る">
          <img class="lp-attach-thumb" src="${esc(a.url)}" alt="${esc(a.name)}" loading="lazy">
        </a>
        <span class="lp-attach-current-info">
          <a class="lp-attach-link" href="${esc(a.url)}">${esc(a.name)}</a>
          <span class="lp-attach-size">${fmtBytes(a.size)}</span>
        </span>
      </p>`;
  }
  return `
    <p class="lp-attach-box">
      <span class="lp-attach-type-icon" aria-hidden="true">${attachmentTypeIcon(a.mime)}</span>
      <a class="lp-attach-link" href="${esc(a.url)}">${esc(a.name)}</a>
      <span class="lp-attach-size">${fmtBytes(a.size)}</span>
    </p>`;
}

/**
 * 登録・修正フォームの「現在のファイル」欄を埋める（画像ならサムネイル、それ以外はアイコン）。
 * prefix は 'f'（更新フォーム）または 'i'（アイテムフォーム）。
 */
function fillAttachmentCurrent(prefix, attachment) {
  const box = $(`${prefix}AttachmentCurrent`);
  if (!box) return;
  const thumb = $(`${prefix}AttachmentThumb`);
  const icon = $(`${prefix}AttachmentCurrentIcon`);
  const link = $(`${prefix}AttachmentCurrentLink`);
  const removeChk = $(`${prefix}RemoveAttachment`);
  if (removeChk) removeChk.checked = false;

  box.hidden = !attachment;
  if (!attachment) return;

  if (isImageMime(attachment.mime)) {
    thumb.src = attachment.url;
    thumb.hidden = false;
    icon.innerHTML = '';
  } else {
    thumb.hidden = true;
    thumb.removeAttribute('src');
    icon.innerHTML = attachmentTypeIcon(attachment.mime);
  }
  link.href = attachment.url;
  link.textContent = `${attachment.name}（${fmtBytes(attachment.size)}）`;
}

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
const state = { q: '', category: '', series: '', sort: 'updated_desc', view: 'shelf' };
let readingId = null;                          // いま開いている本（本棚ビュー）
let chronicleOrder = 'desc';                   // 更新の年表の並び順（desc=新しい順 / asc=古い順）

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

/** library.css が library.js と揃っているかを確かめる
    （片方だけ古いファイルをアップロードすると、画面が無地のまま崩れて原因が分かりにくいため） */
function checkStylesLoaded() {
  const probe = document.createElement('div');
  probe.className = 'lp-stack';
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  document.body.appendChild(probe);
  const ok = getComputedStyle(probe).display === 'flex';
  probe.remove();
  if (!ok) {
    toast('表示用のCSSが古いようです。assets/library.css を差し替えて再読み込みしてください');
    console.warn('library.css が library.js と一致していません（.lp-stack の定義が見つかりません）');
  }
  return ok;
}

/** URL は新しいタブではなく、独立したウィンドウで開く
    （大きさを指定すると、ブラウザはタブではなくウィンドウとして開く） */
function openInWindow(url) {
  const sw = window.screen && screen.availWidth ? screen.availWidth : 1440;
  const sh = window.screen && screen.availHeight ? screen.availHeight : 900;
  const w = Math.max(640, Math.min(1280, Math.round(sw * 0.8)));
  const h = Math.max(480, Math.min(900, Math.round(sh * 0.85)));
  const left = Math.max(0, Math.round((sw - w) / 2));
  const top = Math.max(0, Math.round((sh - h) / 2));
  return window.open(url, '_blank',
    `popup=yes,noopener,noreferrer,resizable=yes,scrollbars=yes,` +
    `width=${w},height=${h},left=${left},top=${top}`);
}

/* ---------- API ---------- */
async function apiGet(path) {
  const res = await fetch(`${API}/${path}`, {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin'
  });
  if (res.status === 401) { location.href = 'login.php'; throw new Error('unauthorized'); }
  if (!res.ok) {
    // サーバーが理由を返していればそれを見せる（原因が分からないままにしない）
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || `データを取得できませんでした（エラー ${res.status}）。`);
  }
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

/**
 * 添付ファイルを含む送信専用。PHP は PUT の multipart/form-data を $_FILES に
 * 展開してくれないため、常に POST で送り、修正のときだけ _method=PUT を同封する。
 */
async function apiSendMultipart(path, formData) {
  const res = await fetch(`${API}/${path}`, {
    method: 'POST',
    headers: { 'X-CSRF-Token': LP_CFG.csrf, Accept: 'application/json' },
    credentials: 'same-origin',
    body: formData
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { location.href = 'login.php'; throw new Error('unauthorized'); }
  if (!res.ok) throw new Error(data.error || `POST ${path} ${res.status}`);
  return data;
}

async function loadItems() {
  if (!LP_CFG) {                       // プレビュー（静的）
    items = JSON.parse(JSON.stringify(window.LP_SAMPLE || []));
  } else {
    items = await apiGet('items.php');
  }
  items.forEach(sortHistory);
  // 本番は API が版数を付けて返す。プレビューは付いてこないのでここで数える
  items.forEach((it) => { if (!it.version) numberVersions(it); });
}

/* 版数の決まり：最初の登録が 1.00、通常の更新で 1.1・1.2…、微修正で 1.11・1.12…。
   桁があふれたら繰り上げる（1.9 の次は 2.00）ので、同じ表記は二度出ない。
   本番では api/items.php が同じ規則で数えている（includes/helpers.php）。 */
function numberVersions(it) {
  let major = 1, minor = 0, rev = 0;
  const label = () => (minor === 0 && rev === 0)
    ? `${major}.00`
    : (rev === 0 ? `${major}.${minor}` : `${major}.${minor}${rev}`);

  [...it.history].reverse().forEach((e, i) => {         // 古い順に数える
    if (i === 0) {
      // 最初の登録は 1.00
    } else if (e.bump === 'revision') {
      rev++;
      if (rev > 9) { rev = 0; minor++; }
      if (minor > 9) { minor = 0; major++; }
    } else {
      minor++; rev = 0;
      if (minor > 9) { minor = 0; major++; }
    }
    e.version = label();
  });
  it.version = it.history.length ? it.history[0].version : '1.00';
}

/* ---------- 絞り込み ---------- */
function historyMatches(h, q) {
  const files = normFiles(h).map((f) => `${f.path} ${f.note}`).join(' ');
  return [h.summary, h.target, h.author, h.kind, h.version, h.ticket, files]
    .join(' ').toLowerCase().includes(q);
}

/* 並び替えできる項目。key は比較に使う値、type は比較のしかた */
const SORTS = {
  updated:  { label: '最終更新',  type: 'text', key: (it) => { const h = latest(it); return h ? `${h.date} ${h.time}` : ''; } },
  name:     { label: '名称',      type: 'ja',   key: (it) => it.name },
  category: { label: '種別',      type: 'ja',   key: (it) => it.category },
  series:   { label: 'シリーズ',  type: 'ja',   key: (it) => it.series || '' },
  creator:  { label: '作成者',    type: 'ja',   key: (it) => it.creator },
  count:    { label: '更新回数',  type: 'num',  key: (it) => it.history.length },
  version:  { label: '最新Ver',   type: 'ja',   key: (it) => it.version || '1.00' },
  created:  { label: '作成日',    type: 'text', key: (it) => String(it.createdAt) }
};

/** 'updated_desc' のような値を { field, dir } に分ける */
function parseSort(value) {
  const m = String(value).match(/^(\w+)_(asc|desc)$/);
  if (!m || !SORTS[m[1]]) return { field: 'updated', dir: 'desc' };
  return { field: m[1], dir: m[2] };
}

function visibleItems() {
  const q = state.q.trim().toLowerCase();
  const list = items.filter((it) => {
    if (state.category && it.category !== state.category) return false;
    if (state.series && (it.series || '') !== state.series) return false;
    if (!q) return true;
    const base = [it.id, it.name, it.category, it.series, it.creator, it.description]
      .join(' ').toLowerCase();
    return base.includes(q) || it.history.some((h) => historyMatches(h, q));
  });

  const { field, dir } = parseSort(state.sort);
  const spec = SORTS[field];
  const sign = dir === 'asc' ? 1 : -1;
  list.sort((a, b) => {
    const x = spec.key(a);
    const y = spec.key(b);
    let d;
    if (spec.type === 'num') d = x - y;
    else if (spec.type === 'ja') d = String(x).localeCompare(String(y), 'ja');
    else d = String(x).localeCompare(String(y));
    // 同じ値のときは名称順にして、並びがぶれないようにする
    return d !== 0 ? d * sign : a.name.localeCompare(b.name, 'ja');
  });
  return list;
}

/* ---------- 更新履歴 ----------
   「何を、どのファイルで直して、最新Verに至ったのか」を軸に組み立てる。
   本棚・一覧のどちらから開いても、同じ見開き（spreadHtml）を使う   */

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
        <span class="lp-road-kind">${isNow ? '最新' : esc(h.kind)}</span>
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
/** 一覧を開かなくても更新の推移が一目で分かる、区分色の小さな点の並び（古い→新しい） */
function historyGlance(it) {
  const n = it.history.length;
  if (!n) return '<span class="lp-row-glance lp-row-glance-empty">まだ更新なし</span>';

  const chrono = [...it.history].reverse();      // 古い順に並べ替え
  const MAX = 10;
  const shown = chrono.slice(-MAX);
  const hidden = chrono.length - shown.length;

  const dots = shown.map((e) =>
    `<i class="lp-hdot lp-hdot-${KIND_CLASS[e.kind] || 'improve'}"></i>`
  ).join('');
  const tip = `更新の推移（${n} 回）：${chrono.map((e) => e.kind).join(' → ')}`;

  return `
    <span class="lp-row-glance" title="${esc(tip)}">
      ${hidden > 0 ? `<span class="lp-hdot-more">+${hidden}</span>` : ''}${dots}
      <span class="lp-row-glance-count">${n} 回</span>
    </span>`;
}

/* ============================================================
   横積みの棚（CDラック／レコード棚のイメージ）
   ・1枚 = 1資料。横に寝かせた背表紙なので、タイトルは横書きで読める
   ・厚み  = 更新回数（よく手が入っている資料ほど厚い）
   ・目盛り = 更新1件ぶん。区分の色で塗るので、棚を眺めるだけで
              「機能追加が多い」「不具合修正続き」といった性格が分かる
   ============================================================ */

function slabHtml(it) {
  const n = it.history.length;
  const h = latest(it);
  const reading = readingId === it.id;

  const thick = Math.min(44 + n * 6, 84);          // 厚み（更新回数ぶん）

  const MAX_TICKS = 16;
  const chrono = [...it.history].reverse();
  const shown = chrono.slice(-MAX_TICKS);
  const hidden = chrono.length - shown.length;
  const ticks = shown.map((e) =>
    `<i class="lp-tick lp-tick-${KIND_CLASS[e.kind] || 'improve'}"></i>`).join('');

  const tip = n
    ? `${it.name}／更新 ${n} 回：${chrono.map((e) => e.kind).join(' → ')}`
    : `${it.name}／更新はまだありません`;

  return `
    <button class="lp-slab lp-slab-${CAT_CLASS[it.category] || 'prg'}${reading ? ' is-reading' : ''}"
            type="button" data-book="${esc(it.id)}" title="${esc(tip)}"
            aria-expanded="${reading}" style="--thick:${thick}px">
      <span class="lp-slab-edge" aria-hidden="true"></span>
      <span class="lp-slab-face">
        <span class="lp-slab-cat">${CAT_ICON[it.category] || ''}${esc(it.category)}</span>
        <span class="lp-slab-main">
          <span class="lp-slab-name">${esc(it.name)}</span>
          <span class="lp-slab-by">${esc(it.id)} ／ ${esc(it.creator)}</span>
        </span>
        <span class="lp-slab-series">${it.series
          ? `<span class="lp-seriestag">${esc(it.series)}</span>` : ''}</span>
        <span class="lp-slab-hist">
          ${hidden > 0 ? `<span class="lp-tick-more">+${hidden}</span>` : ''}
          <span class="lp-slab-ticks" aria-hidden="true">${ticks}</span>
          <span class="lp-slab-count">${n ? `更新 ${n} 回` : '更新なし'}</span>
        </span>
        <span class="lp-slab-ver">${esc(it.version || (h && h.version) || '1.00')}</span>
        <span class="lp-slab-date">${h ? fmtDate(h.date) : '—'}</span>
        <span class="lp-slab-chev" aria-hidden="true">${ICON_CHEVRON}</span>
      </span>
    </button>`;
}

function shelfHtml(list) {
  if (!list.length) return '';
  return `<div class="lp-stack">${list.map(slabHtml).join('')}</div>`;
}

/* ============================================================
   見開き（開いた本の中身）
   左ページ＝最新Verの姿、右ページ＝更新の年表
   ============================================================ */

/** 右ページ：年ごとにまとめた更新の年表 */
function chronicle(it) {
  if (!it.history.length) {
    return '<p class="lp-chr-empty">この資料にはまだ更新が登録されていません。</p>';
  }

  const { rounds, total } = fileRounds(it);
  const n = it.history.length;

  // history は常に新しい順で持っているので、版数の遷移などはこの並びを基準に計算する。
  // 表示だけ、選ばれた並び順（新しい順・古い順）に沿って年ごとにまとめる
  const seq = it.history.map((e, i) => ({ e, i }));
  if (chronicleOrder === 'asc') seq.reverse();

  const years = [];
  seq.forEach(({ e, i }) => {
    const y = String(e.date).slice(0, 4);
    if (!years.length || years[years.length - 1].year !== y) years.push({ year: y, list: [] });
    years[years.length - 1].list.push({ e, i });
  });

  let d = 0;
  const blocks = years.map((grp) => {
    const entries = grp.list.map(({ e, i }) => {
      const oldIdx = n - 1 - i;                     // 古い順に数えた位置
      const prev = it.history[i + 1] || null;
      const files = normFiles(e);
      const step = Math.min(d++, 14);

      const from = prev && prev.version !== e.version ? prev.version : '';
      const jump = from
        ? `<span class="lp-jump">
             <span class="lp-jump-from">${esc(from)}</span>
             <span class="lp-jump-arrow" aria-hidden="true">${ICON_ARROW}</span>
             <span class="lp-jump-to">${esc(e.version)}</span>
           </span>`
        : '';

      // 一覧は「日付・版数・項目名」だけ。詳細は押したときに開く
      return `
        <li class="lp-chr-item${i === 0 ? ' is-latest' : ''}${prev ? '' : ' is-first'}" style="--d:${step}">
          <button class="lp-chr-row" type="button" data-open-entry="${esc(e.uid || oldIdx)}"
                  aria-expanded="false">
            <span class="lp-chr-date">${fmtDate(e.date)}</span>
            <span class="lp-chr-ver">${esc(e.version)}</span>
            <span class="lp-chr-title">${esc(e.summary)}</span>
            ${e.attachment ? `<span class="lp-chr-clip" title="添付ファイルあり：${esc(e.attachment.name)}">${ICON_CLIP}</span>` : ''}
            ${i === 0 ? '<span class="lp-chr-tag">最新</span>' : ''}
            ${prev ? '' : '<span class="lp-chr-tag lp-chr-tag-start">出発点</span>'}
            <span class="lp-chr-mark" aria-hidden="true">${ICON_CHEVRON}</span>
          </button>

          <div class="lp-chr-detail" hidden>
            <div class="lp-chr-head">
              <span class="lp-chr-time">${esc(e.time)} 登録</span>
              ${kindBadge(e.kind)}
              ${jump}
              ${CAN_EDIT && e.uid ? `<button class="lp-chr-edit" type="button"
                  data-edit-update="${esc(e.uid)}" data-item="${esc(it.id)}"
                  title="この更新内容を修正">✎ 修正</button>` : ''}
              ${CAN_EDIT && e.uid ? `<button class="lp-chr-delete" type="button"
                  data-delete-update="${esc(e.uid)}" data-item="${esc(it.id)}"
                  data-summary="${esc(e.summary)}"
                  title="この更新履歴を削除">🗑 削除</button>` : ''}
            </div>
            <div class="lp-chr-meta">
              <span><b>対象機能</b>${esc(e.target)}</span>
              <span><b>対応者</b>${esc(e.author)}</span>
              ${e.ticket ? `<span><b>管理番号</b>${esc(e.ticket)}</span>` : ''}
            </div>
            ${attachmentBoxHtml(e.attachment, true)}
            <div class="lp-chr-files">
              <span class="lp-hist-files-cap">実際に直したプログラム・ファイル（${files.length} 件）</span>
              ${fileTable(files, oldIdx, rounds, total)}
            </div>
          </div>
        </li>`;
    }).join('');

    return `
      <section class="lp-chr-year" style="--d:${Math.min(d, 14)}">
        <h4 class="lp-chr-yearhead"><span>${esc(grp.year)}</span><em>${grp.list.length} 回</em></h4>
        <ol class="lp-chr-list">${entries}</ol>
      </section>`;
  }).join('');

  return `<div class="lp-chr">${blocks}</div>`;
}

/** 更新1件の詳細を開閉する */
function toggleEntry(btn) {
  const detail = btn.parentElement.querySelector('.lp-chr-detail');
  if (!detail) return;
  const open = detail.hidden;
  detail.hidden = !open;
  btn.setAttribute('aria-expanded', String(open));
  btn.closest('.lp-chr-item').classList.toggle('is-open', open);
}

/** 「全ての更新履歴」…すべての詳細をまとめて開く／閉じる */
function toggleAllEntries(btn) {
  const spread = $('spread');
  if (!spread) return;
  const rows = [...spread.querySelectorAll('.lp-chr-row')];
  if (!rows.length) return;
  // 1件でも閉じていれば「全部開く」、すべて開いていれば「全部閉じる」
  const open = rows.some((r) => r.getAttribute('aria-expanded') !== 'true');
  rows.forEach((r) => {
    const detail = r.parentElement.querySelector('.lp-chr-detail');
    if (detail) detail.hidden = !open;
    r.setAttribute('aria-expanded', String(open));
    r.closest('.lp-chr-item').classList.toggle('is-open', open);
  });
  btn.textContent = open ? '詳細を閉じる' : '全ての更新履歴';
  btn.classList.toggle('is-on', open);
}

/** 更新の年表の並び順を切り替える（新しい順・古い順） */
function setChronicleOrder(order) {
  if (order !== 'asc' && order !== 'desc') return;
  if (order === chronicleOrder || !readingId) return;
  chronicleOrder = order;
  const it = items.find((x) => x.id === readingId);
  if (!it) return;
  const spread = $('spread');
  // 中身を作り直すと「開いたときに浮かび上がる」演出が新しい要素にもう一度掛かってしまう
  // （一瞬すべて透明になって見える）ため、並び替えのときは演出を止めておく。
  // 次に本を開き直したとき（openBook）に外れ、そのときは通常どおり演出が入る
  spread.classList.add('lp-reorder');
  spread.innerHTML = spreadHtml(it);
}

/** 同じシリーズの資料（アプリとそのマニュアル・資料など）を並べる */
function siblingsBlock(it) {
  if (!it.series) return '';
  const mates = items.filter((x) => x.series === it.series && x.id !== it.id);
  if (!mates.length) return '';

  const rows = mates
    .sort((a, b) => a.category.localeCompare(b.category, 'ja') || a.name.localeCompare(b.name, 'ja'))
    .map((x) => {
      const h = latest(x);
      return `
        <li>
          <button class="lp-mate" type="button" data-book="${esc(x.id)}">
            <span class="lp-cat lp-cat-${CAT_CLASS[x.category] || 'prg'}">${CAT_ICON[x.category] || ''}${esc(x.category)}</span>
            <span class="lp-mate-name">${esc(x.name)}</span>
            <span class="lp-mate-ver">${esc(x.version || (h && h.version) || '1.00')}</span>
          </button>
        </li>`;
    }).join('');

  return `
    <div class="lp-mates">
      <span class="lp-mates-label">同じシリーズ（${esc(it.series)}）</span>
      <ul class="lp-mates-list">${rows}</ul>
    </div>`;
}

/** 左ページ：この資料の最新Verがどうなっているか */
function spreadLeft(it) {
  const h = latest(it);
  const url = safeUrl(it.downloadUrl);
  // URL が未設定でも、最新の更新にファイルが添付されていればそれを「開く」先にする
  const attach = (!url && h && h.attachment) ? h.attachment : null;
  const fileCount = it.history.reduce((sum, e) => sum + normFiles(e).length, 0);
  const touched = new Set();
  it.history.forEach((e) => normFiles(e).forEach((f) => touched.add(f.path)));

  return `
    <section class="lp-page lp-page-l">
      <div class="lp-page-inner">
        <p class="lp-page-eyebrow">
          <span class="lp-cat lp-cat-${CAT_CLASS[it.category] || 'prg'}">${CAT_ICON[it.category] || ''}${esc(it.category)}</span>
          <span class="lp-page-id">${esc(it.id)}</span>
          ${it.series ? `<button class="lp-seriestag lp-seriestag-btn" type="button"
              data-series="${esc(it.series)}" title="このシリーズだけを表示">${esc(it.series)}</button>` : ''}
        </p>

        <h2 class="lp-page-title">${esc(it.name)}</h2>
        <p class="lp-page-by">${esc(it.creator)}　著</p>

        <div class="lp-nowbox">
          <span class="lp-nowbox-label">最新Ver</span>
          <span class="lp-nowbox-ver">${esc(it.version || (h && h.version) || '1.00')}</span>
          <span class="lp-nowbox-when">${h
            ? `${fmtDate(h.date)} ${esc(h.time)} の更新まで反映`
            : 'まだ更新は登録されていません'}</span>
        </div>

        ${it.description ? `<p class="lp-page-desc">${esc(it.description)}</p>` : ''}

        ${url ? `<p class="lp-page-open">
          <a class="lp-dl" href="${esc(url)}" target="_blank" rel="noopener">${ICON_EXTERNAL}<span>この資料を開く</span></a>
          <span class="lp-dl-url">${esc(url)}</span>
        </p>` : attach ? `<p class="lp-page-open">
          <a class="lp-attach-link lp-attach-link-lg" href="${esc(attach.url)}">${ICON_CLIP}<span>${esc(attach.name)}</span></a>
          <span class="lp-dl-url">${fmtBytes(attach.size)}</span>
        </p>` : '<p class="lp-page-open"><span class="lp-muted">URL 未設定</span></p>'}

        <dl class="lp-okuzuke">
          <div><dt>公開開始</dt><dd>${fmtDate(it.createdAt)}</dd></div>
          <div><dt>これまでの更新</dt><dd>${it.history.length} 回</dd></div>
          <div><dt>直したファイル</dt><dd>延べ ${fileCount} 件 ／ ${touched.size} 種類</dd></div>
          <div><dt>最終対応者</dt><dd>${h ? esc(h.author) : '—'}</dd></div>
        </dl>

        ${versionRoad(it)}
        ${siblingsBlock(it)}

        ${CAN_EDIT ? `<p class="lp-page-actions">
          <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-add="${esc(it.id)}">＋ この資料の更新を登録</button>
          <button class="lp-btn lp-btn-ghost lp-btn-sm" type="button" data-edit-item="${esc(it.id)}">✎ この資料を修正</button>
        </p>` : ''}
      </div>
      <span class="lp-folio">${esc(it.id)}</span>
    </section>`;
}

function spreadHtml(it) {
  return `
    <div class="lp-spread-paper">
      <button class="lp-spread-close" type="button" data-close-spread aria-label="本を閉じる">✕ 閉じる</button>
      ${spreadLeft(it)}
      <section class="lp-page lp-page-r">
        <div class="lp-page-inner">
          <h3 class="lp-page-h">更新の年表
            <span>${it.history.length} 件</span>
            ${it.history.length > 1 ? `
              <div class="lp-viewswitch lp-chr-orderswitch" role="group" aria-label="更新の年表の並び順">
                <button class="lp-viewbtn${chronicleOrder === 'desc' ? ' is-on' : ''}" type="button" data-chr-order="desc">新しい順</button>
                <button class="lp-viewbtn${chronicleOrder === 'asc' ? ' is-on' : ''}" type="button" data-chr-order="asc">古い順</button>
              </div>` : ''}
            ${it.history.length ? '<button class="lp-allbtn" type="button" data-all-entries>全ての更新履歴</button>' : ''}
          </h3>
          ${chronicle(it)}
        </div>
        <span class="lp-folio">${it.history.length} 回の更新</span>
      </section>
      <span class="lp-gutter" aria-hidden="true"></span>
    </div>`;
}

/** 一覧・棚に並んでいる項目だけを返す（見開きの中のボタンは除く） */
function listBooks() {
  const el = $('list');
  if (!el) return [];
  return [...el.querySelectorAll('[data-book]')].filter((b) => !b.closest('#spread'));
}

/** 本を開く・閉じる */
function openBook(id) {
  const spread = $('spread');
  if (!spread) return;

  if (readingId === id) { closeBook(); return; }

  const it = items.find((x) => x.id === id);
  if (!it) return;

  readingId = id;
  let target = null;
  // 見開きは一覧の中に差し込まれ、その中にも data-book のボタン（同じシリーズの資料）が
  // あるため、差し込み先は見開きの外に並んでいるものだけから探す
  listBooks().forEach((b) => {
    const on = b.dataset.book === id;
    if (on) target = b;
    b.classList.toggle('is-reading', on);
    b.setAttribute('aria-expanded', String(on));
    // 一覧では行そのものに開いている印を付ける
    const row = b.closest('.lp-row');
    if (row) row.classList.toggle('is-open', on);
  });

  // 選んだものの直下に開く（どれを開いているかが分かるように）
  if (target) (target.closest('.lp-row') || target).insertAdjacentElement('afterend', spread);

  // いったん閉じてから開き直すと、ページがめくれる動きが必ず再生される
  spread.classList.remove('is-open', 'lp-reorder');
  spread.hidden = false;
  spread.innerHTML = spreadHtml(it);
  void spread.offsetWidth;                       // ここで一度レイアウトを確定させる
  spread.classList.add('is-open');
  showLatestOnRoad(spread);

  // 画面の上に貼り付いているもの（ヘッダー・一覧の見出し行）の下に隠れないように送る
  const stuck = (el) => (el && el.offsetParent !== null ? el.getBoundingClientRect().height : 0);
  const offset = stuck(document.querySelector('.lp-header')) + stuck($('listHead')) + 14;
  const top = spread.getBoundingClientRect().top + window.scrollY - offset;
  window.scrollTo({ top, behavior: 'smooth' });
}

/** 「版数の道のり」は横に長いので、いちばん見たい最新版が見えるところまで送っておく */
function showLatestOnRoad(spread) {
  const track = spread.querySelector('.lp-road-track');
  if (!track) return;
  const go = () => { track.scrollLeft = track.scrollWidth; };
  go();
  window.requestAnimationFrame(go);              // 開くアニメーションで幅が変わるため念のためもう一度
}

function closeBook() {
  const spread = $('spread');
  readingId = null;
  listBooks().forEach((b) => {
    b.classList.remove('is-reading');
    b.setAttribute('aria-expanded', 'false');
    const row = b.closest('.lp-row');
    if (row) row.classList.remove('is-open');
  });
  if (!spread) return;
  spread.classList.remove('is-open');
  window.setTimeout(() => { if (!readingId) { spread.hidden = true; spread.innerHTML = ''; } }, 260);
}

/* ---------- 描画 ---------- */
function rowHtml(it) {
  const h = latest(it);
  const open = readingId === it.id;
  const url = safeUrl(it.downloadUrl);
  const attach = (!url && h && h.attachment) ? h.attachment : null;

  return `
  <article class="lp-row${open ? ' is-open' : ''}" data-id="${esc(it.id)}">
    <div class="lp-row-head" role="button" tabindex="0" data-book="${esc(it.id)}"
         aria-expanded="${open}" aria-controls="spread">
      <span><span class="lp-cat lp-cat-${CAT_CLASS[it.category] || 'prg'}">${CAT_ICON[it.category] || ''}${esc(it.category)}</span></span>
      <span>
        <span class="lp-row-name">${esc(it.name)}</span>
        <span class="lp-row-id">${esc(it.id)} ／ ${esc(it.creator)}</span>
        ${it.series ? `<span class="lp-seriestag">${esc(it.series)}</span>` : ''}
        ${historyGlance(it)}
      </span>
      <span class="lp-row-date">${h ? fmtDate(h.date) : '—'}<span class="lp-row-time">${h ? esc(h.time) : ''}</span>
        <span class="lp-row-ver">${esc(it.version || (h && h.version) || '1.00')}</span></span>
      <span>
        <span class="lp-row-summary">${h ? esc(h.summary) : '更新履歴なし'}</span>
        ${h ? `<span class="lp-row-target">対象機能：${esc(h.target)}</span>` : ''}
      </span>
      <span class="lp-row-author">${esc(it.creator)}</span>
      <span class="lp-row-url">
        ${url ? `<a class="lp-url-link" href="${esc(url)}" target="_blank" rel="noopener"
                    aria-label="${esc(it.name)} を開く">${ICON_EXTERNAL}<span>開く</span></a>`
              : attach ? `<a class="lp-attach-link" href="${esc(attach.url)}"
                    aria-label="${esc(it.name)} をダウンロード">${ICON_CLIP}<span>DL</span></a>`
              : '<span class="lp-muted">—</span>'}
      </span>
      <span class="lp-chev" aria-hidden="true">${ICON_CHEVRON}</span>
    </div>

  </article>`;
}

function render() {
  const list = visibleItems();
  const shelf = state.view === 'shelf';
  const listEl = $('list');

  // 見開きは一覧・棚の中に差し込まれているため、描き直す前にいったん外へ出す
  const spread = $('spread');
  if (spread && listEl.contains(spread)) listEl.insertAdjacentElement('afterend', spread);

  listEl.className = shelf ? 'lp-shelf' : 'lp-list';
  listEl.innerHTML = shelf ? shelfHtml(list) : list.map(rowHtml).join('');

  const head = $('listHead');
  if (head) {
    head.hidden = shelf;
    const { field, dir } = parseSort(state.sort);
    head.querySelectorAll('[data-sort]').forEach((b) => {
      const on = b.dataset.sort === field;
      b.classList.toggle('is-sorted', on);
      b.dataset.dir = on ? dir : '';
      b.setAttribute('aria-sort', on ? (dir === 'asc' ? 'ascending' : 'descending') : 'none');
    });
  }

  // 絞り込みで消えたものが開いたままにならないようにする。
  // 残っていれば、描き直した要素の下に見開きを戻す
  if (readingId) {
    if (!list.some((it) => it.id === readingId)) {
      closeBook();
    } else if (spread && !spread.hidden) {
      const target = listEl.querySelector(`[data-book="${CSS.escape(readingId)}"]`);
      if (target) {
        (target.closest('.lp-row') || target).insertAdjacentElement('afterend', spread);
        target.classList.add('is-reading');
        target.setAttribute('aria-expanded', 'true');
        const row = target.closest('.lp-row');
        if (row) row.classList.add('is-open');
      }
    }
  }

  $('listEmpty').hidden = list.length > 0;
  $('statItems').textContent = items.length;
  $('statHistory').textContent = items.reduce((n, it) => n + it.history.length, 0);
}

/** 本棚 ⇄ 一覧 の切り替え */
function setView(view) {
  if (state.view === view) return;
  state.view = view;
  closeBook();
  document.querySelectorAll('[data-view]').forEach((b) =>
    b.classList.toggle('is-on', b.dataset.view === view));
  try { localStorage.setItem('lp-view', view); } catch (e) { /* 保存できなくても動作に影響はない */ }
  render();
}

/** シリーズの絞り込み欄（登録されているシリーズだけを並べる） */
function renderSeries() {
  const sel = $('seriesSelect');
  if (!sel) return;
  const names = [...new Set(items.map((i) => i.series).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'ja'));
  if (!names.includes(state.series)) state.series = '';
  sel.hidden = names.length === 0;
  sel.innerHTML = ['<option value="">すべてのシリーズ</option>']
    .concat(names.map((n) =>
      `<option value="${esc(n)}"${state.series === n ? ' selected' : ''}>${esc(n)}</option>`))
    .join('');
}

/** 並び替えの選択肢（項目 × 昇順・降順） */
function renderSortOptions() {
  const sel = $('sortSelect');
  if (!sel) return;
  const dirLabel = { updated: ['古い順', '新しい順'], created: ['古い順', '新しい順'], count: ['少ない順', '多い順'] };
  const opts = [];
  Object.entries(SORTS).forEach(([field, spec]) => {
    const [asc, desc] = dirLabel[field] || ['昇順', '降順'];
    opts.push(`<option value="${field}_desc">${spec.label}：${desc}</option>`);
    opts.push(`<option value="${field}_asc">${spec.label}：${asc}</option>`);
  });
  sel.innerHTML = opts.join('');
  sel.value = state.sort;
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
/** 更新履歴の登録・修正フォームを開く（uid を渡すとその1件の修正になる） */
function openUpdateModal(itemId, uid) {
  if (!CAN_EDIT) return;
  renderItemOptions();
  const form = $('updateForm');
  const it = itemId ? items.find((x) => x.id === itemId) : null;
  const entry = (it && uid) ? it.history.find((h) => h.uid === Number(uid)) : null;

  form.dataset.mode = entry ? 'edit' : 'create';
  form.dataset.uid = entry ? String(entry.uid) : '';
  $('modalTitle').textContent = entry ? '更新内容の修正' : '更新内容の登録';
  $('btnUpdateSubmit').textContent = entry ? '修正を保存' : '登録する';

  if (entry) {
    $('fItem').value = itemId;
    $('fDate').value = entry.date;
    $('fTime').value = entry.time;
    $('fKind').value = entry.kind;
    $('fAuthor').value = entry.author;
    $('fBump').value = entry.bump === 'revision' ? 'revision' : 'minor';
    $('fTicket').value = entry.ticket || '';
    $('fSummary').value = entry.summary;
    $('fTarget').value = entry.target;
    $('fFiles').value = normFiles(entry)
      .map((f) => (f.note ? `${f.path} : ${f.note}` : f.path)).join('\n');
    $('fUrl').value = '';
  } else {
    form.reset();
    if (itemId) $('fItem').value = itemId;
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    $('fDate').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    $('fTime').value = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  }

  // 列がまだ無いサーバーでは、選んでも保存されない項目を触らせない
  $('fBump').disabled = !DB_HAS_BUMP;
  if (!DB_HAS_BUMP) { $('fBump').value = 'minor'; }
  $('fBumpNote').hidden = DB_HAS_BUMP;

  // 添付ファイル：一覧から呼ばれた時点でどれもクリアしておく（file input は値を再設定できないため）
  $('fAttachment').value = '';
  $('fAttachmentLimit').textContent = fmtBytes((LP_CFG && LP_CFG.uploadMaxBytes) || 20 * 1024 * 1024);
  fillAttachmentCurrent('f', entry && entry.attachment);
  $('fAttachment').disabled = !DB_HAS_FILES;
  $('fAttachmentNote').hidden = DB_HAS_FILES;

  showModal($('updateModal'));
  $('fSummary').focus();
}

const UPLOAD_ALLOWED_EXT = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'pdf', 'zip'];

async function submitUpdate(ev) {
  ev.preventDefault();
  const form = $('updateForm');
  const edit = form.dataset.mode === 'edit';
  const itemId = $('fItem').value;
  const file = $('fAttachment').files[0] || null;

  // ネットワークに投げる前に分かる範囲だけ、その場で確認しておく
  if (file) {
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    if (!UPLOAD_ALLOWED_EXT.includes(ext)) {
      formError('updateError', '画像（jpg / png / gif / webp）・PDF・ZIP のみアップロードできます。');
      return;
    }
    const max = (LP_CFG && LP_CFG.uploadMaxBytes) || 20 * 1024 * 1024;
    if (file.size > max) {
      formError('updateError', `ファイルサイズが大きすぎます（上限 ${fmtBytes(max)}）。`);
      return;
    }
  }

  const fd = new FormData();
  fd.append('itemId', itemId);
  fd.append('date', $('fDate').value);
  fd.append('time', $('fTime').value);
  fd.append('author', $('fAuthor').value.trim());
  fd.append('kind', $('fKind').value);
  fd.append('bump', $('fBump').value);
  fd.append('summary', $('fSummary').value.trim());
  fd.append('target', $('fTarget').value.trim());
  fd.append('ticket', $('fTicket').value.trim());
  fd.append('downloadUrl', $('fUrl').value.trim());
  fd.append('filesJson', JSON.stringify(
    $('fFiles').value.split('\n').map((s) => s.trim()).filter(Boolean)
  ));
  if (file) {
    fd.append('file', file);
  } else if (edit && $('fRemoveAttachment').checked) {
    fd.append('removeFile', '1');
  }
  if (edit) {
    fd.append('_method', 'PUT');       // PHP は PUT の multipart を解釈しないため POST に載せる
    fd.append('uid', form.dataset.uid);
  }

  try {
    await apiSendMultipart('updates.php', fd);
    await loadItems();
    renderSeries();
    renderChips();
    render();
    // 開いていたものは、保存した内容を反映して開き直す
    readingId = null;
    openBook(itemId);
    hideModals();
    form.reset();
    toast(edit ? '更新履歴を修正しました' : '更新履歴を登録しました');
  } catch (e) {
    formError('updateError', e.message || (edit ? '修正' : '登録') + 'に失敗しました。');
  }
}

/** 間違って登録した更新履歴を1件削除する（管理者のみ・元に戻せないので確認してから） */
async function deleteUpdate(itemId, uid, summary) {
  if (!CAN_EDIT || !uid) return;
  const ok = confirm(`この更新履歴を削除します。よろしいですか？\n\n${summary || ''}\n\n※この操作は元に戻せません。`);
  if (!ok) return;

  try {
    await apiSend('updates.php', 'DELETE', { uid: Number(uid) });
    await loadItems();
    renderSeries();
    renderChips();
    render();
    readingId = null;
    openBook(itemId);
    toast('更新履歴を削除しました');
  } catch (e) {
    toast(e.message || '削除に失敗しました。', 4000);
  }
}

/* ---------- アイテム登録 ---------- */
/** シリーズ名の入力候補を、登録済みのものから作る */
function fillSeriesList() {
  const list = $('seriesList');
  if (!list) return;
  const names = [...new Set(items.map((i) => i.series).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'ja'));
  list.innerHTML = names.map((n) => `<option value="${esc(n)}"></option>`).join('');
}

/** アイテムの登録・修正フォームを開く（id を渡すと修正になる） */
/** 種別に合わせて管理IDを自動で入れる（利用者が自分で書き換えたあとは上書きしない） */
function updateSuggestedItemId() {
  const form = $('itemForm');
  if (form.dataset.mode !== 'create' || form.dataset.idAuto === '0') return;
  $('iId').value = nextItemId($('iCategory').value);
}

function openItemModal(id) {
  if (!CAN_EDIT) return;
  const form = $('itemForm');
  const it = id ? items.find((x) => x.id === id) : null;
  fillSeriesList();

  form.dataset.mode = it ? 'edit' : 'create';
  form.dataset.id = it ? it.id : '';
  form.dataset.idAuto = '1';           // 新規登録では、種別を選ぶたびにIDを自動で入れ直す
  $('itemModalTitle').textContent = it ? 'アイテムの修正' : 'アイテムの新規登録';
  $('btnItemSubmit').textContent = it ? '修正を保存' : '登録する';
  // 管理IDは他の記録とのつながりを保つため、修正では変更させない
  $('iId').readOnly = !!it;

  if (it) {
    $('iId').value = it.id;
    $('iName').value = it.name;
    $('iCategory').value = it.category;
    $('iSeries').value = it.series || '';
    $('iCreator').value = it.creator;
    $('iCreated').value = it.createdAt || '';
    $('iDesc').value = it.description || '';
    $('iUrl').value = it.downloadUrl || '';
  } else {
    form.reset();
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    $('iCreated').value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    updateSuggestedItemId();
  }

  $('iSeries').disabled = !DB_HAS_SERIES;
  if (!DB_HAS_SERIES) { $('iSeries').value = ''; }
  $('iSeriesNote').hidden = DB_HAS_SERIES;

  // 添付ファイル：新規登録では初版として、修正では「最新の更新」のファイルとして扱う
  const latestEntry = it ? latest(it) : null;
  const maxBytes = (LP_CFG && LP_CFG.uploadMaxBytes) || 20 * 1024 * 1024;
  $('iAttachment').value = '';
  fillAttachmentCurrent('i', latestEntry && latestEntry.attachment);
  $('iAttachmentHint').textContent = !it
    ? `初版として配布するファイルをここから登録できます（上限 ${fmtBytes(maxBytes)}）。あとから「この資料の更新を登録」でも追加できます。`
    : latestEntry
      ? `最新の更新（${latestEntry.summary}）に添付されているファイルです。差し替えたり、削除したりできます。`
      : 'まだ更新履歴がありません。ここでファイルを登録すると、初版として保存されます。';
  $('iAttachment').disabled = !DB_HAS_FILES;
  $('iAttachmentNote').hidden = DB_HAS_FILES;

  showModal($('itemModal'));
  (it ? $('iName') : $('iCategory')).focus();
}

/** 更新履歴1件ぶんの、添付ファイル以外の項目をそのまま引き継いだ FormData を作る */
function buildUpdateFormFrom(entry, itemId) {
  const fd = new FormData();
  fd.append('itemId', itemId);
  fd.append('date', entry.date);
  fd.append('time', entry.time);
  fd.append('author', entry.author);
  fd.append('kind', entry.kind);
  fd.append('bump', entry.bump === 'revision' ? 'revision' : 'minor');
  fd.append('summary', entry.summary);
  fd.append('target', entry.target);
  fd.append('ticket', entry.ticket || '');
  fd.append('downloadUrl', '');   // アイテムのURLはここでは触らない
  fd.append('filesJson', JSON.stringify(
    normFiles(entry).map((f) => (f.note ? `${f.path} : ${f.note}` : f.path))
  ));
  return fd;
}

async function submitItem(ev) {
  ev.preventDefault();
  const form = $('itemForm');
  const edit = form.dataset.mode === 'edit';
  const existing = edit ? items.find((x) => x.id === form.dataset.id) : null;
  const latestEntry = existing ? latest(existing) : null;
  const payload = {
    id: edit ? form.dataset.id : $('iId').value.trim(),
    name: $('iName').value.trim(),
    category: $('iCategory').value,
    series: $('iSeries').value.trim(),
    creator: $('iCreator').value.trim(),
    createdAt: $('iCreated').value,
    description: $('iDesc').value.trim(),
    downloadUrl: $('iUrl').value.trim()
  };

  // 添付ファイル：新規登録なら初版として、修正なら最新の更新のファイルとして扱う
  const file = $('iAttachment') ? ($('iAttachment').files[0] || null) : null;
  const removeFile = edit && !file && $('iRemoveAttachment') && $('iRemoveAttachment').checked;
  if (file) {
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    if (!UPLOAD_ALLOWED_EXT.includes(ext)) {
      formError('itemError', '画像（jpg / png / gif / webp）・PDF・ZIP のみアップロードできます。');
      return;
    }
    const max = (LP_CFG && LP_CFG.uploadMaxBytes) || 20 * 1024 * 1024;
    if (file.size > max) {
      formError('itemError', `ファイルサイズが大きすぎます（上限 ${fmtBytes(max)}）。`);
      return;
    }
  }

  try {
    await apiSend('items.php', edit ? 'PUT' : 'POST', payload);

    let fileWarning = '';
    if (file || removeFile) {
      try {
        if (latestEntry) {
          // 既にある「最新の更新」のファイルだけを差し替える／外す
          const fd = buildUpdateFormFrom(latestEntry, payload.id);
          fd.append('_method', 'PUT');
          fd.append('uid', latestEntry.uid);
          if (file) { fd.append('file', file); } else { fd.append('removeFile', '1'); }
          await apiSendMultipart('updates.php', fd);
        } else if (file) {
          // 更新履歴がまだ無い場合は、初版として新しく登録する（版数は必ず 1.00 になる）
          const now = new Date();
          const pad = (n) => String(n).padStart(2, '0');
          const fd = buildUpdateFormFrom({
            date: payload.createdAt,
            time: `${pad(now.getHours())}:${pad(now.getMinutes())}`,
            author: (LP_CFG && LP_CFG.user && LP_CFG.user.name) || payload.creator,
            kind: '初版公開',
            bump: 'minor',
            summary: `${payload.name}の初版を登録`,
            target: '初版',
            ticket: '',
            files: [],
          }, payload.id);
          fd.append('file', file);
          await apiSendMultipart('updates.php', fd);
        }
      } catch (e2) {
        fileWarning = e2.message || '添付ファイルの保存に失敗しました。';
      }
    }

    await loadItems();
    renderSeries();
    renderChips();
    render();
    // 登録・修正した内容がすぐ見えるよう、開いていたものは開き直す
    if (edit && readingId === payload.id) { readingId = null; openBook(payload.id); }
    hideModals();
    form.reset();
    if (fileWarning) {
      toast(`アイテムは保存しましたが、添付ファイルの処理に失敗しました：${fileWarning}`, 6000);
    } else if (file || removeFile) {
      toast(edit
        ? (file ? 'アイテムと添付ファイルを修正しました' : 'アイテムを修正し、添付ファイルを削除しました')
        : 'アイテムと初版のファイルを登録しました');
    } else {
      toast(edit ? 'アイテムを修正しました' : 'アイテムを登録しました');
    }
  } catch (e) {
    formError('itemError', e.message || (edit ? '修正' : '登録') + 'に失敗しました。');
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
/**
 * 一覧を取得できなかったときは、消えるトーストではなく画面に残る帯で理由を出す。
 * 「データの取得に失敗しました」だけだと、何をすれば直るのかが分からないため。
 */
function showLoadError(msg) {
  const main = document.querySelector('.lp-main');
  if (!main) { toast(msg); return; }

  let el = $('loadError');
  if (!el) {
    el = document.createElement('div');
    el.id = 'loadError';
    el.className = 'lp-alert';
    el.setAttribute('role', 'alert');
    main.insertBefore(el, main.firstElementChild);
  }
  el.innerHTML = `
    <p class="lp-alert-title">ライブラリを読み込めませんでした</p>
    <p class="lp-alert-msg"></p>
    <p class="lp-alert-act">
      <button class="lp-btn lp-btn-sm lp-btn-primary" type="button" data-reload>もう一度読み込む</button>
    </p>`;
  el.querySelector('.lp-alert-msg').textContent = msg;
  el.querySelector('[data-reload]').addEventListener('click', () => location.reload());
}

let toastTimer = null;
function toast(msg, ms = 2800) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, ms);
}

/* ---------- 初期化 ---------- */
async function init() {
  try {
    await loadItems();
  } catch (e) {
    if (String(e.message) !== 'unauthorized') showLoadError(e.message);
    items = [];
  }
  // 見開きの受け皿を一覧の直後に用意する（index.php / preview.html 共通）
  const listEl = $('list');
  if (!$('spread')) {
    const spread = document.createElement('div');
    spread.id = 'spread';
    spread.className = 'lp-spread';
    spread.hidden = true;
    listEl.insertAdjacentElement('afterend', spread);
  }

  try {
    const saved = localStorage.getItem('lp-view');
    if (saved === 'list' || saved === 'shelf') state.view = saved;
  } catch (e) { /* 読めなくても既定（本棚）で動く */ }
  document.querySelectorAll('[data-view]').forEach((b) =>
    b.classList.toggle('is-on', b.dataset.view === state.view));

  renderSortOptions();
  renderSeries();
  renderChips();
  render();
  checkStylesLoaded();

  $('searchInput').addEventListener('input', (e) => { state.q = e.target.value; render(); });
  $('sortSelect').addEventListener('change', (e) => { state.sort = e.target.value; render(); });

  const seriesSel = $('seriesSelect');
  if (seriesSel) seriesSel.addEventListener('change', (e) => { state.series = e.target.value; render(); });

  // 一覧の見出しをクリックして並び替える（同じ項目なら昇順・降順を入れ替える）
  const listHead = $('listHead');
  if (listHead) listHead.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-sort]');
    if (!btn) return;
    const { field, dir } = parseSort(state.sort);
    const next = btn.dataset.sort === field && dir === 'desc' ? 'asc' : 'desc';
    state.sort = `${btn.dataset.sort}_${next}`;
    renderSortOptions();
    render();
  });

  document.querySelectorAll('[data-view]').forEach((b) =>
    b.addEventListener('click', () => setView(b.dataset.view)));

  $('chipRow').addEventListener('click', (e) => {
    const chip = e.target.closest('[data-cat]');
    if (!chip) return;
    state.category = chip.dataset.cat;
    renderChips();
    render();
  });

  $('list').addEventListener('click', (e) => {
    if (e.target.closest('#spread')) return;        // 見開きの中は専用の処理に任せる
    if (e.target.closest('.lp-url-link')) return;   // URLを直接開く。行の開閉はしない
    const book = e.target.closest('[data-book]');
    if (book) { openBook(book.dataset.book); return; }
    const add = e.target.closest('[data-add]');
    if (add) { openUpdateModal(add.dataset.add); return; }
  });

  // 見開きの中の操作（閉じる／更新を登録）
  $('spread').addEventListener('click', (e) => {
    // 見開きは一覧の中に差し込まれているため、ここで処理したクリックは
    // 上位（#list）へ伝えない。伝わると同じ操作が二重に走ってしまう
    if (e.target.closest('[data-close-spread], [data-series], [data-edit-item], [data-edit-update], [data-delete-update], [data-book], [data-add], [data-open-entry], [data-all-entries], [data-chr-order]')) {
      e.stopPropagation();
    }
    if (e.target.closest('[data-close-spread]')) { closeBook(); return; }
    const ser = e.target.closest('[data-series]');
    if (ser) {
      state.series = ser.dataset.series;
      renderSeries();
      closeBook();
      render();
      return;
    }
    const orderBtn = e.target.closest('[data-chr-order]');
    if (orderBtn) { setChronicleOrder(orderBtn.dataset.chrOrder); return; }
    const allBtn = e.target.closest('[data-all-entries]');
    if (allBtn) { toggleAllEntries(allBtn); return; }
    const entry = e.target.closest('[data-open-entry]');
    if (entry) { toggleEntry(entry); return; }
    const editItem = e.target.closest('[data-edit-item]');
    if (editItem) { openItemModal(editItem.dataset.editItem); return; }
    const editUp = e.target.closest('[data-edit-update]');
    if (editUp) { openUpdateModal(editUp.dataset.item, editUp.dataset.editUpdate); return; }
    const delUp = e.target.closest('[data-delete-update]');
    if (delUp) { deleteUpdate(delUp.dataset.item, delUp.dataset.deleteUpdate, delUp.dataset.summary); return; }
    const mate = e.target.closest('[data-book]');
    if (mate) { openBook(mate.dataset.book); return; }
    const add = e.target.closest('[data-add]');
    if (add) openUpdateModal(add.dataset.add);
  });

  // 行の見出しは role="button" の div のため、Enter / Space での開閉を自前で処理する
  $('list').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (e.target.closest('.lp-url-link')) return;    // リンク自体のキー操作は既定の動作に任せる
    const head = e.target.closest('[data-book]');
    if (!head) return;
    e.preventDefault();
    openBook(head.dataset.book);
  });

  // 以下は index.php（ログイン後の画面）にのみ存在する要素
  const bind = (id, ev, fn) => { const el = $(id); if (el) el.addEventListener(ev, fn); };
  bind('btnNewUpdate', 'click', () => openUpdateModal());
  bind('btnNewItem', 'click', () => openItemModal());
  bind('btnCloseModal', 'click', hideModals);
  bind('btnCancel', 'click', hideModals);
  bind('btnCloseItemModal', 'click', hideModals);
  bind('btnItemCancel', 'click', hideModals);
  bind('btnClosePwModal', 'click', hideModals);
  bind('btnPwCancel', 'click', hideModals);
  bind('modalOverlay', 'click', hideModals);
  bind('updateForm', 'submit', submitUpdate);
  bind('itemForm', 'submit', submitItem);
  bind('iCategory', 'change', updateSuggestedItemId);
  // 管理IDを自分で書き換えたら、以後は種別を変えても上書きしない
  bind('iId', 'input', () => { $('itemForm').dataset.idAuto = '0'; });
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

  // 「開く」は独立したウィンドウで起動する
  document.addEventListener('click', (e) => {
    const link = e.target.closest('.lp-dl, .lp-url-link');
    if (!link || !link.href) return;
    // 修飾キー付きや中クリックは、利用者の意図どおりブラウザに任せる
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    if (openInWindow(link.href)) e.preventDefault();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    closeUserMenu();
    hideModals();
    if (readingId) closeBook();
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

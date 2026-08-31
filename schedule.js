'use strict';

/* ============================================================
   シフト管理カレンダー — schedule.js  v1
   休み希望／稼働可能日の申請 → 確定入力 → ダブルブッキング検知
   データは localStorage のみに保存（外部送信なし）
   ============================================================ */

const STORAGE_KEY = 'ms-schedule-v1';
const WD = ['日', '月', '火', '水', '木', '金', '土'];
const WISH_AVAILABLE = 'available';
const WISH_OFF = 'off';

/** 業務内容の初期候補（過去に入力した値が自動で追加される） */
const WORK_TYPE_PRESETS = [
  '現場作業', '設置・工事', '点検・保守', '訪問対応', '打ち合わせ', '研修', '事務作業', 'その他',
];

/** 案件名の選択肢の初期値（設定画面の「案件名の登録・削除」で追加・削除できる。
 *  実際の選択肢は state.settings.projects） */
const PROJECT_PRESETS = [
  'リテイルオンサイト', 'JCOM', 'JT', '自社案件', 'くらしのマーケット', 'ミツモア', 'OES入替',
];
const PROJECT_FREE = '__free__';

/**
 * 1日を分ける時間帯（縦型カレンダーの列）
 *   start / end … その枠の標準の時間。見出しと入力の初期値に使う。
 *   from  / to  … その枠に含める範囲。標準より早い開始（AMの8:00、夜間の17:30）も
 *                 その枠に入るよう、標準時間より広くとってある。
 */
const SLOTS = [
  { id: 'early', name: '早朝', start: '05:00', end: '08:00', from: '00:00', to: '08:00' },
  { id: 'am',    name: 'AM',   start: '09:00', end: '12:00', from: '08:00', to: '12:00' },
  { id: 'p1',    name: 'P1',   start: '12:00', end: '15:00', from: '12:00', to: '15:00' },
  { id: 'p2',    name: 'P2',   start: '15:00', end: '18:00', from: '15:00', to: '17:30' },
  { id: 'night', name: '夜間', start: '18:00', end: '23:00', from: '17:30', to: '24:00' },
];

/** 早い開始があり得る枠（キー）と、その手前の枠（値＝そこに少しだけ色をにじませる） */
const PEEK_NEXT = { early: 'am', p2: 'night' };

function slotById(id) {
  for (let i = 0; i < SLOTS.length; i++) if (SLOTS[i].id === id) return SLOTS[i];
  return null;
}

/** 予定とその枠が重なっている分数 */
function slotOverlapMinutes(job, slot) {
  if (job.allDay) return 1440;
  const s = toMinutes(job.start);
  let e = toMinutes(job.end);
  if (s === null || e === null) return 0;
  if (e <= s) e += 1440;   // 日をまたぐ勤務
  const ss = toMinutes(slot.from);
  const se = toMinutes(slot.to);
  const today = Math.min(e, se) - Math.max(s, ss);
  const nextDay = Math.min(e, se + 1440) - Math.max(s, ss + 1440);
  return Math.max(0, today) + Math.max(0, nextDay);
}

/** 端に少し掛かるだけの予定は、その枠には出さない（30分以内） */
const SLOT_EDGE_MINUTES = 30;

/** 予定が掛かっている時間帯のID一覧（終日はすべての枠） */
function jobSlots(job) {
  if (job.allDay) return SLOTS.map((s) => s.id);
  const s = toMinutes(job.start);
  return SLOTS.filter((slot) => {
    const ov = slotOverlapMinutes(job, slot);
    if (ov <= 0) return false;
    // その枠の中で始まる予定は、短くても必ず出す（夜間の17:30開始など）
    const startsHere = s !== null && s >= toMinutes(slot.from) && s < toMinutes(slot.to);
    return startsHere || ov > SLOT_EDGE_MINUTES;
  }).map((slot) => slot.id);
}

/** その予定の“主”の枠＝開始時刻が入る枠（夜勤なら夜間が主で、翌朝は続き扱い） */
function primarySlotId(job) {
  const s = toMinutes(job.start);
  if (s !== null) {
    const found = SLOTS.filter((slot) => s >= toMinutes(slot.from) && s < toMinutes(slot.to));
    if (found.length) return found[0].id;
  }
  // 開始時刻が取れないときは、いちばん長く掛かっている枠にする
  let best = null;
  let bestOverlap = 0;
  SLOTS.forEach((slot) => {
    const ov = slotOverlapMinutes(job, slot);
    if (ov > bestOverlap) { bestOverlap = ov; best = slot.id; }
  });
  return best;
}

/** '08:00' → '8:00' */
function shortTime(hhmm) {
  return String(hhmm || '').replace(/^0/, '');
}

/**
 * 標準の開始時刻との違い。
 * 早い開始（AMの8:00、夜間の17:30）を見分けられるようにする。
 */
function startDiff(job, slot) {
  if (!slot || job.allDay) return null;
  const s = toMinutes(job.start);
  const std = toMinutes(slot.start);
  if (s === null || std === null || s === std) return null;
  return { kind: s < std ? 'early' : 'late', label: shortTime(job.start) };
}

/** その日・その枠に掛かっている予定 */
function jobsInSlot(dateKey, slotId) {
  return jobsOn(dateKey).filter((j) => jobSlots(j).indexOf(slotId) >= 0);
}

/* ------------------------------------------------------------
   日付ユーティリティ
   ------------------------------------------------------------ */

function pad2(n) { return String(n).padStart(2, '0'); }

/** Date → 'YYYY-MM-DD'（ローカル時刻基準） */
function toKey(d) {
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

/** 'YYYY-MM-DD' → Date（ローカル 0時） */
function fromKey(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** 'YYYY-MM-DD' → 通算日数（UTC基準の整数。時差の影響を受けない） */
function dayIndex(key) {
  const [y, m, d] = key.split('-').map(Number);
  return Math.floor(Date.UTC(y, m - 1, d) / 86400000);
}

function addDays(d, n) {
  const r = new Date(d.getTime());
  r.setDate(r.getDate() + n);
  return r;
}

function daysInMonth(year, month /* 0-11 */) {
  return new Date(year, month + 1, 0).getDate();
}

/** 'HH:MM' → 分。不正値は null */
function toMinutes(hhmm) {
  if (!hhmm || !/^\d{1,2}:\d{2}$/.test(hhmm)) return null;
  const [h, m] = hhmm.split(':').map(Number);
  if (h > 47 || m > 59) return null;
  return h * 60 + m;
}

function fromMinutes(min) {
  const m = ((min % 1440) + 1440) % 1440;
  return pad2(Math.floor(m / 60)) + ':' + pad2(m % 60);
}

function todayKey() { return toKey(new Date()); }

/** '2026-09-03' → '9/3(木)' / '2026年9月3日(木)' */
function formatDate(key, style) {
  const d = fromKey(key);
  const w = WD[d.getDay()];
  if (style === 'long') {
    return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日(${w})`;
  }
  return `${d.getMonth() + 1}/${d.getDate()}(${w})`;
}

/* ------------------------------------------------------------
   祝日（1949年以降の主要ルール。振替休日・国民の休日を含む）
   ------------------------------------------------------------ */

const holidayCache = new Map();

/** その年の n 番目の weekday(0=日) の日付 */
function nthWeekday(year, month /* 0-11 */, weekday, nth) {
  const first = new Date(year, month, 1).getDay();
  return 1 + ((weekday - first + 7) % 7) + (nth - 1) * 7;
}

/** 春分・秋分の近似式（1980〜2099年） */
function equinoxDay(year, isSpring) {
  const base = isSpring ? 20.8431 : 23.2488;
  return Math.floor(base + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4));
}

function holidaysOfYear(year) {
  if (holidayCache.has(year)) return holidayCache.get(year);

  const map = new Map();
  const put = (month, day, name) => {
    map.set(`${year}-${pad2(month)}-${pad2(day)}`, name);
  };

  put(1, 1, '元日');
  put(1, nthWeekday(year, 0, 1, 2), '成人の日');
  put(2, 11, '建国記念の日');
  if (year >= 2020) put(2, 23, '天皇誕生日');
  put(3, equinoxDay(year, true), '春分の日');
  put(4, 29, year >= 2007 ? '昭和の日' : 'みどりの日');
  put(5, 3, '憲法記念日');
  put(5, 4, 'みどりの日');
  put(5, 5, 'こどもの日');
  put(7, nthWeekday(year, 6, 1, 3), '海の日');
  put(8, 11, '山の日');
  put(9, nthWeekday(year, 8, 1, 3), '敬老の日');
  put(9, equinoxDay(year, false), '秋分の日');
  put(10, nthWeekday(year, 9, 1, 2), 'スポーツの日');
  put(11, 3, '文化の日');
  put(11, 23, '勤労感謝の日');

  // 国民の休日（祝日に挟まれた平日：主に9月のシルバーウィーク）
  const keys = Array.from(map.keys()).sort();
  keys.forEach((key) => {
    const next2 = toKey(addDays(fromKey(key), 2));
    const between = toKey(addDays(fromKey(key), 1));
    if (map.has(next2) && !map.has(between) && fromKey(between).getDay() !== 0) {
      map.set(between, '国民の休日');
    }
  });

  // 振替休日（日曜が祝日 → 直後の祝日でない日）
  Array.from(map.keys()).sort().forEach((key) => {
    if (fromKey(key).getDay() !== 0) return;
    let d = addDays(fromKey(key), 1);
    while (map.has(toKey(d))) d = addDays(d, 1);
    map.set(toKey(d), '振替休日');
  });

  holidayCache.set(year, map);
  return map;
}

function holidayName(key) {
  const year = Number(key.slice(0, 4));
  return holidaysOfYear(year).get(key) || null;
}

/* ------------------------------------------------------------
   状態
   ------------------------------------------------------------ */

/** 端末間で突き合わせるときの基準時刻 */
function nowIso() { return new Date().toISOString(); }

/** 更新時刻を持たない古いデータ用（必ず新しい入力に負ける） */
const EPOCH0 = '1970-01-01T00:00:00.000Z';

/** 削除の記録を残しておく期間（これを過ぎたら捨てる） */
const TOMBSTONE_DAYS = 180;

function defaultState() {
  return {
    version: 2,
    settings: {
      bufferMin: 0, defStart: '09:00', defEnd: '18:00', senderName: '',
      projects: PROJECT_PRESETS.slice(),   // 案件名の選択肢（登録・削除できる）
      projectCalendar: {},                 // 案件ごとのGoogleカレンダー登録内容のカスタマイズ
    },
    settingsAt: EPOCH0,
    wishes: {},      // 'YYYY-MM-DD' → 'available' | 'off'
    wishMeta: {},    // 'YYYY-MM-DD' → 更新時刻（未定に戻した日も記録する）
    jobs: [],        // { id, date, allDay, start, end, title, workType, ..., updatedAt }
    tombstones: [],  // [{ id, updatedAt }] 削除した予定の記録
  };
}

let state = defaultState();

const now = new Date();
const view = {
  year: now.getFullYear(),
  month: now.getMonth(),   // 0-11
  selected: null,          // 'YYYY-MM-DD'
  slot: null,              // 選択中の時間帯ID（日付ラベルを選んだときは null）
  formOpen: false,         // 登録フォームを開いているか（ダブルクリックで開く）
  popup: false,            // スマホでダブルタップしたときに、内容をポップアップで見せるか
  paint: null,             // null | 'available' | 'off' | 'clear' | 'multi'
  multi: new Set(),        // まとめて登録する枠（'YYYY-MM-DD|slotId'）
  editingId: null,
  confirming: false,       // 仮出勤 → 確定 への切り替え中
  jobFilter: 'all',        // 予定一覧の絞り込み
  ack: false,              // 「重複を承知で登録する」
  form: null,              // 入力途中の値を保持
};

let painting = false;
let paintAdd = true;      // 複数日モードでドラッグ中に追加するか解除するか
let lastTapKey = null;    // ダブルクリック／ダブルタップの判定用
let lastTapAt = 0;
let paintPointerId = null;   // 選択操作中のポインターID（スクロールとの誤操作防止用）
let paintIsTouch = false;
let paintStartX = 0;
let paintStartY = 0;
const SCROLL_CANCEL_PX = 10;   // これ以上指が動いたらスクロールとみなして選択を中止する
const DOUBLE_TAP_MS = 450;
const paintTouched = new Set();

function loadState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return;
    state = normalizeState(parsed);
  } catch (err) {
    console.warn('保存データの読み込みに失敗しました', err);
  }
}

/** 保存データ・受信データを、突き合わせできる形にそろえる（古い形式の移行も兼ねる） */
function normalizeState(parsed) {
  const base = defaultState();
  if (!parsed || typeof parsed !== 'object') return base;

  const plainMap = (v) => (v && typeof v === 'object' && !Array.isArray(v)) ? v : {};

  const wishes = plainMap(parsed.wishes);
  const wishMeta = plainMap(parsed.wishMeta);
  // 更新時刻のない古いデータは、いちばん古い時刻として扱う
  Object.keys(wishes).forEach((k) => { if (!wishMeta[k]) wishMeta[k] = EPOCH0; });

  const jobs = (Array.isArray(parsed.jobs) ? parsed.jobs : []).filter(isValidJob).map((j) => {
    const job = Object.assign({}, j);
    if (!job.updatedAt) job.updatedAt = job.confirmedAt || job.createdAt || EPOCH0;
    return job;
  });

  const limit = new Date(Date.now() - TOMBSTONE_DAYS * 86400000).toISOString();
  const tombstones = (Array.isArray(parsed.tombstones) ? parsed.tombstones : [])
    .filter((t) => t && typeof t.id === 'string')
    .map((t) => ({ id: t.id, updatedAt: t.updatedAt || EPOCH0 }))
    .filter((t) => t.updatedAt >= limit);

  return {
    version: 2,
    settings: Object.assign(base.settings, plainMap(parsed.settings)),
    settingsAt: parsed.settingsAt || EPOCH0,
    wishes: wishes,
    wishMeta: wishMeta,
    jobs: jobs,
    tombstones: tombstones,
  };
}

/**
 * 2つの状態を1件ずつ突き合わせて統合する。
 * 予定・希望それぞれについて更新時刻が新しいほうを採用するため、
 * どちらか一方の入力だけが消えることはない。
 */
function mergeStates(a, b) {
  const left = normalizeState(a);
  const right = normalizeState(b);
  const out = defaultState();

  /* ---- 削除の記録（新しいほうを残す） ---- */
  const tombs = new Map();
  left.tombstones.concat(right.tombstones).forEach((t) => {
    const cur = tombs.get(t.id);
    if (!cur || t.updatedAt > cur.updatedAt) tombs.set(t.id, t);
  });

  /* ---- 予定（同じIDは更新時刻が新しいほうを採用） ---- */
  const jobs = new Map();
  left.jobs.concat(right.jobs).forEach((j) => {
    const cur = jobs.get(j.id);
    // 同時刻なら片方だけが持つ内容を失わないよう、先に入ったものを残す
    if (!cur || j.updatedAt > cur.updatedAt) jobs.set(j.id, j);
  });

  // 削除の記録より後に編集されていれば、その予定は生き残る
  tombs.forEach((t, id) => {
    const job = jobs.get(id);
    if (job && job.updatedAt > t.updatedAt) tombs.delete(id);
    else jobs.delete(id);
  });

  out.jobs = Array.from(jobs.values());
  out.tombstones = Array.from(tombs.values());

  /* ---- 希望（未定に戻した記録も含めて突き合わせる） ---- */
  const dates = new Set(Object.keys(left.wishMeta).concat(Object.keys(right.wishMeta)));
  dates.forEach((d) => {
    const lt = left.wishMeta[d] || '';
    const rt = right.wishMeta[d] || '';
    let winner;
    if (lt > rt) winner = left;
    else if (rt > lt) winner = right;
    else winner = left.wishes[d] ? left : right;   // 同時刻なら値がある側を優先する
    out.wishMeta[d] = lt > rt ? lt : rt;
    if (winner.wishes[d]) out.wishes[d] = winner.wishes[d];
  });

  /* ---- 設定（新しいほうをまとめて採用） ---- */
  const newer = left.settingsAt >= right.settingsAt ? left : right;
  out.settings = Object.assign(out.settings, newer.settings);
  out.settingsAt = newer.settingsAt;

  return out;
}

function isValidJob(j) {
  return j && typeof j === 'object' && typeof j.id === 'string'
    && typeof j.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(j.date);
}

function saveState(skipSync) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch (err) {
    toast('保存に失敗しました（ブラウザの設定をご確認ください）', true);
  }
  if (!skipSync && typeof scheduleSyncPush === 'function') scheduleSyncPush();
}

function newId() {
  return 'j' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/* ------------------------------------------------------------
   予定の時間帯・重複判定
   ------------------------------------------------------------ */

/** 予定 → 絶対分レンジ {s, e}。終日は0:00〜24:00、終了が開始以下なら翌日にまたぐ扱い */
function jobRange(job) {
  const base = dayIndex(job.date) * 1440;
  if (job.allDay) return { s: base, e: base + 1440 };
  const s = toMinutes(job.start);
  let e = toMinutes(job.end);
  if (s === null || e === null) return { s: base, e: base + 1440 };
  if (e <= s) e += 1440;
  return { s: base + s, e: base + e };
}

/**
 * 2件の関係を返す。
 * 'overlap' = 時間そのものが重なっている（ダブルブッキング）
 * 'buffer'  = 重なってはいないが移動・準備時間が足りない
 */
function relation(a, b, buffer) {
  const A = jobRange(a);
  const B = jobRange(b);
  if (A.s < B.e && B.s < A.e) return 'overlap';
  // 片方の終わりともう片方の始まりがちょうど同じ（18:00終了 → 18:00開始）は、
  // 続けて入る予定として扱い、余裕時間の設定にかかわらず警告しない。
  if (A.e === B.s || B.e === A.s) return null;
  // 終日予定には開始・終了の境目がないため、移動・準備時間は判定しない。
  // （そうしないと、隣り合う日の終日予定どうしが誤って「間隔不足」になる）
  if (a.allDay || b.allDay) return null;
  if (buffer > 0 && A.s < B.e + buffer && B.s < A.e + buffer) return 'buffer';
  return null;
}

/** 対象の予定と衝突する既存予定を返す（自分自身と excludeId は除外） */
function findConflicts(job, excludeId) {
  const buffer = Number(state.settings.bufferMin) || 0;
  const out = [];
  state.jobs.forEach((other) => {
    if (other.id === excludeId || other.id === job.id) return;
    // 日をまたぐ可能性を考慮し、前後1日までを比較対象にする
    if (Math.abs(dayIndex(other.date) - dayIndex(job.date)) > 1) return;
    const type = relation(job, other, buffer);
    if (type) out.push({ job: other, type });
  });
  return out;
}

/** 衝突している予定IDの集合（全期間） */
function conflictingJobIds() {
  const buffer = Number(state.settings.bufferMin) || 0;
  const ids = new Set();
  const sorted = state.jobs.slice().sort((a, b) => jobRange(a).s - jobRange(b).s);
  for (let i = 0; i < sorted.length; i++) {
    for (let k = i + 1; k < sorted.length; k++) {
      if (jobRange(sorted[k]).s - jobRange(sorted[i]).e > 1440) break;
      if (relation(sorted[i], sorted[k], buffer)) {
        ids.add(sorted[i].id);
        ids.add(sorted[k].id);
      }
    }
  }
  return ids;
}

function jobsOn(dateKey) {
  return state.jobs
    .filter((j) => j.date === dateKey)
    .sort((a, b) => {
      if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
      return jobRange(a).s - jobRange(b).s;
    });
}

function jobDuration(job) {
  if (job.allDay) return 0;
  const r = jobRange(job);
  return (r.e - r.s) / 60;
}

/* ------------------------------------------------------------
   DOM 参照
   ------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);
let elCalendar, elMonthLabel, elMonthPicker, elSidePanel, elSideAside, elStats,
    elConflictBanner, elExportText, elAvailCount, elJobList, elJobCount, elToast;

/* ------------------------------------------------------------
   描画
   ------------------------------------------------------------ */

function escapeHtml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function renderAll() {
  renderMonthNav();
  renderCalendar();
  renderSidePanel();
  renderStats();
  renderConflictBanner();
  renderExport();
  renderJobList();
  renderIcsPreview();
}

function renderMonthNav() {
  elMonthLabel.textContent = `${view.year}年${view.month + 1}月`;
  elMonthPicker.value = `${view.year}-${pad2(view.month + 1)}`;
}

function renderCalendar() {
  const conflicts = conflictingJobIds();
  const today = todayKey();
  const total = daysInMonth(view.year, view.month);

  const head = '<div class="sc-vrow sc-vhead">'
    + '<div class="sc-vdate-head">日付</div>'
    + SLOTS.map((s2) => `<div class="sc-vslot-head">${escapeHtml(s2.name)}`
        + `<span class="sc-vslot-time">${s2.start}〜${s2.end}</span></div>`).join('')
    + '</div>';

  const rows = [];
  for (let d = 1; d <= total; d++) {
    const key = `${view.year}-${pad2(view.month + 1)}-${pad2(d)}`;
    const date = fromKey(key);
    const dow = date.getDay();
    const hol = holidayName(key);
    const wish = state.wishes[key] || null;
    const dayJobs = jobsOn(key);
    const hasConfirmed = dayJobs.some((j) => j.status !== 'tentative');
    const hasTentative = dayJobs.some((j) => j.status === 'tentative');
    const isEmpty = key >= today && !wish && !dayJobs.length;

    const dcls = ['sc-vdate'];
    if (key < today) dcls.push('sc-vdate-past');
    if (key === today) dcls.push('sc-vdate-today');
    if (dow === 0 || hol) dcls.push('sc-vdate-sun');
    else if (dow === 6) dcls.push('sc-vdate-sat');
    if (wish === WISH_AVAILABLE) dcls.push('sc-vdate-available');
    if (wish === WISH_OFF) dcls.push('sc-vdate-off');
    if (isEmpty) dcls.push('sc-vdate-empty');
    if (!view.slot && key === view.selected) dcls.push('sc-vdate-selected');

    const marks = [];
    if (wish === WISH_AVAILABLE) marks.push('<span class="sc-mark-available">◯</span>');
    if (wish === WISH_OFF) marks.push('<span class="sc-mark-off">✕</span>');
    if (isEmpty) marks.push('<span class="sc-mark-empty">未定</span>');

    const dateCell = `<button type="button" class="${dcls.join(' ')}" data-date="${key}" data-role="date"`
      + ` aria-label="${escapeHtml(`${view.month + 1}月${d}日 ${WD[dow]}曜日`)}">`
      + `<span class="sc-vdate-num">${d}</span>`
      + `<span class="sc-vdate-wd">${WD[dow]}</span>`
      + `<span class="sc-vdate-marks">${marks.join('')}</span>`
      + (hol ? `<span class="sc-vdate-hol">${escapeHtml(hol)}</span>` : '')
      + `</button>`;

    const slotJobs = {};
    SLOTS.forEach((s2) => { slotJobs[s2.id] = jobsInSlot(key, s2.id); });

    const cells = SLOTS.map((slot) => {
      const list = slotJobs[slot.id];
      const pick = key + '|' + slot.id;
      const cls = ['sc-vcell'];
      if (key < today) cls.push('sc-vcell-past');
      if (list.some((j) => j.status !== 'tentative')) cls.push('sc-vcell-confirmed');
      else if (list.length) cls.push('sc-vcell-tentative');
      if (list.some((j) => conflicts.has(j.id))) cls.push('sc-vcell-conflict');
      if (view.multi.has(pick)) cls.push('sc-vcell-multi');
      if (view.selected === key && view.slot === slot.id) cls.push('sc-vcell-selected');

      const inner = list.map((j) => {
        const isPrimary = j.allDay || primarySlotId(j) === slot.id;
        const diff = isPrimary ? startDiff(j, slot) : null;
        const cls = ['sc-vpill'];
        cls.push(j.status === 'tentative' ? 'sc-vpill-tentative' : 'sc-vpill-confirmed');
        if (!isPrimary) cls.push('sc-vpill-cont');       // 前の枠から続いている
        if (diff) cls.push('sc-vpill-' + diff.kind);

        const tag = j.status === 'tentative' ? '<span class="sc-vpill-mark">仮</span>' : '';
        const time = diff
          ? `<span class="sc-vtime sc-vtime-${diff.kind}">${diff.kind === 'early' ? '◀' : ''}${escapeHtml(diff.label)}</span>`
          : '';
        const tip = [formatDate(j.date), j.allDay ? '終日' : j.start + '〜' + j.end,
          j.title, j.workType, j.client, j.place, j.address].filter(Boolean).join(' / ')
          + (diff ? `（この枠の標準 ${slot.start} より${diff.kind === 'early' ? '早い' : '遅い'}開始）` : '')
          + (isPrimary ? '' : '（前の枠から続いています）');

        return `<span class="${cls.join(' ')}" title="${escapeHtml(tip)}">`
          + `${tag}${time}${escapeHtml(j.title || '(無題)')}</span>`;
      }).join('');

      // この枠が空きで、次の枠（AM／夜間）に早い開始の予定があれば、少しだけ色をにじませる
      let peek = '';
      const nextId = PEEK_NEXT[slot.id];
      if (!list.length && nextId) {
        const nextSlot = slotById(nextId);
        const early = slotJobs[nextId].find((j) => {
          if (j.allDay || primarySlotId(j) !== nextId) return false;
          const d2 = startDiff(j, nextSlot);
          return d2 && d2.kind === 'early';
        });
        if (early) {
          // 30分刻みのマスに区切って、そのうち何マス分早いかで幅を決める
          // （他の枠のバーと同じ塗りで、あくまで1本のバーに見えるようにする）
          const std = toMinutes(nextSlot.start);
          const totalUnits = (std - toMinutes(slot.start)) / 30;
          const units = Math.min(totalUnits, Math.max(1, Math.round((std - toMinutes(early.start)) / 30)));
          const width = Math.round((units / totalUnits) * 100);
          const cls2 = early.status === 'tentative' ? 'sc-vpeek-tentative' : 'sc-vpeek-confirmed';
          const tip = `${escapeHtml(early.title || '(無題)')}が${escapeHtml(shortTime(early.start))}から（${escapeHtml(nextSlot.name)}）`;
          peek = `<span class="sc-vpeek ${cls2}" style="width:${width}%" title="${tip}"></span>`;
        }
      }

      const label = `${view.month + 1}月${d}日 ${slot.name}`
        + (list.length ? ' ' + list.map((j) => j.title || '(無題)').join('、') : ' 空き');

      return `<button type="button" class="${cls.join(' ')}" data-date="${key}" data-slot="${slot.id}"`
        + ` data-pick="${pick}" aria-label="${escapeHtml(label)}">`
        + `${inner || peek || '<span class="sc-vcell-empty"></span>'}`
        + (view.multi.has(pick) ? '<span class="sc-vcell-check">✓</span>' : '')
        + `</button>`;
    }).join('');

    const rcls = ['sc-vrow'];
    if (dow === 0) rcls.push('sc-vrow-sun');
    if (dow === 6) rcls.push('sc-vrow-sat');
    // 休み希望の日は、枠まで含めて行ごと赤くする
    if (wish === WISH_OFF) rcls.push('sc-vrow-off');
    if (key === today) rcls.push('sc-vrow-today');
    rows.push(`<div class="${rcls.join(' ')}">${dateCell}${cells}</div>`);
  }

  elCalendar.innerHTML = head + rows.join('');
}

/** 予定の入力フォーム（単日・複数日で共用） */
function jobFormHtml(f) {
  return `
    <div class="sc-side-block">
      <p class="sc-side-block-title">${view.paint === 'multi' ? `選んだ ${view.multi.size} 日にまとめて追加`
        : view.confirming ? '仮出勤を確定にする' : view.editingId ? '予定を編集' : '予定を追加'}</p>
      ${view.confirming ? `<div class="sc-alert sc-alert-confirm">
        <span class="sc-alert-title">✓ 確定内容の確認</span>
        どの案件・どの業務で確定したのか、時間とあわせて確認してから「この内容で確定する」を押してください。
      </div>` : ''}
      <form id="jobForm" class="sc-form">
        ${view.paint !== 'multi' ? `<label class="sc-field">
          <span class="sc-field-label">日付 <span aria-hidden="true">*</span></span>
          <input id="fDate" class="sc-input" type="date" value="${escapeHtml(f.date)}">
        </label>` : ''}

        <label class="sc-field">
          <span class="sc-field-label">案件名 <span aria-hidden="true">*</span></span>
          <select id="fTitle" class="sc-input">
            <option value="" ${f.titleSel === '' ? 'selected' : ''}>選択してください</option>
            ${state.settings.projects.map((p) =>
              `<option value="${escapeHtml(p)}" ${f.titleSel === p ? 'selected' : ''}>${escapeHtml(p)}</option>`).join('')}
            <option value="${PROJECT_FREE}" ${f.titleSel === PROJECT_FREE ? 'selected' : ''}>フリー（自由入力）</option>
          </select>
        </label>

        <label class="sc-field" id="titleFreeRow" ${f.titleSel === PROJECT_FREE ? '' : 'hidden'}>
          <span class="sc-field-label">案件名を入力 <span aria-hidden="true">*</span></span>
          <input id="fTitleFree" class="sc-input" type="text" value="${escapeHtml(f.titleFree)}"
            placeholder="例）○○ホール 音響" autocomplete="off">
        </label>

        <label class="sc-field">
          <span class="sc-field-label">業務内容${f.status === 'confirmed' ? ' <span aria-hidden="true">*</span>' : '（確定時は必須）'}</span>
          <input id="fWorkType" class="sc-input" type="text" list="workTypeList"
            value="${escapeHtml(f.workType)}" placeholder="例）設置・工事／点検・保守" autocomplete="off">
        </label>

        <div class="sc-form-row">
          <label class="sc-field">
            <span class="sc-field-label">区分</span>
            <select id="fStatus" class="sc-input sc-status-select">
              <option value="confirmed" ${f.status === 'confirmed' ? 'selected' : ''}>確定</option>
              <option value="tentative" ${f.status === 'tentative' ? 'selected' : ''}>仮出勤（未確定）</option>
            </select>
          </label>
          <label class="sc-check" style="align-self:flex-end;padding-bottom:8px">
            <input id="fAllDay" type="checkbox" ${f.allDay ? 'checked' : ''}>
            <span>終日</span>
          </label>
        </div>

        ${view.paint === 'multi' && !f.allDay
          ? '<p class="sc-hint">ここで指定した時間が、選んだすべての枠にまとめて登録されます。</p>' : ''}
        <div class="sc-form-row" id="timeRow" ${f.allDay ? 'hidden' : ''}>
          <label class="sc-field">
            <span class="sc-field-label">開始</span>
            <input id="fStart" class="sc-input" type="time" value="${escapeHtml(f.start)}">
          </label>
          <label class="sc-field">
            <span class="sc-field-label">終了</span>
            <input id="fEnd" class="sc-input" type="time" value="${escapeHtml(f.end)}">
          </label>
        </div>

        <div class="sc-form-row">
          <label class="sc-field">
            <span class="sc-field-label">依頼元</span>
            <input id="fClient" class="sc-input" type="text" value="${escapeHtml(f.client)}" autocomplete="off">
          </label>
          <label class="sc-field">
            <span class="sc-field-label">場所</span>
            <input id="fPlace" class="sc-input" type="text" value="${escapeHtml(f.place)}" autocomplete="off">
          </label>
        </div>

        <label class="sc-field">
          <span class="sc-field-label">住所</span>
          <input id="fAddress" class="sc-input" type="text" value="${escapeHtml(f.address)}"
            placeholder="例）東京都渋谷区〇〇1-2-3" autocomplete="off">
        </label>
        <div class="sc-map-actions" id="mapActions" ${f.address.trim() ? '' : 'hidden'}>
          <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-map="google">📍 Googleマップで開く</button>
          <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-map="yahoo">📍 Yahoo!地図で開く</button>
        </div>

        <label class="sc-field">
          <span class="sc-field-label">メモ</span>
          <textarea id="fNote" class="sc-input" rows="3">${escapeHtml(f.note)}</textarea>
        </label>

        <div id="formAlert"></div>

        <div class="sc-form-actions">
          <button type="submit" id="fSubmit" class="sc-btn${view.confirming ? ' sc-btn-confirm' : ''}">${submitLabel()}</button>
          ${view.editingId ? '<button type="button" id="fCancel" class="sc-btn sc-btn-outline">中止</button>' : ''}
        </div>
      </form>
    </div>`;
}

/** 複数の枠をまとめて扱うときのパネル */
function renderMultiPanel() {
  const picks = Array.from(view.multi).sort();
  const f = view.form || blankForm();

  if (!picks.length) {
    elSidePanel.innerHTML = `
      <p class="sc-side-date">まとめて登録</p>
      <p class="sc-side-date-sub">カレンダーの枠をクリックして選んでください（ドラッグで連続選択）。</p>
      <p class="sc-side-empty">まだ選ばれていません。</p>`;
    return;
  }

  const chips = picks.map((pk) => {
    const [d, sid] = pk.split('|');
    const sl = slotById(sid);
    return `<button type="button" class="sc-date-chip" data-unpick="${pk}" title="外す">`
      + `${formatDate(d)} ${escapeHtml(sl ? sl.name : '')} <span aria-hidden="true">×</span></button>`;
  }).join('');

  const dates = Array.from(new Set(picks.map((pk) => pk.split('|')[0]))).sort();
  const busy = picks.filter((pk) => { const [d, sid] = pk.split('|'); return jobsInSlot(d, sid).length; });
  const offDays = dates.filter((d) => (state.wishes[d] || null) === WISH_OFF);

  elSidePanel.innerHTML = `
    <div>
      <p class="sc-side-date">${picks.length}枠を選択中</p>
      <p class="sc-side-date-sub">${dates.length}日ぶん。指定した時間で、同じ内容の予定をまとめて登録します。</p>
    </div>

    <div class="sc-side-block">
      <div class="sc-chip-row">${chips}</div>
      <button type="button" id="multiClear" class="sc-btn sc-btn-sm sc-btn-outline" style="margin-top:6px">選択をすべて解除</button>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">選んだ日の希望をまとめて設定</p>
      <div class="sc-wish-row">
        <button type="button" class="sc-wish-btn" data-multiwish="available">◯ 稼働可</button>
        <button type="button" class="sc-wish-btn" data-multiwish="off">✕ 休み希望</button>
        <button type="button" class="sc-wish-btn" data-multiwish="none">− 未定</button>
      </div>
    </div>

    ${(busy.length || offDays.length) ? `<div class="sc-side-block">
      ${busy.length ? `<p class="sc-job-meta">・すでに予定がある枠：${busy.length}枠</p>` : ''}
      ${offDays.length ? `<p class="sc-job-meta">・休み希望の日：${offDays.map((d) => formatDate(d)).join('、')}</p>` : ''}
    </div>` : ''}

    ${jobFormHtml(f)}
  `;

  updateFormAlert();
}

function renderSidePanel() {
  updateSideModalState();

  if (view.paint === 'multi') { renderMultiPanel(); return; }

  const key = view.selected;
  if (!key) {
    elSidePanel.innerHTML = '<p class="sc-side-empty">カレンダーの日付を選択してください。</p>';
    return;
  }

  const hol = holidayName(key);
  const wish = state.wishes[key] || null;
  const conflicts = conflictingJobIds();
  const slot = view.slot ? slotById(view.slot) : null;
  const dayJobs = slot ? jobsInSlot(key, slot.id) : jobsOn(key);

  const cards = dayJobs.map((j) => {
    const tentative = j.status === 'tentative';
    const cls = ['sc-job-card'];
    cls.push(tentative ? 'sc-job-card-tentative' : 'sc-job-card-confirmed');
    if (conflicts.has(j.id)) cls.push('sc-job-card-conflict');
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.client, j.place].filter(Boolean).map(escapeHtml).join(' / ');
    const gcalUrl = googleCalendarUrl(j);
    const others = findConflicts(j, null);
    const warn = others.length
      ? `<p class="sc-job-warn">⚠ ${others.map((c) => (c.type === 'overlap' ? '時間が重複' : '移動時間が不足') + '：' + formatDate(c.job.date) + ' ' + escapeHtml(c.job.title || '(無題)')).join(' / ')}</p>`
      : '';
    const loc = jobLocation(j);
    return `<div class="${cls.join(' ')}">
      <div class="sc-job-card-top">
        <span class="sc-job-status">${tentative ? '仮出勤' : '確定'}</span>
        <span class="sc-job-time">${escapeHtml(time)}</span>
        <span class="sc-job-title">${escapeHtml(j.title || '(無題)')}</span>
      </div>
      ${j.workType ? `<p class="sc-job-meta"><span class="sc-worktype-tag">${escapeHtml(j.workType)}</span></p>` : ''}
      ${meta ? `<p class="sc-job-meta">${meta}</p>` : ''}
      ${j.address ? `<p class="sc-job-meta">📍 ${escapeHtml(j.address)}</p>` : ''}
      ${j.note ? `<p class="sc-job-meta sc-job-note">📝 ${escapeHtml(j.note)}</p>` : ''}
      ${warn}
      <div class="sc-job-actions">
        ${tentative ? `<button type="button" class="sc-btn sc-btn-sm sc-btn-confirm" data-confirm="${j.id}">✓ 確定にする</button>` : ''}
        ${gcalUrl ? `<a class="sc-btn sc-btn-sm sc-btn-outline" href="${escapeHtml(gcalUrl)}" target="_blank" rel="noopener">📆 追加</a>` : ''}
        ${loc ? `<a class="sc-btn sc-btn-sm sc-btn-outline" href="${escapeHtml(mapUrl('google', loc))}" target="_blank" rel="noopener">📍 Googleマップ</a>` : ''}
        ${loc ? `<a class="sc-btn sc-btn-sm sc-btn-outline" href="${escapeHtml(mapUrl('yahoo', loc))}" target="_blank" rel="noopener">📍 Yahoo!地図</a>` : ''}
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-edit="${j.id}">編集</button>
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" data-del="${j.id}">削除</button>
      </div>
    </div>`;
  }).join('');

  const f = view.form || blankForm();

  elSidePanel.innerHTML = `
    <button type="button" class="sc-modal-close" data-modal-close aria-label="閉じる">✕</button>
    <div>
      <p class="sc-side-date">${formatDate(key, 'long')}${slot ? `　<span class="sc-slot-badge">${escapeHtml(slot.name)}</span>` : ''}</p>
      <p class="sc-side-date-sub">${slot ? `${slot.start}〜${slot.end}　` : ''}${hol ? '🎌 ' + escapeHtml(hol) : ''}${key < todayKey() ? ' （過去の日付）' : ''}</p>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">この日の希望</p>
      ${(view.paint === 'off' || view.paint === 'available') ? `<p class="sc-paint-notice">
        まとめて入力（${view.paint === 'off' ? '✕ 休み希望' : '◯ 稼働可'}）を選択中です。
        日付をタップすると確認してから登録します。設定し直したいときは、下のボタンでも変更できます。
      </p>` : ''}
      <div class="sc-wish-row">
        <button type="button" class="sc-wish-btn ${wish === WISH_AVAILABLE ? 'active-available' : ''} ${view.paint === 'available' ? 'sc-wish-btn-suggest' : ''}" data-wish="available">◯ 稼働可</button>
        <button type="button" class="sc-wish-btn ${wish === WISH_OFF ? 'active-off' : ''} ${view.paint === 'off' ? 'sc-wish-btn-suggest' : ''}" data-wish="off">✕ 休み希望</button>
        <button type="button" class="sc-wish-btn ${!wish ? 'active-none' : ''}" data-wish="none">− 未定</button>
      </div>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">${slot ? `この枠の予定` : 'この日の予定'}（${dayJobs.length}件）</p>
      ${cards || '<p class="sc-empty-note">まだ予定はありません。</p>'}
    </div>

    ${view.formOpen ? jobFormHtml(f) : `
    <div class="sc-side-block">
      <button type="button" id="openForm" class="sc-btn sc-btn-block">＋ ${slot ? 'この枠' : 'この日'}に予定を登録</button>
      <p class="sc-hint" style="margin-top:6px">
        枠をダブルクリック（スマホはダブルタップ）でも、この画面を開けます。
      </p>
    </div>`}
  `;

  updateFormAlert();
}

function renderStats() {
  const days = monthDayKeys();
  let avail = 0, off = 0, confirmedDays = 0, confirmed = 0, tentative = 0, hours = 0;
  days.forEach((key) => {
    const w = state.wishes[key];
    if (w === WISH_AVAILABLE) avail++;
    if (w === WISH_OFF) off++;
    const js = jobsOn(key);
    if (js.some((j) => j.status !== 'tentative')) confirmedDays++;
    js.forEach((j) => {
      if (j.status === 'tentative') tentative++;
      else { confirmed++; hours += jobDuration(j); }
    });
  });

  const stat = (label, value, unit, cls) =>
    `<div class="sc-stat${cls ? ' ' + cls : ''}"><span class="sc-stat-label">${label}</span>` +
    `<span class="sc-stat-value">${value}<span class="sc-stat-unit">${unit}</span></span></div>`;

  elStats.innerHTML =
    stat('確定', confirmed, '件', 'sc-stat-confirmed') +
    stat('仮出勤', tentative, '件', 'sc-stat-tentative') +
    stat('確定の稼働日', confirmedDays, '日') +
    stat('確定の稼働時間', Math.round(hours * 10) / 10, 'h') +
    stat('稼働可能', avail, '日') +
    stat('休み希望', off, '日');
}

/** 今月の業務内容ごとの件数・時間 */
function renderWorkTypeSummary() {
  const keys = monthDayKeys();
  const list = state.jobs.filter((j) => keys.includes(j.date) && j.status !== 'tentative');
  const box = $('workTypeSummary');
  if (!list.length) { box.innerHTML = ''; return; }

  const map = new Map();
  list.forEach((j) => {
    const k = (j.workType || '').trim() || '(業務内容 未設定)';
    const cur = map.get(k) || { count: 0, hours: 0 };
    cur.count++;
    cur.hours += jobDuration(j);
    map.set(k, cur);
  });

  const rows = Array.from(map.entries())
    .sort((a, b) => b[1].count - a[1].count)
    .map(([name, v]) =>
      `<span class="sc-worktype-stat"><span class="sc-worktype-tag">${escapeHtml(name)}</span>` +
      `${v.count}件${v.hours ? ' / ' + (Math.round(v.hours * 10) / 10) + 'h' : ''}</span>`)
    .join('');

  box.innerHTML = `<p class="sc-worktype-title">確定した仕事の業務内容別</p><div class="sc-worktype-row">${rows}</div>`;
}

function renderConflictBanner() {
  const conflicts = conflictingJobIds();
  const list = state.jobs.filter((j) => conflicts.has(j.id));
  if (!list.length) {
    elConflictBanner.hidden = true;
    elConflictBanner.innerHTML = '';
    return;
  }
  const dates = Array.from(new Set(list.map((j) => j.date))).sort();
  const items = dates.slice(0, 8).map((d) => {
    const names = list.filter((j) => j.date === d)
      .map((j) => escapeHtml(j.title || '(無題)')).join(' ／ ');
    return `<li><button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-goto="${d}">${formatDate(d, 'long')}</button> ${names}</li>`;
  }).join('');

  elConflictBanner.hidden = false;
  elConflictBanner.innerHTML =
    `<div class="sc-conflict-banner-inner">` +
      `<strong>⚠ ダブルブッキングの疑いが ${dates.length} 日分あります。</strong>` +
      `<ul class="sc-conflict-list">${items}` +
      (dates.length > 8 ? `<li>ほか ${dates.length - 8} 日</li>` : '') +
      `</ul></div>`;
}

function renderJobList() {
  const conflicts = conflictingJobIds();
  const keys = monthDayKeys();
  const all = state.jobs
    .filter((j) => keys.includes(j.date))
    .sort((a, b) => jobRange(a).s - jobRange(b).s);

  const list = all.filter((j) => {
    if (view.jobFilter === 'confirmed') return j.status !== 'tentative';
    if (view.jobFilter === 'tentative') return j.status === 'tentative';
    return true;
  });

  const nConfirmed = all.filter((j) => j.status !== 'tentative').length;
  const nTentative = all.length - nConfirmed;
  elJobCount.textContent = all.length ? `確定${nConfirmed}件 / 仮出勤${nTentative}件` : '';

  if (!list.length) {
    elJobList.innerHTML = '<p class="sc-empty-note">該当する予定はありません。</p>';
    renderWorkTypeSummary();
    return;
  }

  elJobList.innerHTML = list.map((j) => {
    const tentative = j.status === 'tentative';
    const cls = ['sc-joblist-row'];
    cls.push(tentative ? 'sc-joblist-row-tentative' : 'sc-joblist-row-confirmed');
    if (conflicts.has(j.id)) cls.push('sc-joblist-row-conflict');
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.client, j.place, j.address, j.note].filter(Boolean).map(escapeHtml).join(' / ');
    return `<div class="${cls.join(' ')}" data-goto="${j.date}">
      <span class="sc-joblist-badge ${tentative ? 'sc-badge-tentative' : 'sc-badge-confirmed'}">${tentative ? '仮出勤' : '確定'}</span>
      <span class="sc-joblist-date">${formatDate(j.date)}</span>
      <span class="sc-joblist-time">${escapeHtml(time)}</span>
      <span class="sc-joblist-title">${escapeHtml(j.title || '(無題)')}</span>
      ${j.workType ? `<span class="sc-worktype-tag">${escapeHtml(j.workType)}</span>` : ''}
      ${meta ? `<span class="sc-joblist-meta">${meta}</span>` : ''}
      ${conflicts.has(j.id) ? '<span class="sc-joblist-warn">⚠ 重複</span>' : ''}
    </div>`;
  }).join('');

  renderWorkTypeSummary();
}

/* ------------------------------------------------------------
   稼働可能日リストとメール文面
   ------------------------------------------------------------ */

function monthDayKeys() {
  const total = daysInMonth(view.year, view.month);
  const keys = [];
  for (let d = 1; d <= total; d++) {
    keys.push(`${view.year}-${pad2(view.month + 1)}-${pad2(d)}`);
  }
  return keys;
}

/** 一覧の対象名（見出し・集計に使う） */
const LIST_TARGET_NAMES = {
  off: '休み希望日', available: '稼働可能日',
  confirmed: '確定した稼働日', tentative: '仮出勤の日',
};

/** 案件の絞り込みで使う選択肢を作る（プリセット＋実際に使った案件名）。
 *  「日付をコピー」とGoogleカレンダー登録、両方の案件セレクトに反映する。 */
function refreshProjectFilterOptions() {
  const used = state.jobs.map((j) => (j.title || '').trim()).filter(Boolean);
  const all = Array.from(new Set(state.settings.projects.concat(used)));
  const optionsHtml = '<option value="">すべての案件</option>'
    + all.map((v) => `<option value="${escapeHtml(v)}">${escapeHtml(v)}</option>`).join('');
  [$('projectFilter'), $('icsProjectFilter')].forEach((sel) => {
    if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = optionsHtml;
    if (all.includes(prev)) sel.value = prev;
  });

  // 案件名の一覧が変わりうる操作（同期・バックアップ読込など）はすべてここを通るため、
  // 設定画面の「案件名」「案件ごとの登録内容」もあわせて最新にしておく
  renderProjectChips();
  refreshProjectCalendarOptions();
}

/* ------------------------------------------------------------
   案件名の登録・削除（設定画面）
   ------------------------------------------------------------ */

function renderProjectChips() {
  const box = $('projectChips');
  if (!box) return;
  box.innerHTML = state.settings.projects.length
    ? state.settings.projects.map((p) =>
        `<button type="button" class="sc-proj-chip" data-project="${escapeHtml(p)}" title="削除">${escapeHtml(p)} ✕</button>`).join('')
    : '<p class="sc-empty-note">まだ案件名が登録されていません。</p>';
}

function addProject(name) {
  const v = name.trim();
  if (!v) return;
  if (v === PROJECT_FREE) { toast('その名前は使えません', true); return; }
  if (state.settings.projects.includes(v)) { toast('すでに登録されています', true); return; }
  state.settings.projects.push(v);
  state.settingsAt = nowIso();
  saveState();
  refreshProjectFilterOptions();
  toast(`「${v}」を追加しました`);
}

function removeProject(name) {
  if (!confirm(`案件名「${name}」を選択肢から削除します。すでに登録した予定には影響しません。よろしいですか？`)) return;
  state.settings.projects = state.settings.projects.filter((p) => p !== name);
  delete state.settings.projectCalendar[name];
  state.settingsAt = nowIso();
  saveState();
  refreshProjectFilterOptions();
  toast(`「${name}」を削除しました`);
}

/* ------------------------------------------------------------
   案件ごとのGoogleカレンダー登録内容（設定画面）
   ------------------------------------------------------------ */

/** 選択中の案件のカスタム設定（未登録なら空のひな形） */
function currentPcConfig() {
  const p = $('pcProject').value;
  return state.settings.projectCalendar[p] || { title: '', address: '', description: '', clientOverrides: {} };
}

function refreshProjectCalendarOptions() {
  const sel = $('pcProject');
  if (!sel) return;
  const prev = sel.value;
  sel.innerHTML = state.settings.projects.map((p) => `<option value="${escapeHtml(p)}">${escapeHtml(p)}</option>`).join('');
  if (state.settings.projects.includes(prev)) sel.value = prev;
  loadProjectCalendarEditor();
}

function loadProjectCalendarEditor() {
  if (!$('pcProject') || !$('pcProject').value) {
    if ($('pcTitle')) { $('pcTitle').value = ''; $('pcAddress').value = ''; $('pcDescription').value = ''; }
    if ($('pcOverrideList')) $('pcOverrideList').innerHTML = '<p class="sc-empty-note">案件名を登録すると設定できます。</p>';
    return;
  }
  const cfg = currentPcConfig();
  $('pcTitle').value = cfg.title || '';
  $('pcAddress').value = cfg.address || '';
  $('pcDescription').value = cfg.description || '';
  renderOverrideList();
}

function renderOverrideList() {
  const box = $('pcOverrideList');
  if (!box) return;
  const cfg = currentPcConfig();
  const clients = Object.keys(cfg.clientOverrides || {});
  box.innerHTML = clients.length
    ? clients.map((c) => `<div class="sc-override-item">
        <div class="sc-override-head">
          <strong>${escapeHtml(c)}</strong>
          <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-override-edit="${escapeHtml(c)}">編集</button>
          <button type="button" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" data-override-del="${escapeHtml(c)}">削除</button>
        </div>
        <p class="sc-override-text">${escapeHtml(cfg.clientOverrides[c])}</p>
      </div>`).join('')
    : '<p class="sc-empty-note">まだ上書きはありません。</p>';
}

function saveProjectCalendar() {
  const p = $('pcProject').value;
  if (!p) { toast('案件がありません。先に案件名を登録してください', true); return; }
  const prev = state.settings.projectCalendar[p] || {};
  state.settings.projectCalendar[p] = {
    title: $('pcTitle').value.trim(),
    address: $('pcAddress').value.trim(),
    description: $('pcDescription').value.trim(),
    clientOverrides: prev.clientOverrides || {},
  };
  state.settingsAt = nowIso();
  saveState();
  toast(`「${p}」の登録内容を保存しました`);
}

function addOrUpdateOverride() {
  const p = $('pcProject').value;
  if (!p) { toast('案件がありません。先に案件名を登録してください', true); return; }
  const client = $('pcOverrideClient').value.trim();
  const text = $('pcOverrideText').value.trim();
  if (!client) { toast('依頼元名を入力してください', true); return; }
  if (!text) { toast('説明文を入力してください', true); return; }
  if (!state.settings.projectCalendar[p]) {
    state.settings.projectCalendar[p] = { title: '', address: '', description: '', clientOverrides: {} };
  }
  if (!state.settings.projectCalendar[p].clientOverrides) state.settings.projectCalendar[p].clientOverrides = {};
  state.settings.projectCalendar[p].clientOverrides[client] = text;
  state.settingsAt = nowIso();
  saveState();
  $('pcOverrideClient').value = '';
  $('pcOverrideText').value = '';
  renderOverrideList();
  toast(`「${client}」の上書きを保存しました`);
}

function removeOverride(client) {
  const p = $('pcProject').value;
  const cfg = state.settings.projectCalendar[p];
  if (!cfg || !cfg.clientOverrides) return;
  if (!confirm(`「${client}」の説明文の上書きを削除します。よろしいですか？`)) return;
  delete cfg.clientOverrides[client];
  state.settingsAt = nowIso();
  saveState();
  renderOverrideList();
  toast(`「${client}」の上書きを削除しました`);
}

function editOverride(client) {
  const cfg = currentPcConfig();
  const text = (cfg.clientOverrides || {})[client];
  if (text == null) return;
  $('pcOverrideClient').value = client;
  $('pcOverrideText').value = text;
  $('pcOverrideClient').focus();
}

function listedDayKeys() {
  const target = $('listTarget').value;
  const excludeBooked = $('excludeBooked').checked;
  const project = $('projectFilter').value;

  if (target === 'confirmed' || target === 'tentative') {
    const wantStatus = target === 'confirmed' ? 'confirmed' : 'tentative';
    return monthDayKeys().filter((key) => jobsOn(key).some((j) =>
      (j.status || 'confirmed') === wantStatus && (!project || j.title === project)));
  }

  return monthDayKeys().filter((key) => {
    const w = state.wishes[key] || null;
    if (target === 'off') return w === WISH_OFF;
    if (w !== WISH_AVAILABLE) return false;
    if (excludeBooked && jobsOn(key).length) return false;
    return true;
  });
}

function buildListText() {
  const keys = listedDayKeys();
  const target = $('listTarget').value;
  const format = $('exportFormat').value;
  const project = $('projectFilter').value;
  const isProject = (target === 'confirmed' || target === 'tentative') && project;
  const name = isProject ? `${project} の${LIST_TARGET_NAMES[target]}` : LIST_TARGET_NAMES[target];
  const title = `${view.year}年${view.month + 1}月`;

  if (!keys.length) {
    const howTo = target === 'off' ? 'カレンダーで「✕ 休み希望」を設定してください。'
      : target === 'available' ? 'カレンダーで「◯ 稼働可」を設定してください。'
      : isProject ? '案件名や対象の月をご確認ください。'
      : 'カレンダーで予定を登録すると、ここに表示されます。';
    return `${title} ${name}は登録されていません。\n${howTo}`;
  }

  if (format === 'inline') return keys.map((k) => formatDate(k)).join('、');
  if (format === 'dayonly') return keys.map((k) => Number(k.slice(8))).join('、') + '日';

  return [`${title} ${name}（全${keys.length}日）`, '']
    .concat(keys.map((k) => '・' + formatDate(k)))
    .join('\n');
}

function renderExport() {
  const target = $('listTarget').value;
  const isJobTarget = target === 'confirmed' || target === 'tentative';
  // 「予定が入っている日は除く」は稼働可能日のときだけ、案件の絞り込みは確定・仮出勤のときだけ意味がある
  $('excludeBookedWrap').hidden = target !== 'available';
  $('projectFilterWrap').hidden = !isJobTarget;

  const project = $('projectFilter').value;
  const keys = listedDayKeys();
  const name = (isJobTarget && project) ? `${project} の${LIST_TARGET_NAMES[target]}` : LIST_TARGET_NAMES[target];
  $('availCount').textContent = `${view.year}年${view.month + 1}月 ${name}：${keys.length}日`;
  elExportText.value = buildListText();
}

function buildJobsText() {
  const keys = monthDayKeys();
  const list = state.jobs
    .filter((j) => keys.includes(j.date))
    .sort((a, b) => jobRange(a).s - jobRange(b).s);
  if (!list.length) return `${view.year}年${view.month + 1}月の予定はありません。`;

  const nConfirmed = list.filter((j) => j.status !== 'tentative').length;
  const lines = [
    `${view.year}年${view.month + 1}月の予定（確定${nConfirmed}件 / 仮出勤${list.length - nConfirmed}件）`, '',
  ];
  list.forEach((j) => {
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.workType, j.client, j.place, j.address].filter(Boolean).join(' / ');
    lines.push(`・[${j.status === 'tentative' ? '仮出勤' : '確定'}] ${formatDate(j.date)} ${time} ${j.title || '(無題)'}`
      + (meta ? `（${meta}）` : ''));
  });
  return lines.join('\n');
}

/* ------------------------------------------------------------
   端末間の同期（サーバの sync.php に保存）
   ------------------------------------------------------------ */

const SYNC_KEY_STORE = 'ms-schedule-sync-v1';

/** 同期の設定は端末ごとの設定なので、予定データとは別に保存する */
let sync = { url: 'sync.php', key: '', auto: true, rev: 0, lastAt: null };
let installPrompt = null;   // 「アプリとして追加」用
let syncTimer = null;
let syncPoll = null;
let syncBusy = false;
let syncPending = false;

function loadSyncConfig() {
  try {
    const raw = localStorage.getItem(SYNC_KEY_STORE);
    if (raw) sync = Object.assign(sync, JSON.parse(raw) || {});
  } catch (err) { /* 既定値のまま */ }
}

function saveSyncConfig() {
  try {
    localStorage.setItem(SYNC_KEY_STORE, JSON.stringify(sync));
  } catch (err) { /* 保存できなくても動作は続ける */ }
}

function syncConfigured() {
  return !!(sync.url && sync.key && sync.key.trim().length >= 12);
}

function setSyncStatus(text, kind) {
  const el = $('syncStatus');
  if (el) {
    el.textContent = text;
    el.className = 'sc-sync-status' + (kind ? ' sc-sync-' + kind : '');
  }

  // メニューバーには短い状態だけ出す
  const badge = $('syncBadge');
  if (badge) {
    const label = !syncConfigured() ? '同期オフ'
      : kind === 'ok' ? '同期済み ' + syncTimeLabel(sync.lastAt)
      : kind === 'busy' ? '同期中…'
      : kind === 'error' ? '同期エラー'
      : '未同期';
    badge.textContent = label;
    badge.title = text;
    badge.className = 'sc-header-badge' + (kind === 'ok' ? ' sc-badge-ok'
      : kind === 'busy' ? ' sc-badge-busy' : kind === 'error' ? ' sc-badge-error' : '');
  }
}

function syncTimeLabel(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return `${d.getMonth() + 1}/${d.getDate()} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** サーバに送る中身（同期設定そのものは送らない） */
function syncPayload() {
  return {
    version: 2,
    settings: {
      bufferMin: state.settings.bufferMin,
      defStart: state.settings.defStart,
      defEnd: state.settings.defEnd,
      senderName: state.settings.senderName,
    },
    settingsAt: state.settingsAt,
    wishes: state.wishes,
    wishMeta: state.wishMeta,
    jobs: state.jobs,
    tombstones: state.tombstones,
  };
}

/** キーの順番や、PHP側で空の連想配列が [] になる違いを吸収して比べる */
function stableStringify(value) {
  if (Array.isArray(value)) return '[' + value.map(stableStringify).join(',') + ']';
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort()
      .map((k) => JSON.stringify(k) + ':' + stableStringify(value[k])).join(',') + '}';
  }
  return JSON.stringify(value === undefined ? null : value);
}

function syncFingerprint(data) {
  const wishes = (data && data.wishes && !Array.isArray(data.wishes)) ? data.wishes : {};
  const jobs = (Array.isArray(data && data.jobs) ? data.jobs : []).slice()
    .sort((a, b) => String(a && a.id).localeCompare(String(b && b.id)));
  return stableStringify({ wishes, jobs, settings: (data && data.settings) || {} });
}

function applySyncedData(data) {
  if (!data || typeof data !== 'object') return false;
  state = normalizeState(data);
  view.editingId = null;
  view.confirming = false;
  view.form = blankForm();
  saveState(true);   // 取り込んだ直後に送り返さない
  syncSettingsInputs();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  renderAll();
  return true;
}

function syncEndpoint(params) {
  const base = sync.url.trim();
  const sep = base.indexOf('?') >= 0 ? '&' : '?';
  return base + sep + params;
}

/**
 * サーバの内容とこの端末の内容を突き合わせて、両方を残した状態にそろえる。
 * 1件ずつ新しいほうを採用するので、確認を挟まなくても入力が消えない。
 */
async function syncNow(silent, attempt) {
  if (!syncConfigured()) {
    if (!silent) toast('URLと合言葉（12文字以上）を入力してください', true);
    return false;
  }
  if (syncBusy) { syncPending = true; return false; }
  syncBusy = true;
  const tries = attempt || 1;
  if (!silent || tries === 1) setSyncStatus('同期中…', 'busy');

  try {
    const res = await fetch(syncEndpoint('key=' + encodeURIComponent(sync.key)), { cache: 'no-store' });
    const got = await res.json();
    if (!res.ok || !got.ok) throw new Error(got.error || ('通信エラー（' + res.status + '）'));

    const before = syncFingerprint(syncPayload());
    const serverData = got.data || null;
    const merged = serverData ? mergeStates(syncPayload(), serverData) : normalizeState(syncPayload());
    const mergedPayload = {
      version: 2,
      settings: merged.settings, settingsAt: merged.settingsAt,
      wishes: merged.wishes, wishMeta: merged.wishMeta,
      jobs: merged.jobs, tombstones: merged.tombstones,
    };

    // この端末に足りない分があれば取り込む（確認は不要。消えるものはない）
    const localChanged = syncFingerprint(mergedPayload) !== before;
    if (localChanged) {
      state = normalizeState(mergedPayload);
      view.editingId = null;
      view.confirming = false;
      view.form = blankForm();
      saveState(true);
      syncSettingsInputs();
      refreshWorkTypeOptions();
      refreshProjectFilterOptions();
      renderAll();
    }

    // サーバに足りない分があれば送る
    const serverChanged = !serverData || syncFingerprint(mergedPayload) !== syncFingerprint(serverData);
    if (serverChanged) {
      const put = await fetch(sync.url.trim(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key: sync.key, baseRev: got.rev, data: mergedPayload }),
      });
      if (put.status === 409) {
        // 送っている間に他の端末が保存した。取り直してもう一度突き合わせる
        syncBusy = false;
        if (tries < 4) return syncNow(silent, tries + 1);
        throw new Error('他の端末の更新と重なりました。もう一度お試しください');
      }
      const saved = await put.json();
      if (!put.ok || !saved.ok) throw new Error(saved.error || ('通信エラー（' + put.status + '）'));
      sync.rev = saved.rev;
    } else {
      sync.rev = got.rev;
    }

    sync.lastAt = nowIso();
    saveSyncConfig();

    const label = localChanged
      ? '同期しました（' + syncTimeLabel(sync.lastAt) + '）'
      : '最新の状態です（' + syncTimeLabel(sync.lastAt) + '）';
    setSyncStatus(label, 'ok');
    if (!silent) toast(localChanged ? '同期しました' : 'すでに最新の状態です');

    if (syncPending) { syncPending = false; syncBusy = false; return syncNow(true, 1); }
    return true;
  } catch (err) {
    setSyncStatus('同期できません：' + err.message, 'error');
    if (!silent) toast('同期できません：' + err.message, true);
    return false;
  } finally {
    syncBusy = false;
  }
}

/** 変更のたびに呼ばれる。まとめて少し遅らせて同期する */
function scheduleSyncPush() {
  if (!sync.auto || !syncConfigured()) return;
  clearTimeout(syncTimer);
  setSyncStatus('変更あり（まもなく同期します）', 'busy');
  syncTimer = setTimeout(() => { syncNow(true); }, 1200);
}

/** 画面を開いている間、一定間隔で他の端末の変更を拾う */
function startSyncPolling() {
  clearInterval(syncPoll);
  if (!sync.auto || !syncConfigured()) return;
  syncPoll = setInterval(() => {
    if (document.visibilityState === 'visible' && !syncBusy) syncNow(true);
  }, 45000);
}

async function syncTest() {
  if (!syncConfigured()) { toast('URLと合言葉（12文字以上）を入力してください', true); return; }
  setSyncStatus('確認中…', 'busy');
  try {
    const res = await fetch(syncEndpoint('action=ping&key=' + encodeURIComponent(sync.key)), { cache: 'no-store' });
    const json = await res.json();
    if (!res.ok || !json.ok) throw new Error(json.error || ('通信エラー（' + res.status + '）'));
    const label = json.rev === 0
      ? 'つながりました（サーバにはまだデータがありません）'
      : 'つながりました（予定' + json.jobs + '件・最終保存 ' + (syncTimeLabel(json.updatedAt) || '不明') + '）';
    setSyncStatus(label, 'ok');
    toast(label);
  } catch (err) {
    setSyncStatus('つながりません：' + err.message, 'error');
    toast('つながりません：' + err.message, true);
  }
}

function generateSyncKey() {
  const chars = 'abcdefghijkmnpqrstuvwxyz23456789';
  const buf = new Uint8Array(16);
  (window.crypto || window.msCrypto).getRandomValues(buf);
  let out = '';
  for (let i = 0; i < buf.length; i++) {
    if (i > 0 && i % 4 === 0) out += '-';
    out += chars[buf[i] % chars.length];
  }
  return out;
}

function syncSyncInputs() {
  $('syncUrl').value = sync.url || '';
  $('syncKey').value = sync.key || '';
  $('syncAuto').checked = !!sync.auto;
  if (!syncConfigured()) setSyncStatus('未設定', '');
  else if (sync.lastAt) setSyncStatus('前回の同期 ' + syncTimeLabel(sync.lastAt), '');
  else setSyncStatus('設定済み（未同期）', '');
}

/* ------------------------------------------------------------
   Googleカレンダー連携（iCalendar / .ics の書き出し）
   ------------------------------------------------------------ */

/** RFC 5545 のテキスト値エスケープ */
function icsEscape(value) {
  return String(value == null ? '' : value)
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

function utf8Len(ch) {
  const c = ch.codePointAt(0);
  if (c < 0x80) return 1;
  if (c < 0x800) return 2;
  if (c < 0x10000) return 3;
  return 4;
}

/**
 * 1行を75オクテット以内に折り返す（RFC 5545）。
 * 日本語が途中で壊れないよう、バイト数で数えつつ文字単位で分割する。
 */
function foldIcsLine(line) {
  const parts = [];
  let cur = '';
  let bytes = 0;
  let limit = 75;
  for (const ch of line) {
    const n = utf8Len(ch);
    if (bytes + n > limit) {
      parts.push(cur);
      cur = '';
      bytes = 0;
      limit = 74;   // 継続行は先頭の空白1文字ぶんを差し引く
    }
    cur += ch;
    bytes += n;
  }
  parts.push(cur);
  return parts[0] + parts.slice(1).map((s) => '\r\n ' + s).join('');
}

function icsDate(dateKey) { return dateKey.replace(/-/g, ''); }

/** 'YYYY-MM-DD' + 分 → 'YYYYMMDDTHHMMSS'（タイムゾーン指定なし＝取り込み先の時刻として扱われる） */
function icsLocalDateTime(dateKey, minutes) {
  const day = addDays(fromKey(dateKey), Math.floor(minutes / 1440));
  const m = ((minutes % 1440) + 1440) % 1440;
  return icsDate(toKey(day)) + 'T' + pad2(Math.floor(m / 60)) + pad2(m % 60) + '00';
}

function icsStamp() {
  const d = new Date();
  return d.getUTCFullYear() + pad2(d.getUTCMonth() + 1) + pad2(d.getUTCDate()) + 'T'
    + pad2(d.getUTCHours()) + pad2(d.getUTCMinutes()) + pad2(d.getUTCSeconds()) + 'Z';
}

/** 書き出し対象の予定 */
function icsTargetJobs() {
  const scope = $('icsScope').value;
  const project = $('icsProjectFilter') ? $('icsProjectFilter').value : '';
  const monthKeys = monthDayKeys();
  const today = todayKey();
  return state.jobs.filter((j) => {
    if (project && j.title !== project) return false;
    if (scope === 'month-confirmed') return monthKeys.includes(j.date) && j.status !== 'tentative';
    if (scope === 'month-all') return monthKeys.includes(j.date);
    if (scope === 'future-confirmed') return j.date >= today && j.status !== 'tentative';
    return true;
  }).sort((a, b) => jobRange(a).s - jobRange(b).s);
}

/**
 * タイトル・住所・説明文のテンプレートに、その予定の内容を差し込む。
 * {案件名}{依頼元}{業務内容}{場所}{住所}{日付}{開始}{終了}{メモ} が使える。
 */
function fillTemplate(tpl, job) {
  const map = {
    '案件名': job.title || '',
    '依頼元': job.client || '',
    '業務内容': job.workType || '',
    '場所': job.place || '',
    '住所': job.address || '',
    '日付': formatDate(job.date, 'long'),
    '開始': job.allDay ? '' : (job.start || ''),
    '終了': job.allDay ? '' : (job.end || ''),
    'メモ': job.note || '',
  };
  return String(tpl).replace(/\{(案件名|依頼元|業務内容|場所|住所|日付|開始|終了|メモ)\}/g, (m, key) => map[key]);
}

function icsSummary(job, prefix) {
  const tentative = job.status === 'tentative';
  const cfg = state.settings.projectCalendar[job.title];
  const base = (cfg && cfg.title && cfg.title.trim()) ? fillTemplate(cfg.title, job) : (job.title || '(無題)');
  return (prefix ? prefix : '') + (tentative ? '【仮】' : '') + base;
}

/** Googleカレンダー登録用の「場所」。案件ごとのカスタム住所があればそちらを使う */
function icsLocation(job) {
  const cfg = state.settings.projectCalendar[job.title];
  if (cfg && cfg.address && cfg.address.trim()) return fillTemplate(cfg.address, job);
  return jobLocation(job);
}

/** Googleカレンダー登録用の説明文。依頼元ごとの上書き→案件のカスタム説明文→既定の順で使う */
function icsDescription(job) {
  const cfg = state.settings.projectCalendar[job.title];
  if (cfg) {
    const override = cfg.clientOverrides && job.client ? cfg.clientOverrides[job.client] : null;
    if (override && override.trim()) return fillTemplate(override, job);
    if (cfg.description && cfg.description.trim()) return fillTemplate(cfg.description, job);
  }
  return [
    job.workType ? '業務内容: ' + job.workType : '',
    job.client ? '依頼元: ' + job.client : '',
    job.note ? 'メモ: ' + job.note : '',
    job.status === 'tentative' ? '※ 未確定（仮出勤）' : '',
  ].filter(Boolean).join('\n');
}

/** 予定の配列から .ics 本文を組み立てる */
function buildIcs(jobs, prefix) {
  const stamp = icsStamp();
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//shift-calendar//JP',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'X-WR-CALNAME:' + icsEscape('シフト管理カレンダー'),
  ];

  jobs.forEach((job) => {
    // 日時を先に組み立て、作れない予定は VEVENT ごと飛ばす
    // （途中で抜けると閉じタグのないファイルになり、取り込みが丸ごと失敗するため）
    const timeLines = [];
    if (job.allDay) {
      timeLines.push('DTSTART;VALUE=DATE:' + icsDate(job.date));
      timeLines.push('DTEND;VALUE=DATE:' + icsDate(toKey(addDays(fromKey(job.date), 1))));
    } else {
      const s = toMinutes(job.start);
      let e = toMinutes(job.end);
      if (s === null || e === null) return;
      if (e <= s) e += 1440;   // 日をまたぐ勤務
      timeLines.push('DTSTART:' + icsLocalDateTime(job.date, s));
      timeLines.push('DTEND:' + icsLocalDateTime(job.date, e));
    }

    const desc = icsDescription(job);

    lines.push('BEGIN:VEVENT');
    lines.push('UID:' + job.id + '@shift-calendar');
    lines.push('DTSTAMP:' + stamp);
    lines.push('SEQUENCE:' + (Number(job.rev) || 0));
    timeLines.forEach((l) => lines.push(l));
    lines.push('SUMMARY:' + icsEscape(icsSummary(job, prefix)));
    if (desc) lines.push('DESCRIPTION:' + icsEscape(desc));
    if (icsLocation(job)) lines.push('LOCATION:' + icsEscape(icsLocation(job)));
    if (job.workType) lines.push('CATEGORIES:' + icsEscape(job.workType));
    lines.push('STATUS:' + (job.status === 'tentative' ? 'TENTATIVE' : 'CONFIRMED'));
    lines.push('END:VEVENT');
  });

  lines.push('END:VCALENDAR');
  return lines.map(foldIcsLine).join('\r\n') + '\r\n';
}

function renderIcsPreview() {
  const jobs = icsTargetJobs();
  const prefix = $('icsPrefix').value.trim();
  const nTentative = jobs.filter((j) => j.status === 'tentative').length;

  $('icsCount').textContent = jobs.length
    ? `${jobs.length}件（うち仮出勤${nTentative}件）` : '対象なし';
  $('downloadIcs').disabled = jobs.length === 0;

  if (!jobs.length) {
    $('icsPreview').innerHTML = '<p class="sc-empty-note">対象の予定がありません。区分や対象範囲をご確認ください。</p>';
    return;
  }

  const rows = jobs.slice(0, 12).map((j) => {
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    return `<li><span class="sc-ics-date">${formatDate(j.date)}</span>` +
      `<span class="sc-ics-time">${escapeHtml(time)}</span>` +
      `<span>${escapeHtml(icsSummary(j, prefix))}</span></li>`;
  }).join('');

  $('icsPreview').innerHTML =
    `<p class="sc-worktype-title">取り込まれる予定</p><ul class="sc-ics-list">${rows}` +
    (jobs.length > 12 ? `<li class="sc-empty-note">ほか ${jobs.length - 12}件</li>` : '') + '</ul>';
}

function downloadIcs() {
  const jobs = icsTargetJobs();
  if (!jobs.length) { toast('対象の予定がありません', true); return; }

  const text = buildIcs(jobs, $('icsPrefix').value.trim());
  const blob = new Blob([text], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const scope = $('icsScope').value;
  a.href = url;
  a.download = scope.startsWith('month')
    ? `shift-${view.year}-${pad2(view.month + 1)}.ics`
    : `shift-${todayKey()}.ics`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast(`${jobs.length}件を書き出しました。Googleカレンダーの設定→インポートから取り込めます`);
}

/** 場所と住所をまとめた表示・地図検索用の文字列 */
function jobLocation(job) {
  return [job.place, job.address].filter(Boolean).join(' ');
}

/** 住所を地図アプリで開くURL（スマホでは該当のアプリが開く） */
function mapUrl(kind, address) {
  const q = encodeURIComponent(address);
  if (kind === 'google') return `https://www.google.com/maps/search/?api=1&query=${q}`;
  if (kind === 'yahoo') return `https://map.yahoo.co.jp/search?p=${q}`;
  return null;
}

/** 1件だけGoogleカレンダーに追加するURL（スマホでも使える） */
function googleCalendarUrl(job) {
  let dates;
  if (job.allDay) {
    dates = icsDate(job.date) + '/' + icsDate(toKey(addDays(fromKey(job.date), 1)));
  } else {
    const s = toMinutes(job.start);
    let e = toMinutes(job.end);
    if (s === null || e === null) return null;
    if (e <= s) e += 1440;
    dates = icsLocalDateTime(job.date, s) + '/' + icsLocalDateTime(job.date, e);
  }
  const details = icsDescription(job);

  return 'https://calendar.google.com/calendar/render?action=TEMPLATE'
    + '&text=' + encodeURIComponent(icsSummary(job, ''))
    + '&dates=' + dates
    + '&ctz=Asia/Tokyo'
    + (details ? '&details=' + encodeURIComponent(details) : '')
    + (icsLocation(job) ? '&location=' + encodeURIComponent(icsLocation(job)) : '');
}

/* ------------------------------------------------------------
   入力フォーム
   ------------------------------------------------------------ */

function blankForm() {
  const slot = view.slot ? slotById(view.slot) : null;
  return {
    date: view.selected || todayKey(),
    titleSel: '', titleFree: '', workType: '', status: 'confirmed', allDay: false,
    start: slot ? slot.start : state.settings.defStart,
    end: slot ? slot.end : state.settings.defEnd,
    client: '', place: '', address: '', note: '',
  };
}

function formFromJob(job) {
  const title = job.title || '';
  const preset = state.settings.projects.includes(title);
  return {
    date: job.date,
    titleSel: title ? (preset ? title : PROJECT_FREE) : '',
    titleFree: preset ? '' : title,
    workType: job.workType || '',
    status: job.status || 'confirmed',
    allDay: !!job.allDay,
    start: job.start || state.settings.defStart,
    end: job.end || state.settings.defEnd,
    client: job.client || '',
    place: job.place || '',
    address: job.address || '',
    note: job.note || '',
  };
}

function readForm() {
  if (!$('jobForm')) return null;
  const dateEl = $('fDate');
  return {
    date: dateEl ? dateEl.value : (view.selected || ''),
    titleSel: $('fTitle').value,
    titleFree: $('fTitleFree').value,
    workType: $('fWorkType').value,
    status: $('fStatus').value,
    allDay: $('fAllDay').checked,
    start: $('fStart').value,
    end: $('fEnd').value,
    client: $('fClient').value,
    place: $('fPlace').value,
    address: $('fAddress').value,
    note: $('fNote').value,
  };
}

/** 業務内容の入力候補（プリセット＋過去に登録した値） */
function refreshWorkTypeOptions() {
  const used = state.jobs.map((j) => (j.workType || '').trim()).filter(Boolean);
  const all = Array.from(new Set(WORK_TYPE_PRESETS.concat(used)));
  const dl = $('workTypeList');
  if (dl) dl.innerHTML = all.map((v) => `<option value="${escapeHtml(v)}"></option>`).join('');
}

/** 選択中の案件名（フリーなら自由入力の内容） */
function formTitle(f) {
  return f.titleSel === PROJECT_FREE ? f.titleFree.trim() : f.titleSel;
}

/** フォームの現在値から仮の予定オブジェクトを作る */
function draftJob() {
  const f = view.form || blankForm();
  return {
    id: view.editingId || '__draft__',
    date: f.date != null ? f.date : (view.selected || ''),
    allDay: !!f.allDay,
    start: f.start || state.settings.defStart,
    end: f.end || state.settings.defEnd,
    title: formTitle(f),
    workType: f.workType.trim(),
    client: f.client.trim(),
    place: f.place.trim(),
    address: f.address.trim(),
    note: f.note.trim(),
    status: f.status,
  };
}

/** 重複・注意事項を再計算して警告欄だけを更新（入力中のフォーカスを維持） */
function updateFormAlert() {
  const alertBox = $('formAlert');
  if (!alertBox) return;
  if (view.paint === 'multi') { updateMultiFormAlert(alertBox); return; }
  if (!view.selected) return;

  // まだ案件名を選んでいない＝入力を始めていない状態では判定しない。
  // （日付を選んだだけで、既定の時刻と既存予定を突き合わせた警告が出てしまうため）
  if (!view.editingId && !(view.form && view.form.titleSel)) {
    alertBox.innerHTML = '';
    view.ack = false;
    const btn0 = $('fSubmit');
    if (btn0) {
      btn0.disabled = false;
      btn0.textContent = submitLabel(false);
    }
    return;
  }

  const draft = draftJob();
  const conflicts = findConflicts(draft, view.editingId);
  const overlaps = conflicts.filter((c) => c.type === 'overlap');
  const buffers = conflicts.filter((c) => c.type === 'buffer');

  const warns = [];
  if ((state.wishes[draft.date] || null) === WISH_OFF) {
    warns.push('この日は「休み希望」に設定されています。');
  }
  if (draft.date < todayKey()) {
    warns.push('過去の日付です。');
  }
  if (!draft.allDay) {
    const s = toMinutes(draft.start);
    const e = toMinutes(draft.end);
    if (s === null || e === null) warns.push('開始・終了時刻を入力してください。');
    else if (e <= s) warns.push('終了が開始以前のため、日をまたぐ予定として扱います。');
  }

  let html = '';

  if (overlaps.length || buffers.length) {
    const items = conflicts.map((c) => {
      const t = c.job.allDay ? '終日' : `${c.job.start}〜${c.job.end}`;
      const kind = c.type === 'overlap' ? '時間が重複' : `移動・準備時間（${state.settings.bufferMin}分）が不足`;
      return `<li>${formatDate(c.job.date)} ${escapeHtml(t)} ${escapeHtml(c.job.title || '(無題)')} … ${kind}</li>`;
    }).join('');
    const cls = overlaps.length ? 'sc-alert-danger' : 'sc-alert-warn';
    const head = overlaps.length
      ? '⚠ ダブルブッキングです'
      : '⚠ 前後の予定と間隔が足りません';
    html += `<div class="sc-alert ${cls}">
      <span class="sc-alert-title">${head}</span>
      <ul>${items}</ul>
      <label class="sc-check" style="margin-top:6px;color:inherit">
        <input id="fAck" type="checkbox" ${view.ack ? 'checked' : ''}>
        <span>重複を承知のうえで登録する</span>
      </label>
    </div>`;
  }

  if (warns.length) {
    html += `<div class="sc-alert sc-alert-warn" style="margin-top:6px">
      <span class="sc-alert-title">確認してください</span>
      <ul>${warns.map((w) => `<li>${escapeHtml(w)}</li>`).join('')}</ul>
    </div>`;
  }

  alertBox.innerHTML = html;

  if (!conflicts.length) view.ack = false;

  const btn = $('fSubmit');
  if (btn) {
    const needAck = conflicts.length > 0;
    btn.disabled = needAck && !view.ack;
    btn.textContent = submitLabel(needAck);
  }
}

/** 選んだ枠それぞれについて、登録しようとしている内容の重複を調べる */
function multiDraft(pick) {
  const [date, sid] = pick.split('|');
  const base = draftJob();
  return Object.assign(base, {
    id: '__draft__', date: date,
    slot: sid,
  });
}

function multiConflicts() {
  const f = view.form || blankForm();
  if (!f.titleSel) return [];
  return Array.from(view.multi).sort().map((pick) => {
    const [date, sid] = pick.split('|');
    const slot = slotById(sid);
    return { date: date, slotName: slot ? slot.name : '', conflicts: findConflicts(multiDraft(pick), null) };
  }).filter((r) => r.conflicts.length);
}

function updateMultiFormAlert(alertBox) {
  const f = view.form || blankForm();
  let html = '';

  if (!f.titleSel) {
    alertBox.innerHTML = '';
    view.ack = false;
  } else {
    const found = multiConflicts();
    if (found.length) {
      const items = found.map((r) => {
        const names = r.conflicts.map((c) => escapeHtml(c.job.title || '(無題)')
          + (c.type === 'overlap' ? '（時間が重複）' : '（間隔が不足）')).join('、');
        return `<li>${formatDate(r.date)} ${escapeHtml(r.slotName)}：${names}</li>`;
      }).join('');
      html = `<div class="sc-alert sc-alert-danger">
        <span class="sc-alert-title">⚠ ${found.length}枠が既存の予定と重なります</span>
        <ul>${items}</ul>
        <label class="sc-check" style="margin-top:6px;color:inherit">
          <input id="fAck" type="checkbox" ${view.ack ? 'checked' : ''}>
          <span>重複を承知のうえで登録する</span>
        </label>
      </div>`;
    } else {
      view.ack = false;
    }
    alertBox.innerHTML = html;
  }

  const btn = $('fSubmit');
  if (btn) {
    const needAck = html !== '';
    btn.disabled = (needAck && !view.ack) || view.multi.size === 0;
    btn.textContent = submitLabel(needAck);
  }
}

/** 送信ボタンの文言（登録／更新／確定 × 重複の有無） */
function submitLabel(hasConflict) {
  if (view.paint === 'multi') {
    const n = view.multi.size;
    return hasConflict ? `重複を承知で ${n}枠に登録` : `${n}枠にまとめて登録`;
  }
  if (view.confirming) return hasConflict ? '重複を承知で確定する' : 'この内容で確定する';
  if (view.editingId) return hasConflict ? '重複を承知で更新' : '更新する';
  return hasConflict ? '重複を承知で登録' : '登録する';
}

/** 選んだ日すべてに同じ内容の予定を作る */
function submitMultiJobs() {
  const base = draftJob();
  const picks = Array.from(view.multi).sort();

  if (!view.form.titleSel) { toast('案件名を選択してください', true); $('fTitle').focus(); return; }
  if (!base.title) { toast('案件名を入力してください', true); $('fTitleFree').focus(); return; }
  if (base.status === 'confirmed' && !base.workType) {
    toast('確定にするには業務内容を入力してください', true);
    $('fWorkType').focus();
    return;
  }
  if (!base.allDay && (toMinutes(base.start) === null || toMinutes(base.end) === null)) {
    toast('開始・終了時刻を入力してください', true);
    return;
  }
  if (multiConflicts().length && !view.ack) {
    updateFormAlert();
    toast('重複しています。内容を確認してチェックを入れてください', true);
    return;
  }

  const at = nowIso();
  picks.forEach((pick) => {
    const job = Object.assign({}, multiDraft(pick), {
      id: newId(), createdAt: at, updatedAt: at,
    });
    if (job.status === 'confirmed') job.confirmedAt = at;
    state.jobs.push(job);
    if (state.wishes[job.date] === WISH_OFF) setWish(job.date, null, true);
  });

  view.multi.clear();
  view.ack = false;
  view.form = blankForm();
  saveState();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  renderAll();
  toast(`${picks.length}枠に登録しました`);
}

function submitJob(ev) {
  ev.preventDefault();
  view.form = readForm();

  if (view.paint === 'multi') { submitMultiJobs(); return; }

  const draft = draftJob();
  if (!view.form.titleSel) {
    toast('案件名を選択してください', true);
    $('fTitle').focus();
    return;
  }
  if (!draft.title) {
    toast('案件名を入力してください', true);
    $('fTitleFree').focus();
    return;
  }
  if (draft.status === 'confirmed' && !draft.workType) {
    toast('確定にするには業務内容を入力してください', true);
    $('fWorkType').focus();
    return;
  }
  if (!draft.date) {
    toast('日付を入力してください', true);
    const el = $('fDate');
    if (el) el.focus();
    return;
  }
  if (!draft.allDay) {
    if (toMinutes(draft.start) === null || toMinutes(draft.end) === null) {
      toast('開始・終了時刻を入力してください', true);
      return;
    }
  }

  const conflicts = findConflicts(draft, view.editingId);
  if (conflicts.length && !view.ack) {
    updateFormAlert();
    toast('重複しています。内容を確認してチェックを入れてください', true);
    return;
  }

  const wasConfirming = view.confirming;
  let savedId = view.editingId;

  if (view.editingId) {
    const idx = state.jobs.findIndex((j) => j.id === view.editingId);
    if (idx >= 0) {
      const prev = state.jobs[idx];
      state.jobs[idx] = Object.assign({}, prev, draft, { id: view.editingId, updatedAt: nowIso() });
      if (draft.status === 'confirmed' && state.jobs[idx].confirmedAt == null) {
        state.jobs[idx].confirmedAt = new Date().toISOString();
      }
      // 取り込み済みのGoogleカレンダー側を更新できるよう版数を上げる
      state.jobs[idx].rev = (Number(prev.rev) || 0) + 1;
    }
    toast(wasConfirming ? '確定しました' : '予定を更新しました');
  } else {
    draft.id = newId();
    draft.createdAt = nowIso();
    draft.updatedAt = draft.createdAt;
    if (draft.status === 'confirmed') draft.confirmedAt = draft.createdAt;
    state.jobs.push(draft);
    savedId = draft.id;
    toast(conflicts.length ? '重複を承知で登録しました' : '予定を登録しました');
  }

  // 予定を入れた日は自動で「休み希望」を解除して稼働扱いにそろえる
  if (state.wishes[draft.date] === WISH_OFF) delete state.wishes[draft.date];

  // 日付を変更した場合は、変更後の日（月をまたいでいればその月）に表示を合わせる
  view.selected = draft.date;
  const movedTo = fromKey(draft.date);
  if (movedTo.getMonth() !== view.month || movedTo.getFullYear() !== view.year) {
    view.year = movedTo.getFullYear();
    view.month = movedTo.getMonth();
  }

  view.editingId = null;
  view.confirming = false;
  view.ack = false;
  view.form = blankForm();
  // 狭い画面ではポップアップのまま残ると裏の操作ができなくなるため閉じる
  // （広い画面は続けて登録しやすいよう、これまでどおり開いたままにする）
  if (window.innerWidth <= 900) { view.formOpen = false; view.popup = false; }
  saveState();

  // 確定させたときは、同じ時間帯に残っている仮出勤をまとめて取り消せるようにする
  if (draft.status === 'confirmed') cleanupTentatives(savedId);

  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  renderAll();
}

/** 確定した予定と重なって残っている仮出勤の取り消しを促す */
function cleanupTentatives(confirmedId) {
  const job = state.jobs.find((j) => j.id === confirmedId);
  if (!job) return;
  const leftovers = findConflicts(job, null)
    .filter((c) => c.type === 'overlap' && c.job.status === 'tentative')
    .map((c) => c.job);
  if (!leftovers.length) return;

  const names = leftovers
    .map((j) => `・${formatDate(j.date)} ${j.allDay ? '終日' : j.start + '〜' + j.end} ${j.title || '(無題)'}`)
    .join('\n');
  if (!confirm(`確定した予定と重なっている仮出勤が ${leftovers.length}件 あります。\n\n${names}\n\nこれらを取り消しますか？（「キャンセル」で残します）`)) return;

  const ids = new Set(leftovers.map((j) => j.id));
  removeJobs(ids);
  saveState();
  toast(`仮出勤 ${ids.size}件 を取り消しました`);
}

/** 仮出勤カードの「確定にする」 */
function startConfirm(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  view.selected = job.date;
  view.editingId = id;
  view.confirming = true;
  view.formOpen = true;
  view.ack = false;
  view.form = Object.assign(formFromJob(job), { status: 'confirmed' });
  const d = fromKey(job.date);
  view.year = d.getFullYear();
  view.month = d.getMonth();
  renderAll();
  const el = $('fWorkType');
  if (el) el.focus();
}

function startEdit(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  view.selected = job.date;
  view.editingId = id;
  view.confirming = false;
  view.formOpen = true;
  view.ack = false;
  view.form = formFromJob(job);
  const d = fromKey(job.date);
  view.year = d.getFullYear();
  view.month = d.getMonth();
  renderAll();
  const el = $('fTitle');
  if (el) el.focus();
}

function deleteJob(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  if (!confirm(`「${job.title || '(無題)'}」（${formatDate(job.date, 'long')}）を削除します。よろしいですか？`)) return;
  removeJobs(new Set([id]));
  if (view.editingId === id) {
    view.editingId = null;
    view.confirming = false;
    view.form = blankForm();
  }
  // 狭い画面ではポップアップのまま残ると裏の操作ができなくなるため閉じる
  if (window.innerWidth <= 900) { view.formOpen = false; view.popup = false; }
  saveState();
  renderAll();
  toast('予定を削除しました');
}

/* ------------------------------------------------------------
   希望日の設定
   ------------------------------------------------------------ */

/** 予定を消す。他の端末にも削除が伝わるよう記録を残す */
function removeJobs(ids) {
  const at = nowIso();
  state.jobs = state.jobs.filter((j) => !ids.has(j.id));
  ids.forEach((id) => {
    state.tombstones = state.tombstones.filter((t) => t.id !== id);
    state.tombstones.push({ id: id, updatedAt: at });
  });
}

function setWish(dateKey, wish, skipSave) {
  if (!wish) delete state.wishes[dateKey];
  else state.wishes[dateKey] = wish;
  state.wishMeta[dateKey] = nowIso();   // 未定に戻した場合も記録を残す
  if (!skipSave) saveState();
}

function applyPaint(dateKey) {
  if (!view.paint) return false;
  if (view.paint === 'clear') setWish(dateKey, null);
  else setWish(dateKey, view.paint);
  return true;
}

/**
 * まとめて入力（休み希望／稼働可／未定に戻す）モードで1日だけタップしたときに、
 * その場で確認してから確定する。誤タップは防ぎつつ、パネルまで移動する手間はなくす。
 */
function confirmSingleDayPaint(dateKey) {
  const label = view.paint === 'off' ? '休み希望' : view.paint === 'available' ? '稼働可' : '未定';
  const verb = view.paint === 'clear' ? 'に戻します' : 'で登録します';
  let msg = `${formatDate(dateKey, 'long')}を「${label}」${verb}。\nよろしいですか？`;

  const busy = jobsOn(dateKey);
  if (view.paint === 'off' && busy.length) {
    msg += `\n\n※この日にはすでに予定が${busy.length}件あります。`;
  }

  const ok = confirm(msg);
  if (ok) applyPaint(dateKey);
  selectDate(dateKey, { noScroll: true });
  if (ok) toast(`${formatDate(dateKey)}を${label}にしました`);
}

/* ------------------------------------------------------------
   コピー・トースト
   ------------------------------------------------------------ */

let toastTimer = null;
function toast(msg, isError) {
  elToast.textContent = msg;
  elToast.className = 'sc-toast' + (isError ? ' sc-toast-error' : '');
  elToast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { elToast.hidden = true; }, 2600);
}

async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (err) { /* フォールバックへ */ }

  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch (err) {
    return false;
  }
}

/* ------------------------------------------------------------
   データ入出力
   ------------------------------------------------------------ */

function exportJson() {
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `schedule-backup-${todayKey()}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('バックアップを保存しました');
}

function importJson(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result));
      if (!parsed || typeof parsed !== 'object') throw new Error('形式が不正です');
      const jobs = Array.isArray(parsed.jobs) ? parsed.jobs.filter(isValidJob) : [];
      const wishes = parsed.wishes && typeof parsed.wishes === 'object' ? parsed.wishes : {};
      if (!confirm(`現在のデータを置き換えます。\n予定 ${jobs.length}件 / 希望 ${Object.keys(wishes).length}日\nよろしいですか？`)) return;
      state = {
        version: 1,
        settings: Object.assign(defaultState().settings, parsed.settings || {}),
        wishes,
        jobs,
      };
      view.editingId = null;
      view.confirming = false;
      view.form = blankForm();
      saveState();
      syncSettingsInputs();
      refreshWorkTypeOptions();
      refreshProjectFilterOptions();
      renderAll();
      toast('バックアップを読み込みました');
    } catch (err) {
      toast('読み込みに失敗しました：' + err.message, true);
    }
  };
  reader.readAsText(file);
}

function syncSettingsInputs() {
  $('bufferMin').value = String(state.settings.bufferMin);
  $('defStart').value = state.settings.defStart;
  $('defEnd').value = state.settings.defEnd;
}

/* ------------------------------------------------------------
   イベント登録
   ------------------------------------------------------------ */

function moveMonth(delta) {
  const d = new Date(view.year, view.month + delta, 1);
  view.year = d.getFullYear();
  view.month = d.getMonth();
  renderAll();
}

/** 「選択のみ」でドラッグしたときに、複数選択モードへ切り替える */
function switchToMulti() {
  view.paint = 'multi';
  view.editingId = null;
  view.confirming = false;
  view.ack = false;
  view.form = blankForm();
  document.querySelectorAll('.sc-paint-tab').forEach((t) => {
    t.classList.toggle('active', t.dataset.paint === 'multi');
  });
  const hint = $('paintHint');
  if (hint) hint.textContent = 'まとめて登録中です。枠をクリックで追加・解除、ドラッグで連続選択できます。終わったら「選択のみ」に戻してください。';
  const cal = document.querySelector('.sc-calendar');
  if (cal) cal.classList.add('sc-calendar-painting');
}

function selectDate(key, opts) {
  view.selected = key;
  view.slot = (opts && opts.slot) || null;
  view.formOpen = !!(opts && opts.openForm);
  view.popup = !!(opts && opts.popup);
  view.editingId = null;
  view.confirming = false;
  view.ack = false;
  view.form = blankForm();
  const d = fromKey(key);
  if (d.getMonth() !== view.month || d.getFullYear() !== view.year) {
    view.year = d.getFullYear();
    view.month = d.getMonth();
  }
  renderAll();
}

/** 登録フォームを閉じて、選んだ日の内容表示に戻す */
function closeForm() {
  view.editingId = null;
  view.confirming = false;
  view.formOpen = false;
  view.popup = false;
  view.ack = false;
  view.form = blankForm();
  renderSidePanel();
}

/** 狭い画面では、ダブルタップしたときの内容をカレンダーの上に重ねて表示する */
function updateSideModalState() {
  if (!elSideAside) return;
  const open = (view.formOpen || view.popup) && view.paint !== 'multi' && window.innerWidth <= 900;
  elSideAside.classList.toggle('sc-side-modal-open', open);
  document.body.classList.toggle('sc-modal-open-body', open);
}

function bindEvents() {
  $('prevMonth').addEventListener('click', () => moveMonth(-1));
  $('nextMonth').addEventListener('click', () => moveMonth(1));
  $('todayBtn').addEventListener('click', () => {
    const d = new Date();
    view.year = d.getFullYear();
    view.month = d.getMonth();
    renderAll();
  });
  elMonthPicker.addEventListener('change', () => {
    const v = elMonthPicker.value;
    if (!/^\d{4}-\d{2}$/.test(v)) return;
    view.year = Number(v.slice(0, 4));
    view.month = Number(v.slice(5, 7)) - 1;
    renderAll();
  });

  // まとめて入力モード
  document.querySelectorAll('.sc-paint-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.sc-paint-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      const v = tab.dataset.paint;
      view.paint = v === 'off-mode' ? null : v;
      if (view.paint !== 'multi') view.multi.clear();
      view.ack = false;
      view.form = blankForm();
      renderAll();
      $('paintHint').textContent = view.paint
        ? 'まとめて入力中です。1日だけクリックすると確認してから登録します。ドラッグすると複数日を確認なしで一括設定できます。終わったら「選択のみ」に戻してください。'
        : '日付をクリックすると詳細パネルが開きます。まとめて入力を選ぶと、クリック（ドラッグ）で一括設定できます。';
      document.querySelector('.sc-calendar').classList.toggle('sc-calendar-painting', !!view.paint);
    });
  });

  // カレンダー：日付ラベル＝希望の設定、枠＝予定の登録
  elCalendar.addEventListener('pointerdown', (ev) => {
    const dateBtn = ev.target.closest('[data-role="date"]');
    const cell = ev.target.closest('[data-pick]');
    if (!dateBtn && !cell) return;

    // スマホでのスクロール操作との誤操作防止用に、押した位置を覚えておく
    paintPointerId = ev.pointerId;
    paintIsTouch = ev.pointerType === 'touch';
    paintStartX = ev.clientX;
    paintStartY = ev.clientY;

    // 希望の塗りは日付ラベルの列だけ。
    // ここではまだ反映しない（1回押しただけで休みになってしまう誤操作を防ぐため）。
    // ドラッグして2日目に触れた時点でまとめて反映する。1日だけなら endPaint で選ぶだけにする。
    if (dateBtn && view.paint && view.paint !== 'multi') {
      painting = true;
      paintTouched.clear();
      paintTouched.add(dateBtn.dataset.date);
      renderCalendar();
      return;
    }

    if (cell && view.paint === 'multi') {
      painting = true;
      paintTouched.clear();
      paintTouched.add(cell.dataset.pick);
      paintAdd = !view.multi.has(cell.dataset.pick);
      if (paintAdd) view.multi.add(cell.dataset.pick); else view.multi.delete(cell.dataset.pick);
      renderCalendar();
      renderSidePanel();
      return;
    }

    // 選択のみのときは、ドラッグでまとめて選べるようにする
    if (cell && !view.paint) {
      painting = true;
      paintTouched.clear();
      paintTouched.add(cell.dataset.pick);
      paintAdd = true;
    }
  });

  elCalendar.addEventListener('pointerover', (ev) => {
    if (!painting) return;

    if (view.paint && view.paint !== 'multi') {
      const dateBtn = ev.target.closest('[data-role="date"]');
      if (!dateBtn || paintTouched.has(dateBtn.dataset.date)) return;
      // ここまで来た＝2日目以降に触れた＝ドラッグでの範囲選択と判断し、
      // 触れた日をまとめて反映する（1日目もまだ反映していなければ含める）。
      const wasSingle = paintTouched.size === 1;
      paintTouched.add(dateBtn.dataset.date);
      if (wasSingle) paintTouched.forEach((d) => applyPaint(d));
      else applyPaint(dateBtn.dataset.date);
      renderCalendar();
      return;
    }

    const cell = ev.target.closest('[data-pick]');
    if (!cell || paintTouched.has(cell.dataset.pick)) return;
    paintTouched.add(cell.dataset.pick);

    if (view.paint === 'multi') {
      if (paintAdd) view.multi.add(cell.dataset.pick); else view.multi.delete(cell.dataset.pick);
    } else {
      // 選択のみでドラッグしたときは、その場で複数選択に切り替える
      if (view.paint !== 'multi') switchToMulti();
      paintTouched.forEach((pk) => view.multi.add(pk));
    }
    renderCalendar();
    renderSidePanel();
  });

  const endPaint = () => {
    if (!painting) return;
    painting = false;
    if (view.paint === 'multi') { renderAll(); return; }
    if (view.paint) {
      // 1日だけ押した（ドラッグしなかった）ときは、まだ何も変更していない。
      // 確認ダイアログでその場で確定できるようにする（誤タップ防止と、操作の軽さを両立させる）。
      if (paintTouched.size === 1) confirmSingleDayPaint(Array.from(paintTouched)[0]);
      else renderAll();   // ドラッグして複数日を触れた場合は pointerover 側ですでに反映済み
      return;
    }
    // 選択のみ：1枠だけならその枠を選ぶ。
    // 続けてもう一度押した（ダブルクリック／ダブルタップ）ときだけ登録フォームを開く。
    // ただし、すでに予定がある枠は、誤って内容を触ってしまわないよう内容の確認だけにする
    // （新規に追加したいときは「＋予定を登録」ボタンを押す）。
    if (paintTouched.size === 1) {
      const pick = Array.from(paintTouched)[0];
      const now = Date.now();
      const isDouble = lastTapKey === pick && (now - lastTapAt) < DOUBLE_TAP_MS;
      lastTapKey = pick;
      lastTapAt = now;
      const [d, sid] = pick.split('|');
      const openForm = isDouble && !jobsInSlot(d, sid).length;
      selectDate(d, { slot: sid, openForm, popup: isDouble });
      if (openForm) {
        const el = $('fTitle');
        if (el) el.focus();
      }
    }
    paintTouched.clear();
  };
  window.addEventListener('pointerup', endPaint);
  window.addEventListener('pointercancel', endPaint);

  // スマホでは、指を押した要素にそのままイベントが送られ続ける（他の枠に指が移っても
  // pointerover は飛ばない）ため、スクロールしようとして指が動いただけでも
  // 「1枠だけ押した」ことになり、そのままその枠が選ばれてしまう。
  // 一定以上指が動いたらスクロール操作とみなし、選択操作を中止する。
  window.addEventListener('pointermove', (ev) => {
    if (!painting || !paintIsTouch || ev.pointerId !== paintPointerId) return;
    const dx = ev.clientX - paintStartX;
    const dy = ev.clientY - paintStartY;
    if (Math.hypot(dx, dy) > SCROLL_CANCEL_PX) {
      painting = false;
      paintTouched.clear();
    }
  }, { passive: true });

  // 念のため、画面が実際にスクロールされたときも同様に選択操作を中止する
  window.addEventListener('scroll', () => {
    if (!painting) return;
    painting = false;
    paintTouched.clear();
  }, { passive: true });

  elCalendar.addEventListener('click', (ev) => {
    const dateBtn = ev.target.closest('[data-role="date"]');
    if (!dateBtn || view.paint) return;
    const key = 'date|' + dateBtn.dataset.date;
    const now = Date.now();
    const isDouble = lastTapKey === key && (now - lastTapAt) < DOUBLE_TAP_MS;
    lastTapKey = key;
    lastTapAt = now;
    const openForm = isDouble && !jobsOn(dateBtn.dataset.date).length;
    selectDate(dateBtn.dataset.date, { openForm, popup: isDouble });
  });

  // スマホでポップアップ表示中は、背景（枠外）をタップすると閉じる
  elSideAside.addEventListener('click', (ev) => {
    if (ev.target === elSideAside && elSideAside.classList.contains('sc-side-modal-open')) closeForm();
  });

  // サイドパネル内の操作（委譲）
  elSidePanel.addEventListener('click', (ev) => {
    const unpick = ev.target.closest('[data-unpick]');
    if (unpick) {
      view.multi.delete(unpick.dataset.unpick);
      renderCalendar();
      renderSidePanel();
      return;
    }

    if (ev.target.id === 'multiClear') {
      view.multi.clear();
      renderCalendar();
      renderSidePanel();
      return;
    }

    const multiWish = ev.target.closest('[data-multiwish]');
    if (multiWish) {
      const v = multiWish.dataset.multiwish;
      // view.multi は「日付|枠」なので、日付だけを取り出して重複を除く
      const dates = Array.from(new Set(Array.from(view.multi).map((pk) => pk.split('|')[0])));
      if (!dates.length) return;
      dates.forEach((d) => setWish(d, v === 'none' ? null : v, true));
      saveState();
      renderAll();
      toast(`${dates.length}日を${v === 'available' ? '稼働可' : v === 'off' ? '休み希望' : '未定'}にしました`);
      return;
    }

    const wishBtn = ev.target.closest('[data-wish]');
    if (wishBtn && view.selected) {
      const v = wishBtn.dataset.wish;
      const current = state.wishes[view.selected] || null;
      const next = (v === 'none' || current === v) ? null : v;
      if (next === WISH_OFF && jobsOn(view.selected).length) {
        if (!confirm('この日にはすでに予定が入っています。休み希望にしますか？')) return;
      }
      setWish(view.selected, next);
      renderAll();
      return;
    }

    if (ev.target.id === 'openForm') {
      view.formOpen = true;
      view.form = blankForm();
      renderSidePanel();
      const el = $('fTitle');
      if (el) el.focus();
      return;
    }

    if (ev.target.closest('[data-modal-close]')) { closeForm(); return; }

    const mapBtn = ev.target.closest('[data-map]');
    if (mapBtn) {
      const addrEl = $('fAddress');
      const placeEl = $('fPlace');
      const loc = [placeEl ? placeEl.value.trim() : '', addrEl ? addrEl.value.trim() : ''].filter(Boolean).join(' ');
      if (!loc) { toast('住所を入力してください', true); if (addrEl) addrEl.focus(); return; }
      window.open(mapUrl(mapBtn.dataset.map, loc), '_blank', 'noopener');
      return;
    }

    const confirmBtn = ev.target.closest('[data-confirm]');
    if (confirmBtn) { startConfirm(confirmBtn.dataset.confirm); return; }

    const editBtn = ev.target.closest('[data-edit]');
    if (editBtn) { startEdit(editBtn.dataset.edit); return; }

    const delBtn = ev.target.closest('[data-del]');
    if (delBtn) { deleteJob(delBtn.dataset.del); return; }

    if (ev.target.id === 'fCancel') { closeForm(); }
  });

  elSidePanel.addEventListener('input', (ev) => {
    // 承知チェックは change 側で扱う（ここで警告欄を作り直すと反応が消えるため）
    if (ev.target.id === 'fAck') return;
    if (!ev.target.closest('#jobForm')) return;
    view.form = readForm();
    updateFormAlert();
    if (ev.target.id === 'fAddress' || ev.target.id === 'fPlace') {
      const mapActions = $('mapActions');
      if (mapActions) mapActions.hidden = !($('fAddress').value.trim() || $('fPlace').value.trim());
    }
  });

  elSidePanel.addEventListener('change', (ev) => {
    if (ev.target.id === 'fAck') {
      view.ack = ev.target.checked;
      const btn = $('fSubmit');
      if (btn) btn.disabled = !view.ack;
      return;
    }
    if (ev.target.id === 'fAllDay') {
      view.form = readForm();
      const row = $('timeRow');
      if (row) row.hidden = ev.target.checked;
      updateFormAlert();
      return;
    }
    // 「フリー」を選んだときだけ自由入力欄を出す
    if (ev.target.id === 'fTitle') {
      view.form = readForm();
      const row = $('titleFreeRow');
      if (row) row.hidden = ev.target.value !== PROJECT_FREE;
      if (ev.target.value === PROJECT_FREE) $('fTitleFree').focus();
      updateFormAlert();
    }
  });

  elSidePanel.addEventListener('submit', (ev) => {
    if (ev.target.id === 'jobForm') submitJob(ev);
  });

  // 重複バナー／一覧から日付へジャンプ
  const gotoHandler = (ev) => {
    const el = ev.target.closest('[data-goto]');
    if (!el) return;
    selectDate(el.dataset.goto);
    document.querySelector('.sc-main').scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  elConflictBanner.addEventListener('click', gotoHandler);
  elJobList.addEventListener('click', gotoHandler);

  // 予定一覧の絞り込み
  document.querySelectorAll('.sc-filter-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.sc-filter-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      view.jobFilter = tab.dataset.filter;
      renderJobList();
    });
  });

  // 日付の一覧
  ['listTarget', 'exportFormat', 'excludeBooked', 'projectFilter'].forEach((id) => {
    $(id).addEventListener('change', renderExport);
  });

  $('copyExport').addEventListener('click', async () => {
    const ok = await copyText(elExportText.value);
    toast(ok ? 'コピーしました' : 'コピーできませんでした。手動で選択してください', !ok);
  });

  // Googleカレンダー用ファイル
  $('icsScope').addEventListener('change', renderIcsPreview);
  $('icsProjectFilter').addEventListener('change', renderIcsPreview);
  $('icsPrefix').addEventListener('input', renderIcsPreview);
  $('downloadIcs').addEventListener('click', downloadIcs);

  // 案件ごとのGoogleカレンダー登録内容
  $('pcProject').addEventListener('change', loadProjectCalendarEditor);
  $('pcSave').addEventListener('click', saveProjectCalendar);
  $('pcOverrideAdd').addEventListener('click', addOrUpdateOverride);
  $('pcOverrideList').addEventListener('click', (ev) => {
    const editBtn = ev.target.closest('[data-override-edit]');
    if (editBtn) { editOverride(editBtn.dataset.overrideEdit); return; }
    const delBtn = ev.target.closest('[data-override-del]');
    if (delBtn) removeOverride(delBtn.dataset.overrideDel);
  });

  // 案件名の登録・削除
  $('addProjectBtn').addEventListener('click', () => {
    addProject($('newProjectName').value);
    $('newProjectName').value = '';
    $('newProjectName').focus();
  });
  $('newProjectName').addEventListener('keydown', (ev) => {
    if (ev.key !== 'Enter') return;
    ev.preventDefault();
    addProject($('newProjectName').value);
    $('newProjectName').value = '';
  });
  $('projectChips').addEventListener('click', (ev) => {
    const chip = ev.target.closest('[data-project]');
    if (chip) removeProject(chip.dataset.project);
  });

  $('copyJobs').addEventListener('click', async () => {
    const ok = await copyText(buildJobsText());
    toast(ok ? '予定一覧をコピーしました' : 'コピーできませんでした', !ok);
  });

  // 設定
  $('bufferMin').addEventListener('change', () => {
    state.settings.bufferMin = Number($('bufferMin').value) || 0;
    saveState();
    renderAll();
  });
  $('defStart').addEventListener('change', () => {
    state.settings.defStart = $('defStart').value || '09:00';
    saveState();
  });
  $('defEnd').addEventListener('change', () => {
    state.settings.defEnd = $('defEnd').value || '18:00';
    saveState();
  });

  // 端末間の同期
  // 入力し終えたら、そのまま同期を始める（設定直後に待たされないように）
  let syncSetupTimer = null;
  const syncAfterSetup = () => {
    clearTimeout(syncSetupTimer);
    startSyncPolling();
    if (!sync.auto || !syncConfigured()) return;
    syncSetupTimer = setTimeout(() => syncNow(true), 800);
  };

  $('syncUrl').addEventListener('input', () => {
    sync.url = $('syncUrl').value.trim();
    saveSyncConfig();
    syncSyncInputs();
    syncAfterSetup();
  });
  $('syncKey').addEventListener('input', () => {
    sync.key = $('syncKey').value.trim();
    sync.rev = 0;            // 合言葉を変えたら別の保存先になる
    saveSyncConfig();
    syncSyncInputs();
    syncAfterSetup();
  });
  $('syncAuto').addEventListener('change', () => {
    sync.auto = $('syncAuto').checked;
    saveSyncConfig();
    startSyncPolling();
  });
  $('genKey').addEventListener('click', () => {
    if (sync.key && !confirm('新しい合言葉を作ると、いまの合言葉で保存した内容とはつながらなくなります。よろしいですか？')) return;
    sync.key = generateSyncKey();
    sync.rev = 0;
    saveSyncConfig();
    syncSyncInputs();
    toast('合言葉を作りました。他の端末にも同じものを入力してください');
  });
  $('syncTest').addEventListener('click', syncTest);
  $('syncNowBtn').addEventListener('click', () => syncNow(false));

  // 別の端末で変更されている可能性があるので、画面に戻ってきたら同期する
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && sync.auto && syncConfigured()) syncNow(true);
  });
  window.addEventListener('online', () => {
    if (sync.auto && syncConfigured()) syncNow(true);
  });

  $('exportJson').addEventListener('click', exportJson);
  $('importJson').addEventListener('change', (ev) => {
    const file = ev.target.files && ev.target.files[0];
    if (file) importJson(file);
    ev.target.value = '';
  });
  $('resetAll').addEventListener('click', async () => {
    if (!confirm('この端末に保存されているすべての希望・予定を削除します。元に戻せません。よろしいですか？')) return;
    if (!confirm('本当に削除してよろしいですか？')) return;

    // 同期している場合、黙って空を送ると他の端末の予定まで消えてしまう
    let alsoServer = false;
    if (syncConfigured()) {
      alsoServer = confirm('共有しているサーバのデータも削除しますか？\n\n'
        + 'OK　　　：サーバのデータも削除する（他の端末からも消えます）\n'
        + 'キャンセル：この端末だけ削除する（サーバのデータは残す）');
    }

    state = defaultState();
    view.selected = null;
    view.editingId = null;
    view.confirming = false;
    view.form = blankForm();
    saveState(true);
    syncSettingsInputs();
    refreshWorkTypeOptions();
    refreshProjectFilterOptions();
    renderAll();

    if (alsoServer) {
      try {
        await fetch(syncEndpoint('key=' + encodeURIComponent(sync.key)), { method: 'DELETE' });
        sync.rev = 0;
        saveSyncConfig();
        setSyncStatus('サーバのデータも削除しました', 'warn');
      } catch (err) {
        toast('サーバのデータを削除できませんでした：' + err.message, true);
      }
    } else if (syncConfigured()) {
      setSyncStatus('この端末のみ削除しました（サーバは変更していません）', 'warn');
    }
    toast('この端末のデータを削除しました');
  });

  // メニューバー
  const SECTIONS = ['calendar', 'mail', 'list', 'settings'];
  let menuLock = 0;   // 押した直後は、スクロール中の判定で上書きしない

  const setActiveMenu = (name) => {
    document.querySelectorAll('.sc-menu-item').forEach((item) => {
      item.classList.toggle('active', item.dataset.jump === name);
    });
  };

  document.querySelectorAll('.sc-menu-item').forEach((item) => {
    item.addEventListener('click', () => {
      const target = $('sec-' + item.dataset.jump);
      if (!target) return;
      setActiveMenu(item.dataset.jump);
      menuLock = Date.now() + 1000;
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  });

  // いま見ている場所をメニューに反映する
  const markActiveSection = () => {
    if (Date.now() < menuLock) return;
    const doc = document.documentElement;
    const atBottom = window.innerHeight + window.scrollY >= doc.scrollHeight - 4;
    let current = SECTIONS[0];
    SECTIONS.forEach((name) => {
      const el = $('sec-' + name);
      if (!el) return;
      const top = el.getBoundingClientRect().top;
      // 最下部では、画面に見えている一番下の見出しを現在地とする
      if (atBottom ? top < window.innerHeight : top <= 140) current = name;
    });
    setActiveMenu(current);
  };
  let scrollTick = false;
  window.addEventListener('scroll', () => {
    if (scrollTick) return;
    scrollTick = true;
    requestAnimationFrame(() => { markActiveSection(); scrollTick = false; });
  }, { passive: true });
  markActiveSection();

  // PDF出力（印刷ダイアログの「PDFに保存」を利用）
  // 保存時のファイル名にも対象の月がわかるよう、印刷中だけタイトルを変える
  let savedTitle = document.title;
  window.addEventListener('beforeprint', () => {
    $('printHeader').textContent = `${view.year}年${view.month + 1}月 スケジュール`;
    savedTitle = document.title;
    document.title = `VertiCale_${view.year}年${pad2(view.month + 1)}月`;
  });
  window.addEventListener('afterprint', () => { document.title = savedTitle; });
  $('printPdfBtn').addEventListener('click', () => window.print());

  // ホーム画面・デスクトップへの追加（PWA）
  window.addEventListener('beforeinstallprompt', (ev) => {
    ev.preventDefault();
    installPrompt = ev;
    $('installBtn').hidden = false;
  });
  $('installBtn').addEventListener('click', async () => {
    if (!installPrompt) return;
    installPrompt.prompt();
    const res = await installPrompt.userChoice;
    installPrompt = null;
    $('installBtn').hidden = true;
    if (res && res.outcome === 'accepted') toast('アプリとして追加しました');
  });
  window.addEventListener('appinstalled', () => {
    installPrompt = null;
    $('installBtn').hidden = true;
  });

  // キーボード操作
  document.addEventListener('keydown', (ev) => {
    if (ev.key === 'Escape' && elSideAside.classList.contains('sc-side-modal-open')) { closeForm(); return; }
    if (ev.target.matches('input, textarea, select')) return;
    if (ev.key === 'ArrowLeft') moveMonth(-1);
    if (ev.key === 'ArrowRight') moveMonth(1);
  });
}

/* ------------------------------------------------------------
   起動
   ------------------------------------------------------------ */

function init() {
  elCalendar = $('calendar');
  elMonthLabel = $('monthLabel');
  elMonthPicker = $('monthPicker');
  elSidePanel = $('sidePanel');
  elSideAside = document.querySelector('.sc-side');
  elStats = $('stats');
  elConflictBanner = $('conflictBanner');
  elExportText = $('exportText');
  elAvailCount = $('availCount');
  elJobList = $('jobList');
  elJobCount = $('jobCount');
  elToast = $('toast');

  loadState();
  loadSyncConfig();
  syncSettingsInputs();
  syncSyncInputs();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  view.form = blankForm();
  bindEvents();
  renderAll();

  // 起動時に、他の端末で更新されていないか確認する
  if (sync.auto && syncConfigured()) syncNow(true);
  startSyncPolling();

  // アプリとして追加できるようにする（file:// で開いた場合は動かないので黙って見送る）
  if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}

document.addEventListener('DOMContentLoaded', init);

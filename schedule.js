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
const CLIENT_FREE = '__client_free__';

/** 選んだ案件に登録されている業態（依頼元ごとの上書き）の選択肢一覧 */
function businessTypesForProject(project) {
  const cfg = project ? state.settings.projectCalendar[project] : null;
  return cfg && cfg.clientOverrides ? Object.keys(cfg.clientOverrides) : [];
}

/** 案件名（#fTitle）を変えたときに、業態（#fClient）の選択肢を選び直したその案件のものに更新する */
function refreshClientOptions() {
  const sel = $('fClient');
  if (!sel) return;
  const f = view.form || blankForm();
  const options = businessTypesForProject(formTitle(f));
  const prevValue = sel.value;
  sel.innerHTML = '<option value="">選択してください</option>'
    + options.map((c) => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join('')
    + `<option value="${CLIENT_FREE}">自由入力</option>`;
  sel.value = (options.includes(prevValue) || prevValue === CLIENT_FREE) ? prevValue : '';
}

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
 * 予定バーが主の枠から何枠分ぶん続けて表示されるか。
 * 日をまたぐ夜勤は日付をまたいだ側の枠（早朝など）が離れて含まれることがあるため、
 * 主の枠から連続している範囲だけをバーの長さとする（離れた分は枠の色だけで示す）。
 */
function jobBarSpan(job, primaryId) {
  const ids = new Set(jobSlots(job));
  const startIdx = SLOTS.findIndex((s2) => s2.id === primaryId);
  if (startIdx < 0) return 1;
  let span = 0;
  for (let k = startIdx; k < SLOTS.length && ids.has(SLOTS[k].id); k++) span++;
  return Math.max(1, span);
}

/**
 * バーの直後の枠に、1枠分にするほどではない短い掛かり（30分以内）があれば、
 * その分だけバーの長さに少しだけ足す（早朝・夜間の端で特に効く）。
 * 見た目上のバーの長さが実際の時間により近づくようにするための微調整で、返り値は0〜1の割合。
 */
function jobBarTailSpill(job, primaryId, span) {
  if (job.allDay) return 0;
  const startIdx = SLOTS.findIndex((s2) => s2.id === primaryId);
  const nextIdx = startIdx + span;
  if (startIdx < 0 || nextIdx >= SLOTS.length) return 0;
  const nextSlot = SLOTS[nextIdx];
  const ov = slotOverlapMinutes(job, nextSlot);
  if (ov <= 0 || ov > SLOT_EDGE_MINUTES) return 0;
  const dur = toMinutes(nextSlot.end) - toMinutes(nextSlot.start);
  return dur > 0 ? Math.min(1, ov / dur) : 0;
}

/**
 * 主の枠の標準の開始（見出しに出ている時刻）より実際の開始が早いとき、
 * その分だけ手前の枠に少しだけバーを伸ばす（AMの8:00開始・夜間の17:30開始など）。
 * 実際にその枠に掛かっているわけではないが、標準より早いことが一目で分かるようにするための表現で、
 * 返り値は0〜1の割合。
 */
function jobBarLeadBleed(job, primaryId) {
  if (job.allDay) return 0;
  const idx = SLOTS.findIndex((s2) => s2.id === primaryId);
  if (idx <= 0) return 0;   // 一番左の枠には手前の枠が無い
  const slot = SLOTS[idx];
  const s = toMinutes(job.start);
  const std = toMinutes(slot.start);
  if (s === null || std === null || s >= std) return 0;
  const early = std - s;
  const prevSlot = SLOTS[idx - 1];
  const dur = toMinutes(prevSlot.end) - toMinutes(prevSlot.start);
  return dur > 0 ? Math.min(1, early / dur) : 0;
}

/** その日・その枠に掛かっている予定 */
function jobsInSlot(dateKey, slotId) {
  return jobsOn(dateKey).filter((j) => jobSlots(j).indexOf(slotId) >= 0);
}

/**
 * 予定バーのgrid配置スタイル。掛かる枠の幅に加えて、
 * 前後の枠への少しのにじみ（早い開始・短い掛かり）があれば、その分だけ広げる。
 */
function jobBarPlacementStyle(job, primaryId, col) {
  const span = jobBarSpan(job, primaryId);
  const lead = jobBarLeadBleed(job, primaryId);
  const tail = jobBarTailSpill(job, primaryId, span);
  if (lead <= 0 && tail <= 0) return `grid-column:${col} / span ${span};`;

  const totalCols = span + (lead > 0 ? 1 : 0) + (tail > 0 ? 1 : 0);
  const startCol = col - (lead > 0 ? 1 : 0);
  const marginLeftPct = lead > 0 ? ((1 - lead) / totalCols) * 100 : 0;
  const marginRightPct = tail > 0 ? ((1 - tail) / totalCols) * 100 : 0;
  return `grid-column:${startCol} / span ${totalCols};`
    + ` margin-left:${marginLeftPct.toFixed(2)}%; margin-right:${marginRightPct.toFixed(2)}%;`;
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
      projectColors: {},                   // 案件ごとのカレンダーのバーの色（未設定なら既定の色）
      notify: { enabled: false, leadMinutes: 0 },   // 予定の時間の通知（この画面を開いている間だけ）
    },
    settingsAt: EPOCH0,
    wishes: {},      // 'YYYY-MM-DD' → 'available' | 'off'
    wishMeta: {},    // 'YYYY-MM-DD' → 更新時刻（未定に戻した日も記録する）
    jobs: [],        // { id, date, allDay, start, end, title, workType, ..., updatedAt }
    tombstones: [],  // [{ id, updatedAt }] 削除した予定の記録
    todos: [],       // { id, title, note, category: 'work'|'personal', dueDate, dueTime, done, createdAt, updatedAt }
    todoTombstones: [],  // [{ id, updatedAt }] 削除したToDoの記録
  };
}

let state = defaultState();

const now = new Date();
const view = {
  year: now.getFullYear(),
  month: now.getMonth(),   // 0-11
  selected: null,          // 'YYYY-MM-DD'
  slot: null,              // 選択中の時間帯ID（日付ラベルを選んだときは null）
  formOpen: false,         // 登録フォームを開いているか（空いている枠をタップで開く）
  popup: false,            // スマホで、内容を下からのシートで見せるか
  paint: null,             // null | 'available' | 'off' | 'clear' | 'multi'
  multi: new Set(),        // まとめて登録・変更する枠（'YYYY-MM-DD|slotId'）
  multiTab: null,          // まとめて操作の表示（'add'=新規登録 / 'edit'=選んだ予定を変更 / null=自動）
  multiAuto: false,        // 長押し・Shiftクリックで一時的に選択モードに入ったか（操作後に自動で戻す）
  bulkForm: null,          // まとめて変更の入力途中の値
  bulkExclude: new Set(),  // まとめて変更の対象から外した予定ID
  sheetOpenedAt: 0,        // シートを開いた時刻（続けてのタップで、すぐ閉じてしまわないように）
  editingId: null,
  confirming: false,       // 仮出勤 → 確定 への切り替え中
  jobFilter: 'all',        // 予定一覧の絞り込み
  ack: false,              // 「重複を承知で登録する」
  form: null,              // 入力途中の値を保持
  autoNoteText: null,      // 業態の上書きテンプレートを自動で入れたメモの内容（手で書き換えたら追従をやめる）
  todoFilter: 'open',      // ToDoの絞り込み（open=未完了のみ / all=すべて）
  todoFormOpen: false,     // ToDoの追加・編集フォームを開いているか
  todoEditingId: null,     // 編集中のToDoのID（新規追加のときはnull）
};

let painting = false;
let paintAdd = true;      // 複数日モードでドラッグ中に追加するか解除するか
let longPressTimer = null;    // 長押しで複数選択に入るためのタイマー
let longPressActive = false;  // 長押しが成立して、指でなぞって選べる状態か
let swallowTouchEnd = false;  // 指を離した瞬間にシート等を開いたとき、続けて起きるクリックを無効にする
let confirmOpenedAt = 0;      // 確認ダイアログを開いた時刻
let paintPointerId = null;   // 選択操作中のポインターID（スクロールとの誤操作防止用）
let paintIsTouch = false;
let paintStartX = 0;
let paintStartY = 0;
const SCROLL_CANCEL_PX = 10;   // これ以上指が動いたらスクロールとみなして選択を中止する
const LONG_PRESS_MS = 450;     // これだけ押し続けたら長押し（複数選択の開始）とみなす
const SHEET_GUARD_MS = 500;    // シートを開いた直後は、背景のタップで閉じない
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

  const todos = (Array.isArray(parsed.todos) ? parsed.todos : []).filter(isValidTodo).map((t) => {
    const todo = Object.assign({}, t);
    if (!todo.updatedAt) todo.updatedAt = todo.createdAt || EPOCH0;
    if (typeof todo.done !== 'boolean') todo.done = false;
    if (todo.category !== 'personal') todo.category = 'work';
    if (typeof todo.note !== 'string') todo.note = '';
    if (typeof todo.dueDate !== 'string') todo.dueDate = '';
    if (typeof todo.dueTime !== 'string' || !todo.dueDate) todo.dueTime = '';
    return todo;
  });

  const todoTombstones = (Array.isArray(parsed.todoTombstones) ? parsed.todoTombstones : [])
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
    todos: todos,
    todoTombstones: todoTombstones,
  };
}

/**
 * 案件名の一覧を突き合わせる。片方の端末にしか無い案件名も残す
 * （新しい機能を知らない古い端末が同期しても、他の端末で登録した案件名を消さないため）。
 */
function mergeProjectNames(olderNames, newerNames) {
  const seen = new Set();
  const out = [];
  (newerNames || []).concat(olderNames || []).forEach((p) => {
    if (!seen.has(p)) { seen.add(p); out.push(p); }
  });
  return out;
}

/**
 * 案件ごとのGoogleカレンダー設定を突き合わせる。タイトル・住所・説明文は新しいほうを採用しつつ、
 * 業態ごとの上書きは案件・業態ごとに両方残す（片方の端末にしか無い上書きを消さないため）。
 */
function mergeProjectCalendar(olderPc, newerPc) {
  const older = olderPc || {};
  const newer = newerPc || {};
  const out = {};
  Object.keys(older).concat(Object.keys(newer)).forEach((name) => {
    if (out[name]) return;
    const a = older[name];
    const b = newer[name];
    out[name] = (a && b)
      ? Object.assign({}, a, b, { clientOverrides: Object.assign({}, a.clientOverrides || {}, b.clientOverrides || {}) })
      : (b || a);
  });
  return out;
}

/** 案件ごとのバーの色を突き合わせる（片方の端末にしか無い分も残す） */
function mergeProjectColors(olderColors, newerColors) {
  return Object.assign({}, olderColors || {}, newerColors || {});
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

  /* ---- ToDo（予定と同じく、同じIDは更新時刻が新しいほうを採用） ---- */
  const todoTombs = new Map();
  left.todoTombstones.concat(right.todoTombstones).forEach((t) => {
    const cur = todoTombs.get(t.id);
    if (!cur || t.updatedAt > cur.updatedAt) todoTombs.set(t.id, t);
  });

  const todos = new Map();
  left.todos.concat(right.todos).forEach((t) => {
    const cur = todos.get(t.id);
    if (!cur || t.updatedAt > cur.updatedAt) todos.set(t.id, t);
  });

  todoTombs.forEach((t, id) => {
    const todo = todos.get(id);
    if (todo && todo.updatedAt > t.updatedAt) todoTombs.delete(id);
    else todos.delete(id);
  });

  out.todos = Array.from(todos.values());
  out.todoTombstones = Array.from(todoTombs.values());

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

  /* ---- 設定（基本は新しいほうを採用。案件名・案件ごとの設定・バーの色は
     片方の端末にしか無い分も消えないよう、それぞれ突き合わせて残す） ---- */
  const newer = left.settingsAt >= right.settingsAt ? left : right;
  const older = newer === left ? right : left;
  out.settings = Object.assign({}, newer.settings, {
    projects: mergeProjectNames(older.settings.projects, newer.settings.projects),
    projectCalendar: mergeProjectCalendar(older.settings.projectCalendar, newer.settings.projectCalendar),
    projectColors: mergeProjectColors(older.settings.projectColors, newer.settings.projectColors),
  });
  out.settingsAt = newer.settingsAt;

  return out;
}

function isValidJob(j) {
  return j && typeof j === 'object' && typeof j.id === 'string'
    && typeof j.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(j.date);
}

/** 予定の見出し。タイトルが入っていればそれを、なければ案件名を使う（メインの表示用） */
function jobHeading(j) {
  return (j.subject || '').trim() || j.title || '(無題)';
}

/** タイトルがあるときだけ、案件名を補足として添える（タイトルが無いときは見出しが案件名なので重ねない） */
function jobSubLabel(j) {
  return (j.subject || '').trim() ? (j.title || '') : '';
}

function isValidTodo(t) {
  return t && typeof t === 'object' && typeof t.id === 'string' && typeof t.title === 'string';
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
    elConflictBanner, elExportText, elAvailCount, elJobList, elJobCount, elToast,
    elConfirmOverlay, elConfirmMsg, elConfirmOk, elConfirmCancel;

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
  renderTodos();
}

function renderMonthNav() {
  elMonthLabel.textContent = `${view.year}年${view.month + 1}月`;
  elMonthPicker.value = `${view.year}-${pad2(view.month + 1)}`;
}

/** 表示中の月の予定を日付ごとにまとめる（日ごとに全件を探し直さないため） */
function monthJobsByDate() {
  const prefix = `${view.year}-${pad2(view.month + 1)}-`;
  const map = {};
  state.jobs.forEach((j) => {
    if (typeof j.date !== 'string' || j.date.indexOf(prefix) !== 0) return;
    (map[j.date] = map[j.date] || []).push(j);
  });
  Object.keys(map).forEach((k) => {
    map[k].sort((a, b) => {
      if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
      return jobRange(a).s - jobRange(b).s;
    });
  });
  return map;
}

function renderCalendar() {
  const conflicts = conflictingJobIds();
  const today = todayKey();
  const total = daysInMonth(view.year, view.month);
  const byDate = monthJobsByDate();

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
    const dayJobs = byDate[key] || [];
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
      + (key === today ? '<span class="sc-vdate-today-badge">今日</span>' : '')
      + `<span class="sc-vdate-marks">${marks.join('')}</span>`
      + (hol ? `<span class="sc-vdate-hol">${escapeHtml(hol)}</span>` : '')
      + `</button>`;

    const slotJobs = {};
    SLOTS.forEach((s2) => { slotJobs[s2.id] = []; });
    dayJobs.forEach((j) => { jobSlots(j).forEach((sid) => { if (slotJobs[sid]) slotJobs[sid].push(j); }); });

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

      const label = `${view.month + 1}月${d}日 ${slot.name}`
        + (list.length ? ' ' + list.map((j) => jobHeading(j)).join('、') : ' 空き');

      return `<button type="button" class="${cls.join(' ')}" data-date="${key}" data-slot="${slot.id}"`
        + ` data-pick="${pick}" aria-label="${escapeHtml(label)}">`
        + (list.length ? '' : '<span class="sc-vcell-empty"></span>')
        + (view.multi.has(pick) ? '<span class="sc-vcell-check">✓</span>' : '')
        + `</button>`;
    }).join('');

    // 予定バー：案件が掛かる枠をひとつながりの棒で表す（枠ごとに案件名を繰り返さない）
    const barGroups = {};
    dayJobs.forEach((j) => {
      const pid = j.allDay ? SLOTS[0].id : primarySlotId(j);
      if (!pid) return;
      (barGroups[pid] = barGroups[pid] || []).push(j);
    });
    const bars = Object.keys(barGroups).map((pid) => {
      const idx = SLOTS.findIndex((s2) => s2.id === pid);
      if (idx < 0) return '';
      const col = idx + 2;   // 1列目は日付列
      const jobsHere = barGroups[pid];
      if (jobsHere.length === 1) {
        const style = jobBarPlacementStyle(jobsHere[0], pid, col);
        return jobBarHtml(jobsHere[0], key, pid, conflicts, style);
      }
      // 重複を承知で同じ枠に重ねた予定は、その枠の中にまとめて縦に並べる
      const inner = jobsHere.map((j) => jobBarHtml(j, key, pid, conflicts, '')).join('');
      return `<div class="sc-vbar-stack" data-date="${key}" data-slot="${pid}"`
        + ` style="grid-column:${col} / span 1;">${inner}</div>`;
    }).join('');

    const rcls = ['sc-vrow'];
    if (dow === 0) rcls.push('sc-vrow-sun');
    if (dow === 6) rcls.push('sc-vrow-sat');
    // 休み希望の日は、枠まで含めて行ごと赤くする
    if (wish === WISH_OFF) rcls.push('sc-vrow-off');
    if (key === today) rcls.push('sc-vrow-today');
    rows.push(`<div class="${rcls.join(' ')}">${dateCell}${cells}${bars}</div>`);
  }

  elCalendar.innerHTML = head + rows.join('');
}

/** '#rrggbb' → {r,g,b}（不正な値はnull） */
function hexToRgb(hex) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex || '');
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

/** WCAGの相対輝度（0〜1。大きいほど明るい色） */
function relativeLuminance(rgb) {
  const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(rgb.r) + 0.7152 * f(rgb.g) + 0.0722 * f(rgb.b);
}

/** 白との合成（ratio=0で白、1で元の色。仮予定の背景を薄く作るのに使う） */
function tintWithWhite(rgb, ratio) {
  const mix = (c) => Math.round(255 * (1 - ratio) + c * ratio);
  return `rgb(${mix(rgb.r)}, ${mix(rgb.g)}, ${mix(rgb.b)})`;
}

/** 予定バー（縦型カレンダー）のHTML。案件名の下に実際の時間帯を小さく添える */
function jobBarHtml(j, dateKey, slotId, conflicts, style) {
  const cls = ['sc-vbar'];
  cls.push(j.status === 'tentative' ? 'sc-vbar-tentative' : 'sc-vbar-confirmed');
  if (conflicts.has(j.id)) cls.push('sc-vbar-conflict');

  let colorStyle = '';
  const customHex = state.settings.projectColors[j.title];
  const rgb = customHex && hexToRgb(customHex);
  if (rgb) {
    if (j.status === 'tentative') {
      const textColor = relativeLuminance(rgb) > 0.6 ? '#3a2a06' : customHex;
      colorStyle = `background:${tintWithWhite(rgb, 0.22)};border-color:${customHex};color:${textColor};`;
    } else {
      const textColor = relativeLuminance(rgb) > 0.55 ? '#1f2937' : '#fff';
      colorStyle = `background:${customHex};color:${textColor};`;
    }
  }

  const tag = j.status === 'tentative' ? '<span class="sc-vbar-mark">仮</span>' : '';
  const timeLabel = j.allDay ? '終日' : `${shortTime(j.start)}〜${shortTime(j.end)}`;
  const tip = [formatDate(j.date), j.allDay ? '終日' : j.start + '〜' + j.end,
    j.subject, j.title, j.workType, j.client, j.storeName, j.place, j.address].filter(Boolean).join(' / ');
  return `<div class="${cls.join(' ')}" data-date="${dateKey}" data-slot="${slotId}"`
    + ` style="${style}${colorStyle}" title="${escapeHtml(tip)}">`
    + `<span class="sc-vbar-title">${tag}${escapeHtml(jobHeading(j))}</span>`
    + (jobSubLabel(j) ? `<span class="sc-vbar-proj">${escapeHtml(jobSubLabel(j))}</span>` : '')
    + `<span class="sc-vbar-time">${escapeHtml(timeLabel)}</span>`
    + `</div>`;
}

/** 訪問先1件分の入力欄（場所・住所・小さな地図ボタン）。primaryはID固定、追加分はrowKeyで連番にする */
function visitRowHtml(rowKey, address, removable) {
  const addrId = rowKey === 'primary' ? 'fAddress' : `fExtraAddress-${rowKey}`;
  const hasAddr = (address || '').trim();
  return `<div class="sc-visit-row">
    <label class="sc-field sc-field-grow">
      <span class="sc-field-label">住所</span>
      <input id="${addrId}" class="sc-input" type="text" value="${escapeHtml(address)}"
        placeholder="例）東京都渋谷区〇〇1-2-3" autocomplete="off">
    </label>
    <div class="sc-visit-actions">
      <button type="button" class="sc-icon-btn" data-map="google" data-map-row="${rowKey}" title="Googleマップで開く" ${hasAddr ? '' : 'hidden'}>🗺️</button>
      <button type="button" class="sc-icon-btn" data-map="yahoo" data-map-row="${rowKey}" title="Yahoo!地図で開く" ${hasAddr ? '' : 'hidden'}>📍</button>
      ${removable ? `<button type="button" class="sc-icon-btn sc-icon-btn-danger" data-remove-visit="${rowKey}" title="この訪問先を削除">✕</button>` : ''}
    </div>
  </div>`;
}

/** 予定の入力フォーム（単日・複数日で共用） */
function jobFormHtml(f) {
  return `
    <div class="sc-side-block">
      <p class="sc-side-block-title">${view.paint === 'multi' ? `選んだ ${view.multi.size} 枠にまとめて追加`
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
          <span class="sc-field-label">タイトル（スケジュールにメインで表示）</span>
          <input id="fSubject" class="sc-input" type="text" value="${escapeHtml(f.subject)}"
            placeholder="例）〇〇店 レジ入替" autocomplete="off" enterkeyhint="next">
        </label>

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

        ${view.paint === 'multi' ? `<label class="sc-check" id="useSlotTimeRow" ${f.allDay ? 'hidden' : ''}>
          <input id="fUseSlotTime" type="checkbox" ${f.useSlotTime !== false ? 'checked' : ''}>
          <span>選んだ枠の時間帯で登録する（AMなら9:00〜12:00。同じ日の続いた枠は1件にまとめます）</span>
        </label>` : ''}
        <div class="sc-form-row" id="timeRow" ${f.allDay || (view.paint === 'multi' && f.useSlotTime !== false) ? 'hidden' : ''}>
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
            <span class="sc-field-label">業態</span>
            <select id="fClient" class="sc-input">
              <option value="" ${f.clientSel === '' ? 'selected' : ''}>選択してください</option>
              ${businessTypesForProject(formTitle(f)).map((c) =>
                `<option value="${escapeHtml(c)}" ${f.clientSel === c ? 'selected' : ''}>${escapeHtml(c)}</option>`).join('')}
              <option value="${CLIENT_FREE}" ${f.clientSel === CLIENT_FREE ? 'selected' : ''}>自由入力</option>
            </select>
          </label>
          <label class="sc-field">
            <span class="sc-field-label">店舗名</span>
            <input id="fStoreName" class="sc-input" type="text" value="${escapeHtml(f.storeName)}" autocomplete="off">
          </label>
        </div>

        <label class="sc-field" id="clientFreeRow" ${f.clientSel === CLIENT_FREE ? '' : 'hidden'}>
          <span class="sc-field-label">業態を入力</span>
          <input id="fClientFree" class="sc-input" type="text" value="${escapeHtml(f.clientFree)}"
            placeholder="例）はま寿司" autocomplete="off">
        </label>

        <div class="sc-field-label" style="margin-top:4px">訪問先</div>
        <div class="sc-visit-list">
          ${visitRowHtml('primary', f.address, false)}
          ${(f.extraAddresses || []).map((a, i) => visitRowHtml(String(i), a, true)).join('')}
        </div>
        <div class="sc-export-actions" style="margin-top:2px">
          <button type="button" id="addVisitBtn" class="sc-btn sc-btn-sm sc-btn-outline">＋ 訪問先を追加</button>
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

/** 選んだ枠に入っている予定（同じ予定が複数の枠に掛かっていても1件として数える） */
function multiSelectedJobs() {
  const seen = new Set();
  const out = [];
  Array.from(view.multi).sort().forEach((pk) => {
    const [d, sid] = pk.split('|');
    jobsInSlot(d, sid).forEach((j) => {
      if (seen.has(j.id)) return;
      seen.add(j.id);
      out.push(j);
    });
  });
  return out.sort((x, y) => jobRange(x).s - jobRange(y).s);
}

/** まとめて変更の対象（チェックを外した予定を除く） */
function bulkTargets() {
  return multiSelectedJobs().filter((j) => !view.bulkExclude.has(j.id));
}

/** いま表示するまとめて操作のタブ（選んでいない場合は、選んだ枠がすべて予定ありなら変更、それ以外は新規登録） */
function effectiveMultiTab() {
  const jobs = multiSelectedJobs();
  if (!jobs.length) return 'add';
  if (view.multiTab) return view.multiTab;
  const allBusy = Array.from(view.multi).every((pk) => { const [d, sid] = pk.split('|'); return jobsInSlot(d, sid).length; });
  return allBusy ? 'edit' : 'add';
}

function blankBulkForm() {
  return { subject: '', title: '', workType: '', status: '', timeMode: '', start: state.settings.defStart, end: state.settings.defEnd, client: '', storeName: '' };
}

/** まとめて変更のフォーム（空欄・「変更しない」の項目はそのまま残す） */
function bulkFormHtml(targets) {
  const b = view.bulkForm || blankBulkForm();
  const tentativeCount = targets.filter((j) => j.status === 'tentative').length;
  const list = multiSelectedJobs().map((j) => {
    const time = j.allDay ? '終日' : `${shortTime(j.start)}〜${shortTime(j.end)}`;
    const checked = !view.bulkExclude.has(j.id);
    return `<label class="sc-bulk-item ${j.status === 'tentative' ? 'sc-bulk-item-tentative' : ''}">
      <input type="checkbox" data-bulk-pick="${j.id}" ${checked ? 'checked' : ''}>
      <span class="sc-bulk-item-date">${formatDate(j.date)}</span>
      <span class="sc-bulk-item-time">${escapeHtml(time)}</span>
      <span class="sc-bulk-item-title">${j.status === 'tentative' ? '<span class="sc-vbar-mark">仮</span>' : ''}${escapeHtml(jobHeading(j))}${(jobSubLabel(j) || j.workType) ? `<small>${escapeHtml([jobSubLabel(j), j.workType].filter(Boolean).join(' / '))}</small>` : ''}</span>
    </label>`;
  }).join('');

  return `
    <div class="sc-side-block">
      <p class="sc-side-block-title">対象の予定（${targets.length}件）</p>
      <div class="sc-bulk-list">${list}</div>
      <div class="sc-bulk-quick">
        ${tentativeCount ? `<button type="button" id="bulkConfirmBtn" class="sc-btn sc-btn-sm sc-btn-confirm">✓ 仮出勤 ${tentativeCount}件 をまとめて確定</button>` : ''}
        <button type="button" id="bulkDeleteBtn" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" ${targets.length ? '' : 'disabled'}>🗑 ${targets.length}件 をまとめて削除</button>
      </div>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">内容をまとめて変更</p>
      <p class="sc-hint">変えたい項目だけ入力してください。空欄・「変更しない」の項目は、それぞれの予定の内容がそのまま残ります。</p>
      <form id="bulkForm" class="sc-form">
        <label class="sc-field">
          <span class="sc-field-label">タイトル</span>
          <input id="bSubject" class="sc-input" type="text" value="${escapeHtml(b.subject)}" placeholder="変更しない" autocomplete="off">
        </label>
        <label class="sc-field">
          <span class="sc-field-label">案件名</span>
          <select id="bTitle" class="sc-input">
            <option value="">変更しない</option>
            ${state.settings.projects.map((p) => `<option value="${escapeHtml(p)}" ${b.title === p ? 'selected' : ''}>${escapeHtml(p)}</option>`).join('')}
          </select>
        </label>
        <label class="sc-field">
          <span class="sc-field-label">業務内容</span>
          <input id="bWorkType" class="sc-input" type="text" list="workTypeList" value="${escapeHtml(b.workType)}" placeholder="変更しない" autocomplete="off">
        </label>
        <div class="sc-form-row">
          <label class="sc-field">
            <span class="sc-field-label">区分</span>
            <select id="bStatus" class="sc-input">
              <option value="" ${b.status === '' ? 'selected' : ''}>変更しない</option>
              <option value="confirmed" ${b.status === 'confirmed' ? 'selected' : ''}>確定</option>
              <option value="tentative" ${b.status === 'tentative' ? 'selected' : ''}>仮出勤（未確定）</option>
            </select>
          </label>
          <label class="sc-field">
            <span class="sc-field-label">時間</span>
            <select id="bTimeMode" class="sc-input">
              <option value="" ${b.timeMode === '' ? 'selected' : ''}>変更しない</option>
              <option value="time" ${b.timeMode === 'time' ? 'selected' : ''}>時間を指定</option>
              <option value="allday" ${b.timeMode === 'allday' ? 'selected' : ''}>終日</option>
            </select>
          </label>
        </div>
        <div class="sc-form-row" id="bTimeRow" ${b.timeMode === 'time' ? '' : 'hidden'}>
          <label class="sc-field">
            <span class="sc-field-label">開始</span>
            <input id="bStart" class="sc-input" type="time" value="${escapeHtml(b.start)}">
          </label>
          <label class="sc-field">
            <span class="sc-field-label">終了</span>
            <input id="bEnd" class="sc-input" type="time" value="${escapeHtml(b.end)}">
          </label>
        </div>
        <div class="sc-form-row">
          <label class="sc-field">
            <span class="sc-field-label">業態</span>
            <input id="bClient" class="sc-input" type="text" value="${escapeHtml(b.client)}" placeholder="変更しない" autocomplete="off">
          </label>
          <label class="sc-field">
            <span class="sc-field-label">店舗名</span>
            <input id="bStoreName" class="sc-input" type="text" value="${escapeHtml(b.storeName)}" placeholder="変更しない" autocomplete="off">
          </label>
        </div>
        <div class="sc-form-actions">
          <button type="submit" id="bSubmit" class="sc-btn" ${targets.length ? '' : 'disabled'}>${targets.length}件 に反映する</button>
        </div>
      </form>
    </div>`;
}

/** 複数の枠をまとめて扱うときのパネル */
function renderMultiPanel() {
  const picks = Array.from(view.multi).sort();
  const f = view.form || blankForm();
  const closeBtn = '<button type="button" class="sc-modal-close" data-modal-close aria-label="閉じる">✕</button>';

  if (!picks.length) {
    elSidePanel.innerHTML = closeBtn + `
      <p class="sc-side-date">まとめて登録・変更</p>
      <p class="sc-side-date-sub">カレンダーの枠をタップして選んでください（パソコンはドラッグ、スマホは長押ししたまま指でなぞると連続で選べます）。日付をタップすると、その日の枠をまとめて選べます。</p>
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
  const selectedJobs = multiSelectedJobs();
  const busy = picks.filter((pk) => { const [d, sid] = pk.split('|'); return jobsInSlot(d, sid).length; });
  const offDays = dates.filter((d) => (state.wishes[d] || null) === WISH_OFF);
  const tab = effectiveMultiTab();

  const tabs = selectedJobs.length ? `
    <div class="sc-multi-tabs" role="tablist">
      <button type="button" class="sc-multi-tab ${tab === 'add' ? 'active' : ''}" data-multitab="add" role="tab">＋ 新しく登録</button>
      <button type="button" class="sc-multi-tab ${tab === 'edit' ? 'active' : ''}" data-multitab="edit" role="tab">✎ 予定を変更（${selectedJobs.length}件）</button>
    </div>` : '';

  const addBody = `
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

    ${jobFormHtml(f)}`;

  elSidePanel.innerHTML = closeBtn + `
    <div>
      <p class="sc-side-date">${picks.length}枠を選択中</p>
      <p class="sc-side-date-sub">${dates.length}日ぶん${selectedJobs.length ? `・入っている予定 ${selectedJobs.length}件` : ''}</p>
    </div>

    <div class="sc-side-block">
      <div class="sc-chip-row">${chips}</div>
      <button type="button" id="multiClear" class="sc-btn sc-btn-sm sc-btn-outline" style="margin-top:6px">選択をすべて解除</button>
    </div>

    ${tabs}
    ${tab === 'edit' ? bulkFormHtml(bulkTargets()) : addBody}
  `;

  if (tab !== 'edit') updateFormAlert();
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
    const meta = [j.client, j.storeName, j.place].filter(Boolean).map(escapeHtml).join(' / ');
    const gcalUrl = googleCalendarUrl(j);
    const others = findConflicts(j, null);
    const warn = others.length
      ? `<p class="sc-job-warn">⚠ ${others.map((c) => (c.type === 'overlap' ? '時間が重複' : '移動時間が不足') + '：' + formatDate(c.job.date) + ' ' + escapeHtml(jobHeading(c.job))).join(' / ')}</p>`
      : '';
    const visitLines = jobVisits(j).map((addr) => {
      const icons = `<a class="sc-map-icon" href="${escapeHtml(mapUrl('google', addr))}" target="_blank" rel="noopener" title="Googleマップで開く">🗺️</a>` +
        `<a class="sc-map-icon" href="${escapeHtml(mapUrl('yahoo', addr))}" target="_blank" rel="noopener" title="Yahoo!地図で開く">📍</a>`;
      return `<div class="sc-job-visit"><p class="sc-job-meta">📍 ${escapeHtml(addr)}</p>${icons}</div>`;
    }).join('');
    return `<div class="${cls.join(' ')}">
      <div class="sc-job-card-top">
        <span class="sc-job-status">${tentative ? '仮出勤' : '確定'}</span>
        <span class="sc-job-time">${escapeHtml(time)}</span>
        <span class="sc-job-title">${escapeHtml(jobHeading(j))}</span>
      </div>
      ${jobSubLabel(j) ? `<p class="sc-job-meta sc-job-proj">案件：${escapeHtml(jobSubLabel(j))}</p>` : ''}
      ${j.workType ? `<p class="sc-job-meta"><span class="sc-worktype-tag">${escapeHtml(j.workType)}</span></p>` : ''}
      ${meta ? `<p class="sc-job-meta">${meta}</p>` : ''}
      ${visitLines}
      ${j.note ? `<p class="sc-job-meta sc-job-note">📝 ${escapeHtml(j.note)}</p>` : ''}
      ${warn}
      <div class="sc-job-actions">
        ${tentative ? `<button type="button" class="sc-btn sc-btn-sm sc-btn-confirm" data-confirm="${j.id}">✓ 確定にする</button>` : ''}
        ${gcalUrl ? `<a class="sc-btn sc-btn-sm sc-btn-outline" href="${escapeHtml(gcalUrl)}" target="_blank" rel="noopener">📆 追加</a>` : ''}
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-edit="${j.id}">編集</button>
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-dup="${j.id}">複製</button>
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" data-del="${j.id}">削除</button>
      </div>
    </div>`;
  }).join('');

  const f = view.form || blankForm();

  const header = `
    <button type="button" class="sc-modal-close" data-modal-close aria-label="閉じる">✕</button>
    <div>
      <p class="sc-side-date">${formatDate(key, 'long')}${slot ? `　<span class="sc-slot-badge">${escapeHtml(slot.name)}</span>` : ''}</p>
      <p class="sc-side-date-sub">${slot ? `${slot.start}〜${slot.end}　` : ''}${hol ? '🎌 ' + escapeHtml(hol) : ''}${key < todayKey() ? ' （過去の日付）' : ''}</p>
    </div>`;

  const wishBlock = `
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
    </div>`;

  const jobsBlock = `
    <div class="sc-side-block">
      <p class="sc-side-block-title">${slot ? `この枠の予定` : 'この日の予定'}（${dayJobs.length}件）</p>
      ${cards || '<p class="sc-empty-note">まだ予定はありません。</p>'}
    </div>`;

  if (view.formOpen) {
    // 入力中は、フォームをいちばん上に出す（スマホのシートでもすぐ入力できるように）。
    // 編集・確定中は対象の予定がフォームに出ているので、カードの一覧は省く
    elSidePanel.innerHTML = header + jobFormHtml(f)
      + (!view.editingId && dayJobs.length ? jobsBlock : '')
      + (!view.editingId ? wishBlock : '');
  } else {
    const addBlock = `
    <div class="sc-side-block">
      <button type="button" id="openForm" class="sc-btn sc-btn-block">＋ ${slot ? 'この枠' : 'この日'}に予定を登録</button>
      <p class="sc-hint" style="margin-top:6px">
        空いている枠をタップすると、すぐに登録画面が開きます。枠を長押し（パソコンはShift＋クリック）すると、複数の枠をまとめて登録・変更できます。
      </p>
    </div>`;
    // 枠を選んだときは予定の内容を先に、日付を選んだときは希望の設定を先に出す
    elSidePanel.innerHTML = header + (slot ? jobsBlock + addBlock + wishBlock : wishBlock + jobsBlock + addBlock);
  }

  updateFormAlert();
}

function renderStats() {
  const days = monthDayKeys();
  const today = todayKey();
  let avail = 0, off = 0, confirmedDays = 0, confirmed = 0, tentative = 0, undecided = 0;
  days.forEach((key) => {
    const w = state.wishes[key];
    if (w === WISH_AVAILABLE) avail++;
    if (w === WISH_OFF) off++;
    const js = jobsOn(key);
    if (js.some((j) => j.status !== 'tentative')) confirmedDays++;
    js.forEach((j) => { if (j.status === 'tentative') tentative++; else confirmed++; });
    // 稼働可でも休み希望でもなく、予定も無い、今日以降のまだ決めていない日
    if (key >= today && !w && !js.length) undecided++;
  });

  const stat = (label, value, unit, cls) =>
    `<div class="sc-stat${cls ? ' ' + cls : ''}"><span class="sc-stat-label">${label}</span>` +
    `<span class="sc-stat-value">${value}<span class="sc-stat-unit">${unit}</span></span></div>`;

  const todoOpen = state.todos.filter((t) => !t.done).length;

  elStats.innerHTML =
    stat('確定', confirmed, '件', 'sc-stat-confirmed') +
    stat('仮出勤', tentative, '件', 'sc-stat-tentative') +
    stat('ToDo', todoOpen, '件', 'sc-stat-todo') +
    stat('確定の稼働日', confirmedDays, '日') +
    stat('稼働可能', avail, '日') +
    stat('休み希望', off, '日') +
    stat('未定', undecided, '日', 'sc-stat-empty');
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
      .map((j) => escapeHtml(jobHeading(j))).join(' ／ ');
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
    const meta = [j.client, j.storeName, j.place, j.address, j.note].filter(Boolean).map(escapeHtml).join(' / ');
    return `<div class="${cls.join(' ')}" data-goto="${j.date}">
      <span class="sc-joblist-badge ${tentative ? 'sc-badge-tentative' : 'sc-badge-confirmed'}">${tentative ? '仮出勤' : '確定'}</span>
      <span class="sc-joblist-date">${formatDate(j.date)}</span>
      <span class="sc-joblist-time">${escapeHtml(time)}</span>
      <span class="sc-joblist-title">${escapeHtml(jobHeading(j))}</span>
      ${jobSubLabel(j) ? `<span class="sc-joblist-proj">${escapeHtml(jobSubLabel(j))}</span>` : ''}
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
  off: '休み希望日', available: '稼働可能日', empty: '未定の日', all: '予定のある日',
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

/** 案件のバーの色（未設定時に色選択欄へ出す既定色。確定予定の既定色と同じ） */
const DEFAULT_BAR_COLOR = '#1d4ed8';

function renderProjectChips() {
  const box = $('projectChips');
  if (!box) return;
  box.innerHTML = state.settings.projects.length
    ? state.settings.projects.map((p) => {
        const color = state.settings.projectColors[p] || DEFAULT_BAR_COLOR;
        return `<span class="sc-proj-chip">`
          + `<input type="color" class="sc-proj-color" data-project-color="${escapeHtml(p)}" value="${color}" title="「${escapeHtml(p)}」のバーの色">`
          + `<button type="button" class="sc-proj-chip-name" data-project="${escapeHtml(p)}" title="削除">${escapeHtml(p)} ✕</button>`
          + `</span>`;
      }).join('')
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
  delete state.settings.projectColors[name];
  state.settingsAt = nowIso();
  saveState();
  refreshProjectFilterOptions();
  toast(`「${name}」を削除しました`);
}

/** 案件のバーの色を変える（既定色と同じにしたときは設定を消して既定に戻す） */
function setProjectColor(name, color) {
  if (!state.settings.projects.includes(name)) return;
  if (!/^#[0-9a-f]{6}$/i.test(color) || color.toLowerCase() === DEFAULT_BAR_COLOR) {
    delete state.settings.projectColors[name];
  } else {
    state.settings.projectColors[name] = color.toLowerCase();
  }
  state.settingsAt = nowIso();
  saveState();
  renderCalendar();
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
  // 同期などをきっかけに呼ばれても、入力中のフィールドは上書きしない
  // （案件を切り替えたときは選択肢＝select側にフォーカスがあるので、この判定には掛からない）
  const active = document.activeElement;
  const editing = active && ['pcTitle', 'pcAddress', 'pcDescription'].includes(active.id);
  if (!editing) {
    const cfg = currentPcConfig();
    $('pcTitle').value = cfg.title || '';
    $('pcAddress').value = cfg.address || '';
    $('pcDescription').value = cfg.description || '';
  }
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
  if (!client) { toast('業態名を入力してください', true); return; }
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

  if (target === 'all') {
    // 確定・仮出勤を問わず、その月に予定が入っている日をすべて対象にする
    return monthDayKeys().filter((key) => jobsOn(key).some((j) => !project || j.title === project));
  }

  if (target === 'empty') {
    const today = todayKey();
    // 稼働可でも休み希望でもなく、予定も無い、今日以降のまだ決めていない日（集計の「未定」と同じ定義）
    return monthDayKeys().filter((key) => key >= today && !state.wishes[key] && !jobsOn(key).length);
  }

  return monthDayKeys().filter((key) => {
    const w = state.wishes[key] || null;
    if (target === 'off') return w === WISH_OFF;
    if (w !== WISH_AVAILABLE) return false;
    if (excludeBooked && jobsOn(key).length) return false;
    return true;
  });
}

/** 「その月のすべての予定」は、案件ごとに件数・日付をまとめて示す */
function buildAllJobsGroupedText(format) {
  const keys = monthDayKeys();
  const project = $('projectFilter').value;
  const jobs = state.jobs.filter((j) => keys.includes(j.date) && (!project || j.title === project));
  const title = `${view.year}年${view.month + 1}月`;

  if (!jobs.length) {
    const howTo = project ? '案件名や対象の月をご確認ください。' : 'カレンダーで予定を登録すると、ここに表示されます。';
    return `${title} ${LIST_TARGET_NAMES.all}は登録されていません。\n${howTo}`;
  }

  const groups = new Map();
  jobs.forEach((j) => {
    const name = j.title || '(無題)';
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(j.date);
  });
  const names = Array.from(groups.keys()).sort((a, b) => {
    const diff = groups.get(b).length - groups.get(a).length;
    return diff !== 0 ? diff : a.localeCompare(b, 'ja');
  });

  const lines = names.map((name) => {
    const dates = groups.get(name).slice().sort();
    if (format === 'inline') return `${name}（${dates.length}件）：${dates.map((k) => formatDate(k)).join('、')}`;
    if (format === 'dayonly') return `${name}（${dates.length}件）：${dates.map((k) => Number(k.slice(8))).join('、')}日`;
    return `${name}（${dates.length}件）\n` + dates.map((k) => '・' + formatDate(k)).join('\n');
  });

  const header = `${title} ${LIST_TARGET_NAMES.all}（全${jobs.length}件 / ${names.length}案件）`;
  return format === 'bullet' ? [header].concat(lines).join('\n\n') : lines.join('\n');
}

function buildListText() {
  const target = $('listTarget').value;
  const format = $('exportFormat').value;

  if (target === 'all') return buildAllJobsGroupedText(format);

  const keys = listedDayKeys();
  const project = $('projectFilter').value;
  const isProject = (target === 'confirmed' || target === 'tentative') && project;
  const name = isProject ? `${project} の${LIST_TARGET_NAMES[target]}` : LIST_TARGET_NAMES[target];
  const title = `${view.year}年${view.month + 1}月`;

  if (!keys.length) {
    const howTo = target === 'off' ? 'カレンダーで「✕ 休み希望」を設定してください。'
      : target === 'available' ? 'カレンダーで「◯ 稼働可」を設定してください。'
      : target === 'empty' ? 'この先の日はすべて、休み希望・稼働可・予定のいずれかが決まっています。'
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
  const isJobTarget = target === 'confirmed' || target === 'tentative' || target === 'all';
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
    const meta = [jobSubLabel(j), j.workType, j.client, j.storeName, j.place, j.address].filter(Boolean).join(' / ');
    lines.push(`・[${j.status === 'tentative' ? '仮出勤' : '確定'}] ${formatDate(j.date)} ${time} ${jobHeading(j)}`
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
      projects: state.settings.projects,
      projectCalendar: state.settings.projectCalendar,
      projectColors: state.settings.projectColors,
      notify: state.settings.notify,
    },
    settingsAt: state.settingsAt,
    wishes: state.wishes,
    wishMeta: state.wishMeta,
    jobs: state.jobs,
    tombstones: state.tombstones,
    todos: state.todos,
    todoTombstones: state.todoTombstones,
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
  const todos = (Array.isArray(data && data.todos) ? data.todos : []).slice()
    .sort((a, b) => String(a && a.id).localeCompare(String(b && b.id)));
  return stableStringify({ wishes, jobs, todos, settings: (data && data.settings) || {} });
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
      todos: merged.todos, todoTombstones: merged.todoTombstones,
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
      ensureNotifyChecker();
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
 * {案件名}{タイトル}{業態}{店舗名}{業務内容}{住所}{日付}{開始}{終了}{メモ} が使える
 * （{依頼元}は{業態}、{店舗}は{店舗名}、{開始時間}{終了時間}は{開始}{終了}と同じ意味で使える。
 * 　{場所}も過去との互換のため残しているが、入力欄が無いため常に空になる）。
 */
function fillTemplate(tpl, job) {
  const map = {
    '案件名': job.title || '',
    'タイトル': job.subject || '',
    '依頼元': job.client || '',
    '業態': job.client || '',
    '業務内容': job.workType || '',
    '場所': job.place || '',
    '店舗名': job.storeName || '',
    '店舗': job.storeName || '',
    '住所': job.address || '',
    '日付': formatDate(job.date, 'long'),
    '開始': job.allDay ? '' : (job.start || ''),
    '終了': job.allDay ? '' : (job.end || ''),
    '開始時間': job.allDay ? '' : (job.start || ''),
    '終了時間': job.allDay ? '' : (job.end || ''),
    'メモ': job.note || '',
  };
  return String(tpl).replace(/\{(案件名|タイトル|依頼元|業態|業務内容|場所|店舗名|店舗|住所|日付|開始時間|終了時間|開始|終了|メモ)\}/g, (m, key) => map[key]);
}

function icsSummary(job, prefix) {
  const tentative = job.status === 'tentative';
  const cfg = state.settings.projectCalendar[job.title];
  const base = (cfg && cfg.title && cfg.title.trim()) ? fillTemplate(cfg.title, job) : jobHeading(job);
  return (prefix ? prefix : '') + (tentative ? '【仮】' : '') + base;
}

/** Googleカレンダー登録用の「場所」。案件ごとのカスタム住所があればそちらを使う */
function icsLocation(job) {
  const cfg = state.settings.projectCalendar[job.title];
  if (cfg && cfg.address && cfg.address.trim()) return fillTemplate(cfg.address, job);
  return jobLocation(job);
}

/** Googleカレンダー登録用の説明文。業態ごとの上書き→案件のカスタム説明文→既定の順で使う */
function icsDescription(job) {
  const cfg = state.settings.projectCalendar[job.title];
  if (cfg) {
    const override = cfg.clientOverrides && job.client ? cfg.clientOverrides[job.client] : null;
    if (override && override.trim()) return fillTemplate(override, job);
    if (cfg.description && cfg.description.trim()) return fillTemplate(cfg.description, job);
  }
  return [
    job.subject ? 'タイトル: ' + job.subject : '',
    job.title ? '案件名: ' + job.title : '',
    job.workType ? '業務内容: ' + job.workType : '',
    job.client ? '業態: ' + job.client : '',
    job.storeName ? '店舗名: ' + job.storeName : '',
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

/** 訪問先の住所の一覧（主の住所を先頭に、追加の訪問先を続ける。空欄は含めない） */
function jobVisits(job) {
  const list = [job.address].concat(
    (Array.isArray(job.extraAddresses) ? job.extraAddresses : [])
      .map((a) => (typeof a === 'string' ? a : (a && a.address) || ''))
  );
  return list.map((a) => (a || '').trim()).filter(Boolean);
}

/** 表示・地図検索用の文字列（複数の訪問先があれば「／」でつなげる） */
function jobLocation(job) {
  return jobVisits(job).join(' ／ ');
}

/** 住所を地図アプリで開くURL（スマホでは該当のアプリが開く） */
function mapUrl(kind, address) {
  const q = encodeURIComponent(address);
  if (kind === 'google') return `https://www.google.com/maps/search/?api=1&query=${q}`;
  if (kind === 'yahoo') return `https://map.yahoo.co.jp/search?q=${q}`;
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
    subject: '', titleSel: '', titleFree: '', workType: '', status: 'confirmed', allDay: false,
    start: slot ? slot.start : state.settings.defStart,
    end: slot ? slot.end : state.settings.defEnd,
    clientSel: '', clientFree: '', storeName: '', place: '', address: '', extraAddresses: [], note: '',
    useSlotTime: true,   // まとめて登録で、選んだ枠それぞれの時間帯を使うか
  };
}

function formFromJob(job) {
  const title = job.title || '';
  const preset = state.settings.projects.includes(title);
  const client = job.client || '';
  const knownClient = businessTypesForProject(title).includes(client);
  return {
    date: job.date,
    subject: job.subject || '',
    titleSel: title ? (preset ? title : PROJECT_FREE) : '',
    titleFree: preset ? '' : title,
    workType: job.workType || '',
    status: job.status || 'confirmed',
    allDay: !!job.allDay,
    start: job.start || state.settings.defStart,
    end: job.end || state.settings.defEnd,
    clientSel: client ? (knownClient ? client : CLIENT_FREE) : '',
    clientFree: knownClient ? '' : client,
    storeName: job.storeName || '',
    place: job.place || '',
    address: job.address || '',
    extraAddresses: (Array.isArray(job.extraAddresses) ? job.extraAddresses : [])
      .map((a) => (typeof a === 'string' ? a : (a && a.address) || '')),
    note: job.note || '',
  };
}

function readForm() {
  if (!$('jobForm')) return null;
  const dateEl = $('fDate');
  const extraAddresses = [];
  for (let i = 0; $('fExtraAddress-' + i); i++) {
    extraAddresses.push($('fExtraAddress-' + i).value);
  }
  return {
    date: dateEl ? dateEl.value : (view.selected || ''),
    subject: $('fSubject').value,
    titleSel: $('fTitle').value,
    titleFree: $('fTitleFree').value,
    workType: $('fWorkType').value,
    status: $('fStatus').value,
    allDay: $('fAllDay').checked,
    start: $('fStart').value,
    end: $('fEnd').value,
    clientSel: $('fClient').value,
    clientFree: $('fClientFree').value,
    storeName: $('fStoreName').value,
    // 「場所」欄は廃止したが、過去に登録済みの値は編集で消えないよう保持する
    place: (view.form && view.form.place) || '',
    address: $('fAddress').value,
    extraAddresses: extraAddresses,
    note: $('fNote').value,
    useSlotTime: $('fUseSlotTime') ? $('fUseSlotTime').checked : true,
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

/** 選択中の業態（自由入力ならその内容） */
function formClient(f) {
  return f.clientSel === CLIENT_FREE ? f.clientFree.trim() : f.clientSel;
}

/** フォームの現在値から仮の予定オブジェクトを作る */
function draftJob() {
  const f = view.form || blankForm();
  const draft = {
    id: view.editingId || '__draft__',
    date: f.date != null ? f.date : (view.selected || ''),
    allDay: !!f.allDay,
    start: f.start || state.settings.defStart,
    end: f.end || state.settings.defEnd,
    subject: (f.subject || '').trim(),
    title: formTitle(f),
    workType: f.workType.trim(),
    client: formClient(f),
    storeName: f.storeName.trim(),
    place: (f.place || '').trim(),
    address: f.address.trim(),
    extraAddresses: (f.extraAddresses || [])
      .map((a) => (a || '').trim())
      .filter(Boolean),
    note: f.note.trim(),
    status: f.status,
  };
  // メモに {店舗名} などの差し込み文字が残っていても、保存時点の内容で解決しておく
  // （業態を先に選んでからあとで店舗名を直した場合などに、古い予定を編集し直しても反映されるように）
  draft.note = fillTemplate(draft.note, draft);
  return draft;
}

/**
 * 業態の上書きテンプレートをメモへ差し込む（店舗名などを後から入力・変更したときも追従する）。
 * 手でメモを書き換えたあとは上書きしない（fNoteの内容が直前の自動挿入と一致する間だけ差し替える）。
 */
function applyClientOverrideNote() {
  const noteEl = $('fNote');
  if (!noteEl || !view.form) return;
  const clientSel = view.form.clientSel;
  if (!clientSel || clientSel === CLIENT_FREE) return;
  const project = formTitle(view.form);
  const cfg = state.settings.projectCalendar[project];
  const tpl = cfg && cfg.clientOverrides ? cfg.clientOverrides[clientSel] : null;
  if (!tpl || !tpl.trim()) return;
  if (noteEl.value && noteEl.value !== view.autoNoteText) return;
  const filled = fillTemplate(tpl, draftJob());
  noteEl.value = filled;
  view.form.note = filled;
  view.autoNoteText = filled;
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
      return `<li>${formatDate(c.job.date)} ${escapeHtml(t)} ${escapeHtml(jobHeading(c.job))} … ${kind}</li>`;
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

/**
 * まとめて登録で作る予定の一覧。
 *   枠の時間帯で登録 … 同じ日の続いた枠（AM＋P1など）は1件にまとめ、その範囲の時間で作る
 *   時間を指定／終日 … 1日につき1件（同じ日の枠を複数選んでも重ねて作らない）
 */
function multiPlan() {
  const f = view.form || blankForm();
  const byDate = {};
  Array.from(view.multi).forEach((pk) => {
    const [d, sid] = pk.split('|');
    const idx = SLOTS.findIndex((sl) => sl.id === sid);
    if (idx >= 0) (byDate[d] = byDate[d] || []).push(idx);
  });
  const plan = [];
  Object.keys(byDate).sort().forEach((d) => {
    const idxs = byDate[d].sort((x, y) => x - y);
    if (f.allDay || f.useSlotTime === false) {
      plan.push({ date: d, slotIds: idxs.map((i) => SLOTS[i].id) });
      return;
    }
    let run = [idxs[0]];
    for (let k = 1; k <= idxs.length; k++) {
      if (k < idxs.length && idxs[k] === run[run.length - 1] + 1) { run.push(idxs[k]); continue; }
      plan.push({ date: d, slotIds: run.map((i) => SLOTS[i].id), start: SLOTS[run[0]].start, end: SLOTS[run[run.length - 1]].end });
      if (k < idxs.length) run = [idxs[k]];
    }
  });
  return plan;
}

/** 計画1件分の、登録しようとしている予定 */
function multiDraft(item) {
  const base = draftJob();
  const job = Object.assign(base, { id: '__draft__', date: item.date });
  if (item.start && !job.allDay) { job.start = item.start; job.end = item.end; }
  // メモの差し込み（日付・時間）は、その予定の内容で作り直す
  job.note = fillTemplate((view.form || blankForm()).note.trim(), job);
  return job;
}

/** 選んだ枠それぞれについて、登録しようとしている内容の重複を調べる */
function multiConflicts() {
  const f = view.form || blankForm();
  if (!f.titleSel) return [];
  return multiPlan().map((item) => {
    const names = item.slotIds.map((sid) => { const sl = slotById(sid); return sl ? sl.name : ''; }).join('・');
    return { date: item.date, slotName: names, conflicts: findConflicts(multiDraft(item), null) };
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
        const names = r.conflicts.map((c) => escapeHtml(jobHeading(c.job))
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
    const n = multiPlan().length;
    return hasConflict ? `重複を承知で ${n}件を登録` : `${n}件をまとめて登録`;
  }
  if (view.confirming) return hasConflict ? '重複を承知で確定する' : 'この内容で確定する';
  if (view.editingId) return hasConflict ? '重複を承知で更新' : '更新する';
  return hasConflict ? '重複を承知で登録' : '登録する';
}

/** 選んだ日すべてに同じ内容の予定を作る */
function submitMultiJobs() {
  const base = draftJob();

  if (!view.multi.size) { toast('枠を選んでください', true); return; }
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
  const plan = multiPlan();
  plan.forEach((item) => {
    const job = Object.assign({}, multiDraft(item), {
      id: newId(), createdAt: at, updatedAt: at,
    });
    if (job.status === 'confirmed') job.confirmedAt = at;
    state.jobs.push(job);
    if (state.wishes[job.date] === WISH_OFF) setWish(job.date, null, true);
  });

  view.ack = false;
  view.form = blankForm();
  saveState();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  finishMultiAction();
  toast(`${plan.length}件の予定を登録しました`);
}

/**
 * まとめて操作が終わったあとの後片付け。選択を空にしてシートを閉じる。
 * 長押し・Shiftクリックで一時的に入った選択モードなら、元の「選択のみ」に戻す。
 */
function finishMultiAction() {
  view.multi.clear();
  view.multiTab = null;
  view.bulkForm = null;
  view.bulkExclude.clear();
  view.popup = false;
  if (view.multiAuto) setPaintMode(null);
  else renderAll();
}

/** まとめて変更の入力欄の値を読む */
function readBulkForm() {
  if (!$('bulkForm')) return view.bulkForm || blankBulkForm();
  return {
    subject: $('bSubject').value,
    title: $('bTitle').value,
    workType: $('bWorkType').value,
    status: $('bStatus').value,
    timeMode: $('bTimeMode').value,
    start: $('bStart').value,
    end: $('bEnd').value,
    client: $('bClient').value,
    storeName: $('bStoreName').value,
  };
}

/** 予定に変更を当てはめる（版数・更新日時をそろえ、確定にしたときは確定日時を残す） */
function applyJobChanges(job, changes, at) {
  const next = Object.assign({}, job, changes, { updatedAt: at, rev: (Number(job.rev) || 0) + 1 });
  if (next.status === 'confirmed' && next.confirmedAt == null) next.confirmedAt = at;
  return next;
}

/** まとめて変更の対象どうし・ほかの予定との重複を数える（変更後の内容で判定する） */
function countBulkConflicts(updatedById) {
  const buffer = Number(state.settings.bufferMin) || 0;
  const all = state.jobs.map((j) => updatedById[j.id] || j);
  let n = 0;
  Object.keys(updatedById).forEach((id) => {
    const job = updatedById[id];
    const hit = all.some((other) => other.id !== id
      && Math.abs(dayIndex(other.date) - dayIndex(job.date)) <= 1
      && relation(job, other, buffer) === 'overlap');
    if (hit) n++;
  });
  return n;
}

/** 選んだ予定の内容をまとめて書き換える */
async function applyBulkEdit() {
  const targets = bulkTargets();
  if (!targets.length) { toast('変更する予定を選んでください', true); return; }
  const b = view.bulkForm = readBulkForm();

  const changes = {};
  if (b.subject.trim()) changes.subject = b.subject.trim();
  if (b.title) changes.title = b.title;
  if (b.workType.trim()) changes.workType = b.workType.trim();
  if (b.status) changes.status = b.status;
  if (b.timeMode === 'allday') changes.allDay = true;
  if (b.timeMode === 'time') {
    if (toMinutes(b.start) === null || toMinutes(b.end) === null) { toast('開始・終了時刻を入力してください', true); return; }
    Object.assign(changes, { allDay: false, start: b.start, end: b.end });
  }
  if (b.client.trim()) changes.client = b.client.trim();
  if (b.storeName.trim()) changes.storeName = b.storeName.trim();
  if (!Object.keys(changes).length) { toast('変更する項目を入力してください', true); return; }

  const at = nowIso();
  const updatedById = {};
  targets.forEach((j) => { updatedById[j.id] = applyJobChanges(j, changes, at); });

  const missingWork = Object.keys(updatedById).filter((id) => updatedById[id].status === 'confirmed' && !(updatedById[id].workType || '').trim());
  if (missingWork.length) {
    toast(`業務内容が未入力の予定が${missingWork.length}件あります。確定にするには業務内容も入力してください`, true);
    const el = $('bWorkType');
    if (el) el.focus();
    return;
  }

  const labels = [];
  if (changes.subject) labels.push(`タイトル→${changes.subject}`);
  if (changes.title) labels.push(`案件名→${changes.title}`);
  if (changes.workType) labels.push(`業務内容→${changes.workType}`);
  if (changes.status) labels.push(`区分→${changes.status === 'confirmed' ? '確定' : '仮出勤'}`);
  if (b.timeMode === 'allday') labels.push('時間→終日');
  if (b.timeMode === 'time') labels.push(`時間→${b.start}〜${b.end}`);
  if (changes.client) labels.push(`業態→${changes.client}`);
  if (changes.storeName) labels.push(`店舗名→${changes.storeName}`);
  let msg = `選んだ ${targets.length}件 の予定を変更します。\n\n${labels.map((l) => '・' + l).join('\n')}`;
  const overlaps = countBulkConflicts(updatedById);
  if (overlaps) msg += `\n\n⚠ 変更後、${overlaps}件 がほかの予定と時間が重なります。`;
  msg += '\n\nよろしいですか？';
  if (!(await customConfirm(msg))) return;

  state.jobs = state.jobs.map((j) => updatedById[j.id] || j);
  // 確定にした日は、休み希望を外して稼働扱いにそろえる（単日の登録と同じ扱い）
  Object.keys(updatedById).forEach((id) => {
    const j = updatedById[id];
    if (j.status === 'confirmed' && state.wishes[j.date] === WISH_OFF) setWish(j.date, null, true);
  });
  saveState();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  finishMultiAction();
  toast(`${targets.length}件の予定を変更しました`);
}

/** 選んだ予定のうち、仮出勤をまとめて確定にする */
async function bulkConfirmTentatives() {
  const b = view.bulkForm = readBulkForm();
  const tentative = bulkTargets().filter((j) => j.status === 'tentative');
  if (!tentative.length) return;
  const fallbackWork = b.workType.trim();
  const missing = tentative.filter((j) => !(j.workType || '').trim());
  if (missing.length && !fallbackWork) {
    toast(`業務内容が未入力の仮出勤が${missing.length}件あります。下の「業務内容」を入力してから確定してください`, true);
    const el = $('bWorkType');
    if (el) el.focus();
    return;
  }
  const lines = tentative.map((j) => `・${formatDate(j.date)} ${j.allDay ? '終日' : j.start + '〜' + j.end} ${jobHeading(j)}`).join('\n');
  if (!(await customConfirm(`仮出勤 ${tentative.length}件 を確定にします。\n\n${lines}\n\nよろしいですか？`))) return;

  const at = nowIso();
  const ids = new Set(tentative.map((j) => j.id));
  state.jobs = state.jobs.map((j) => {
    if (!ids.has(j.id)) return j;
    const changes = { status: 'confirmed' };
    if (!(j.workType || '').trim()) changes.workType = fallbackWork;
    return applyJobChanges(j, changes, at);
  });
  tentative.forEach((j) => { if (state.wishes[j.date] === WISH_OFF) setWish(j.date, null, true); });
  saveState();
  refreshWorkTypeOptions();
  finishMultiAction();
  toast(`${tentative.length}件を確定にしました`);
}

/** 選んだ予定をまとめて削除する */
async function bulkDeleteSelected() {
  const targets = bulkTargets();
  if (!targets.length) return;
  const lines = targets.slice(0, 12).map((j) => `・${formatDate(j.date)} ${j.allDay ? '終日' : j.start + '〜' + j.end} ${jobHeading(j)}`).join('\n')
    + (targets.length > 12 ? `\n…ほか${targets.length - 12}件` : '');
  if (!(await customConfirm(`選んだ ${targets.length}件 の予定を削除します。\n\n${lines}\n\n元に戻せません。よろしいですか？`))) return;
  removeJobs(new Set(targets.map((j) => j.id)));
  saveState();
  refreshProjectFilterOptions();
  finishMultiAction();
  toast(`${targets.length}件の予定を削除しました`);
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
  // 登録・更新したら入力画面を閉じて、その枠の内容表示に戻す（スマホはシートごと閉じる）
  view.formOpen = false;
  if (isNarrow()) view.popup = false;
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
    .map((j) => `・${formatDate(j.date)} ${j.allDay ? '終日' : j.start + '〜' + j.end} ${jobHeading(j)}`)
    .join('\n');
  customConfirm(`確定した予定と重なっている仮出勤が ${leftovers.length}件 あります。\n\n${names}\n\nこれらを取り消しますか？（「キャンセル」で残します）`).then((ok) => {
    if (!ok) return;
    const ids = new Set(leftovers.map((j) => j.id));
    removeJobs(ids);
    saveState();
    renderAll();
    toast(`仮出勤 ${ids.size}件 を取り消しました`);
  });
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

/** 予定を複製する。同じ内容の新規登録フォームを開くだけで、その場ではまだ登録しない */
function startDuplicate(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  view.selected = job.date;
  view.editingId = null;
  view.confirming = false;
  view.formOpen = true;
  view.ack = false;
  view.form = formFromJob(job);
  const d = fromKey(job.date);
  view.year = d.getFullYear();
  view.month = d.getMonth();
  renderAll();
  toast('内容を複製しました。日時を確認して登録してください');
  const el = $('fDate');
  if (el) el.focus();
}

async function deleteJob(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  if (!(await customConfirm(`「${jobHeading(job)}」（${formatDate(job.date, 'long')}）を削除します。よろしいですか？`))) return;
  removeJobs(new Set([id]));
  if (view.editingId === id) {
    view.editingId = null;
    view.confirming = false;
    view.form = blankForm();
  }
  view.formOpen = false;
  // 狭い画面ではシートのまま残ると裏の操作ができなくなるため閉じる
  if (isNarrow()) view.popup = false;
  saveState();
  renderAll();
  toast('予定を削除しました');
}

/* ------------------------------------------------------------
   ToDoリスト
   ------------------------------------------------------------ */

const TODO_CATEGORY_NAMES = { work: '仕事', personal: '個人' };

/** 期限（あれば時刻まで）を表示用の文字列にする */
function todoDueLabel(t) {
  if (!t.dueDate) return '';
  return t.dueTime ? `${formatDate(t.dueDate)} ${t.dueTime}まで` : `${formatDate(t.dueDate)}まで`;
}

/** 期限を過ぎているか（完了済み・期限なしは対象外） */
function isTodoOverdue(t) {
  if (t.done || !t.dueDate) return false;
  const due = new Date(`${t.dueDate}T${t.dueTime || '23:59'}:00`);
  return due.getTime() < Date.now();
}

function addTodo(data) {
  state.todos.push({
    id: newId(),
    title: data.title.trim(),
    note: (data.note || '').trim(),
    category: data.category === 'personal' ? 'personal' : 'work',
    dueDate: data.dueDate || '',
    dueTime: data.dueDate ? (data.dueTime || '') : '',
    done: false,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  });
  saveState();
  toast('ToDoを追加しました');
}

function updateTodo(id, data) {
  const t = state.todos.find((x) => x.id === id);
  if (!t) return;
  t.title = data.title.trim();
  t.note = (data.note || '').trim();
  t.category = data.category === 'personal' ? 'personal' : 'work';
  t.dueDate = data.dueDate || '';
  t.dueTime = t.dueDate ? (data.dueTime || '') : '';
  t.updatedAt = nowIso();
  saveState();
  toast('ToDoを更新しました');
}

function toggleTodoDone(id) {
  const t = state.todos.find((x) => x.id === id);
  if (!t) return;
  t.done = !t.done;
  t.updatedAt = nowIso();
  saveState();
  renderAll();
}

/** ToDoを消す。他の端末にも削除が伝わるよう記録を残す */
function deleteTodo(id) {
  const t = state.todos.find((x) => x.id === id);
  if (!t) return;
  if (!confirm(`「${t.title || '(無題)'}」を削除します。よろしいですか？`)) return;
  const at = nowIso();
  state.todos = state.todos.filter((x) => x.id !== id);
  state.todoTombstones = state.todoTombstones.filter((x) => x.id !== id);
  state.todoTombstones.push({ id, updatedAt: at });
  if (view.todoEditingId === id) { view.todoFormOpen = false; view.todoEditingId = null; }
  saveState();
  renderAll();
  renderTodoForm();
  toast('ToDoを削除しました');
}

function todoFormHtml(t) {
  const f = t || { title: '', note: '', category: 'work', dueDate: '', dueTime: '' };
  const editing = !!(t && t.id);
  return `<form id="todoForm" class="sc-form sc-todo-form">
    <label class="sc-field">
      <span class="sc-field-label">項目名 <span aria-hidden="true">*</span></span>
      <input id="tdTitle" class="sc-input" type="text" value="${escapeHtml(f.title)}" placeholder="例）見積書を送る" autocomplete="off">
    </label>
    <label class="sc-field">
      <span class="sc-field-label">内容</span>
      <textarea id="tdNote" class="sc-input" rows="3">${escapeHtml(f.note)}</textarea>
    </label>
    <label class="sc-field">
      <span class="sc-field-label">区分</span>
      <select id="tdCategory" class="sc-input">
        <option value="work" ${f.category !== 'personal' ? 'selected' : ''}>仕事</option>
        <option value="personal" ${f.category === 'personal' ? 'selected' : ''}>個人</option>
      </select>
    </label>
    <div class="sc-form-row">
      <label class="sc-field">
        <span class="sc-field-label">期限（任意）</span>
        <input id="tdDueDate" class="sc-input" type="date" value="${escapeHtml(f.dueDate)}">
      </label>
      <label class="sc-field">
        <span class="sc-field-label">何時まで（任意）</span>
        <input id="tdDueTime" class="sc-input" type="time" value="${escapeHtml(f.dueTime)}">
      </label>
    </div>
    <div class="sc-form-actions">
      <button type="submit" id="tdSubmit" class="sc-btn">${editing ? '更新する' : '登録する'}</button>
      <button type="button" id="tdCancel" class="sc-btn sc-btn-outline">中止</button>
    </div>
  </form>`;
}

function renderTodoForm() {
  const wrap = $('todoFormWrap');
  if (!wrap) return;
  if (!view.todoFormOpen) { wrap.hidden = true; wrap.innerHTML = ''; return; }
  const editing = view.todoEditingId ? state.todos.find((t) => t.id === view.todoEditingId) : null;
  wrap.hidden = false;
  wrap.innerHTML = todoFormHtml(editing);
}

function renderTodos() {
  const openCount = state.todos.filter((t) => !t.done).length;
  const elCount = $('todoCount');
  if (elCount) elCount.textContent = state.todos.length ? `未完了${openCount}件 / 全${state.todos.length}件` : '';

  const sorted = state.todos.slice().sort((a, b) => {
    // 期限が近い順（期限なしは後ろ）、次に登録順
    const da = a.dueDate ? `${a.dueDate}T${a.dueTime || '23:59'}` : '9999-99-99';
    const db = b.dueDate ? `${b.dueDate}T${b.dueTime || '23:59'}` : '9999-99-99';
    if (da !== db) return da < db ? -1 : 1;
    return (a.createdAt || '') < (b.createdAt || '') ? -1 : 1;
  });
  const list = view.todoFilter === 'open' ? sorted.filter((t) => !t.done) : sorted;

  const elList = $('todoList');
  if (!elList) return;
  if (!list.length) {
    elList.innerHTML = `<p class="sc-empty-note">${view.todoFilter === 'open' ? '未完了のToDoはありません。' : 'ToDoはまだ登録されていません。'}</p>`;
    return;
  }

  elList.innerHTML = list.map((t) => {
    const overdue = isTodoOverdue(t);
    const cls = ['sc-todo-row'];
    if (t.done) cls.push('sc-todo-row-done');
    if (overdue) cls.push('sc-todo-row-overdue');
    const dueLabel = todoDueLabel(t);
    return `<div class="${cls.join(' ')}">
      <label class="sc-todo-check">
        <input type="checkbox" data-todo-toggle="${t.id}" ${t.done ? 'checked' : ''} aria-label="完了にする">
      </label>
      <div class="sc-todo-body">
        <div class="sc-todo-top">
          <span class="sc-todo-badge sc-todo-badge-${t.category}">${TODO_CATEGORY_NAMES[t.category] || '仕事'}</span>
          <span class="sc-todo-title">${escapeHtml(t.title || '(無題)')}</span>
          ${dueLabel ? `<span class="sc-todo-due${overdue ? ' sc-todo-due-overdue' : ''}">${overdue ? '⚠' : '⏰'} ${escapeHtml(dueLabel)}</span>` : ''}
        </div>
        ${t.note ? `<p class="sc-todo-note">${escapeHtml(t.note)}</p>` : ''}
      </div>
      <div class="sc-todo-actions">
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-todo-edit="${t.id}">編集</button>
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" data-todo-del="${t.id}">削除</button>
      </div>
    </div>`;
  }).join('');
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
async function confirmSingleDayPaint(dateKey) {
  const label = view.paint === 'off' ? '休み希望' : view.paint === 'available' ? '稼働可' : '未定';
  const verb = view.paint === 'clear' ? 'に戻します' : 'で登録します';
  let msg = `${formatDate(dateKey, 'long')}を「${label}」${verb}。\nよろしいですか？`;

  const busy = jobsOn(dateKey);
  if (view.paint === 'off' && busy.length) {
    msg += `\n\n※この日にはすでに予定が${busy.length}件あります。`;
  }

  const ok = await customConfirm(msg);
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

/**
 * ブラウザ標準の confirm() の代わりに使う確認ダイアログ。
 * ホーム画面に追加したPWAなど、端末によっては confirm() が出ない（または無反応で
 * そのまま先に進んでしまう）ことがあるため、休み希望などの1タップ確定には必ずこちらを使う。
 */
let confirmResolve = null;
function customConfirm(message) {
  return new Promise((resolve) => {
    confirmResolve = resolve;
    elConfirmMsg.textContent = message;
    elConfirmOverlay.hidden = false;
    confirmOpenedAt = Date.now();
  });
}
function closeCustomConfirm(result) {
  if (elConfirmOverlay.hidden) return;
  elConfirmOverlay.hidden = true;
  const resolve = confirmResolve;
  confirmResolve = null;
  if (resolve) resolve(result);
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
      const todos = Array.isArray(parsed.todos) ? parsed.todos.filter(isValidTodo) : [];
      if (!confirm(`現在のデータを置き換えます。\n予定 ${jobs.length}件 / 希望 ${Object.keys(wishes).length}日 / ToDo ${todos.length}件\nよろしいですか？`)) return;
      state = {
        version: 1,
        settings: Object.assign(defaultState().settings, parsed.settings || {}),
        wishes,
        jobs,
        todos,
        todoTombstones: [],
      };
      view.editingId = null;
      view.confirming = false;
      view.form = blankForm();
      saveState();
      syncSettingsInputs();
      refreshWorkTypeOptions();
      refreshProjectFilterOptions();
      ensureNotifyChecker();
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
  $('notifyEnabled').checked = !!state.settings.notify.enabled;
  $('notifyLead').value = String(state.settings.notify.leadMinutes || 0);
  renderNotifyStatus();
}

/* ------------------------------------------------------------
   予定の時間の通知（この画面を開いている間だけ働く）
   ------------------------------------------------------------ */

const NOTIFIED_STORE = 'ms-schedule-notified-v1';
let notifyTimer = null;

function notifySupported() {
  return typeof Notification !== 'undefined';
}

function renderNotifyStatus() {
  const el = $('notifyStatus');
  if (!el) return;
  if (!notifySupported()) { el.textContent = 'この端末・ブラウザは通知に対応していません'; return; }
  if (Notification.permission === 'denied') { el.textContent = '通知がブロックされています（ブラウザの設定から許可してください）'; return; }
  if (Notification.permission !== 'granted') { el.textContent = '未許可（「通知を許可する」を押してください）'; return; }
  el.textContent = state.settings.notify.enabled ? '有効（この画面を開いている間だけ届きます）' : '許可済み（オフになっています）';
}

/** ブラウザに通知の許可を求める（ボタン操作からのみ呼べる） */
async function requestNotifyPermission() {
  if (!notifySupported()) { toast('この端末・ブラウザは通知に対応していません', true); return; }
  const perm = await Notification.requestPermission();
  renderNotifyStatus();
  if (perm === 'granted') {
    toast('通知を許可しました');
    ensureNotifyChecker();
  } else {
    toast('通知が許可されませんでした', true);
  }
}

/** 通知済みの予定の記録（この端末だけの記録。予定データとは別に保存する） */
function loadNotifiedSet() {
  try {
    const raw = localStorage.getItem(NOTIFIED_STORE);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch (err) { return new Set(); }
}

function saveNotifiedSet(set) {
  try {
    // 2日より前の記録は捨てて、際限なく増えないようにする
    const limit = toKey(addDays(new Date(), -2));
    const kept = Array.from(set).filter((k) => k.slice(k.indexOf('@') + 1) >= limit);
    localStorage.setItem(NOTIFIED_STORE, JSON.stringify(kept));
  } catch (err) { /* 保存できなくても動作には影響しない */ }
}

/** 通知が必要な予定があれば、その場でブラウザ通知を出す（nowを渡すと動作確認しやすい） */
function checkUpcomingNotifications(nowArg) {
  if (!notifySupported() || Notification.permission !== 'granted' || !state.settings.notify.enabled) return;
  const lead = Number(state.settings.notify.leadMinutes) || 0;
  const now = nowArg || new Date();
  const today = toKey(now);
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const notified = loadNotifiedSet();
  let changed = false;

  jobsOn(today).forEach((j) => {
    if (j.allDay) return;
    const s = toMinutes(j.start);
    if (s === null) return;
    const notifyAt = s - lead;
    // 通知の対象時刻を過ぎていて、かつ大きく過ぎすぎていない（10分以内）ものだけ対象にする
    if (nowMin < notifyAt || nowMin - notifyAt > 10) return;
    const key = j.id + '@' + today;
    if (notified.has(key)) return;
    notified.add(key);
    changed = true;
    const remain = s - nowMin;
    const timing = remain > 0 ? `あと${remain}分で開始` : remain < 0 ? `${-remain}分前に開始` : 'まもなく開始';
    const body = [j.workType, j.client, j.storeName, j.place].filter(Boolean).join(' / ') || `${j.start}〜${j.end}`;
    try {
      const n = new Notification(`${(j.subject || '').trim() || j.title || '予定'}（${timing}）`, { body, tag: key });
      n.onclick = () => { window.focus(); n.close(); };
    } catch (err) { /* 通知が出せなくても動作には影響しない */ }
  });

  if (changed) saveNotifiedSet(notified);
}

/** 通知の定期チェックを、必要なときだけ動かす */
function ensureNotifyChecker() {
  clearInterval(notifyTimer);
  notifyTimer = null;
  if (!notifySupported() || Notification.permission !== 'granted' || !state.settings.notify.enabled) return;
  checkUpcomingNotifications();
  notifyTimer = setInterval(checkUpcomingNotifications, 30000);
}

/* ------------------------------------------------------------
   イベント登録
   ------------------------------------------------------------ */

function moveMonth(delta) {
  const d = new Date(view.year, view.month + delta, 1);
  view.year = d.getFullYear();
  view.month = d.getMonth();
  renderAll();
  animateMonthChange(delta);
  // スマホでは月の途中までスクロールしていることが多いので、新しい月の先頭（今月なら今日）を見せる
  if (isNarrow()) {
    const t = fromKey(todayKey());
    if (t.getFullYear() === view.year && t.getMonth() === view.month) scrollToTodayIfNeeded(false);
    else scrollToCalendarTop(false);
  }
}

const PAINT_HINTS = {
  none: '枠をタップすると、空いている枠はすぐに登録画面、予定がある枠はその内容（編集・確定・削除）が開きます。日付をタップすると、その日の内容と希望の設定が開きます。枠を長押し（パソコンはShift＋クリックかドラッグ）すると、複数の枠をまとめて登録・変更できます。◯稼働可・✕休み希望は、左端の日付をタップ（ドラッグ）して設定します。スマホでは右下の「＋」からも追加でき、カレンダーを左右にスワイプすると月を切り替えられます。開いた画面は、下に引っぱるか「戻る」で閉じられます。',
  multi: 'まとめて操作中です。枠をタップで追加・解除できます（パソコンはドラッグ、スマホは長押ししたまま指でなぞると連続で選べます）。日付をタップすると、その日の枠をまとめて選べます。選んだら「登録」か「変更」を押してください。',
  paint: 'まとめて入力中です。日付を1日だけタップすると確認してから登録します。日付の列をドラッグすると、複数日を確認なしで一括設定できます。終わったら「選択のみ」に戻してください。',
};

/** 操作モード（選択のみ／まとめて登録／稼働可／休み希望／未定に戻す）を切り替える */
function setPaintMode(mode, opts) {
  view.paint = mode || null;
  if (view.paint !== 'multi') {
    view.multi.clear();
    view.multiAuto = false;
  }
  view.multiTab = null;
  view.bulkForm = null;
  view.bulkExclude.clear();
  view.popup = false;
  view.formOpen = false;
  view.editingId = null;
  view.confirming = false;
  view.ack = false;
  view.form = blankForm();
  const tabKey = view.paint || 'off-mode';
  document.querySelectorAll('.sc-paint-tab').forEach((t) => {
    t.classList.toggle('active', t.dataset.paint === tabKey);
  });
  const hint = $('paintHint');
  if (hint) hint.textContent = !view.paint ? PAINT_HINTS.none : view.paint === 'multi' ? PAINT_HINTS.multi : PAINT_HINTS.paint;
  const cal = document.querySelector('.sc-calendar');
  if (cal) cal.classList.toggle('sc-calendar-painting', !!view.paint);
  if (!(opts && opts.noRender)) renderAll();
}

/** 「選択のみ」から、長押し・ドラッグ・Shiftクリックで複数選択モードへ切り替える */
function switchToMulti() {
  setPaintMode('multi', { noRender: true });
  view.multiAuto = true;
  view.selected = null;
  view.slot = null;
  updateSelectionMarks();
}

/** 選択中の枠・日付の枠線だけを付け替える（カレンダー全体を作り直さないので、タップの反応が速い） */
function updateSelectionMarks() {
  elCalendar.querySelectorAll('.sc-vcell-selected').forEach((el) => el.classList.remove('sc-vcell-selected'));
  elCalendar.querySelectorAll('.sc-vdate-selected').forEach((el) => el.classList.remove('sc-vdate-selected'));
  if (!view.selected) return;
  const target = view.slot
    ? elCalendar.querySelector(`[data-pick="${view.selected}|${view.slot}"]`)
    : elCalendar.querySelector(`[data-role="date"][data-date="${view.selected}"]`);
  if (target) target.classList.add(view.slot ? 'sc-vcell-selected' : 'sc-vdate-selected');
}

/** まとめて選択中の枠の見た目だけを付け替える */
function updateMultiMark(pick) {
  const cell = elCalendar.querySelector(`[data-pick="${pick}"]`);
  if (!cell) return;
  const on = view.multi.has(pick);
  cell.classList.toggle('sc-vcell-multi', on);
  const check = cell.querySelector('.sc-vcell-check');
  if (on && !check) cell.insertAdjacentHTML('beforeend', '<span class="sc-vcell-check">✓</span>');
  if (!on && check) check.remove();
}

/** 選択を追加・解除する（見た目は該当の枠だけ更新し、パネルは次の描画のときにまとめて更新する） */
function setMultiPick(pick, on) {
  if (on) view.multi.add(pick); else view.multi.delete(pick);
  updateMultiMark(pick);
  scheduleSidePanel();
}

let sidePanelFrame = 0;
function scheduleSidePanel() {
  if (sidePanelFrame) return;
  sidePanelFrame = requestAnimationFrame(() => {
    sidePanelFrame = 0;
    renderSidePanel();
  });
}

function selectDate(key, opts) {
  const wasOpen = elSideAside && elSideAside.classList.contains('sc-side-modal-open');
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
    renderAll();
  } else {
    updateSelectionMarks();
    renderSidePanel();
  }
  if (!wasOpen && (view.formOpen || view.popup)) view.sheetOpenedAt = Date.now();
  if (elSidePanel) elSidePanel.scrollTop = 0;
}

/** 登録フォーム（シート）を閉じて、選んだ日の内容表示に戻す */
function closeForm() {
  if (view.paint === 'multi') {
    // まとめて操作中は、入力途中の内容と選択は残したまま閉じるだけにする
    view.popup = false;
    renderSidePanel();
    return;
  }
  view.editingId = null;
  view.confirming = false;
  view.formOpen = false;
  view.popup = false;
  view.ack = false;
  view.form = blankForm();
  renderSidePanel();
}

function isNarrow() { return window.innerWidth <= 900; }

/** 狭い画面では、選んだ枠の内容や登録画面をカレンダーの上に下からのシートで重ねて表示する */
function updateSideModalState() {
  if (!elSideAside) return;
  const open = isNarrow() && (view.paint === 'multi' ? view.popup : (view.formOpen || view.popup));
  elSideAside.classList.toggle('sc-side-modal-open', open);
  document.body.classList.toggle('sc-modal-open-body', open || voice.open);
  renderMultiBar();
  syncHistoryLayers();
}

/** スマホでまとめて選択しているときに、画面の下に出す操作バー */
function renderMultiBar() {
  const bar = $('multiBar');
  if (!bar) return;
  const show = view.paint === 'multi';
  bar.hidden = !show;
  document.body.classList.toggle('sc-has-multibar', show);
  if (!show) return;
  const n = view.multi.size;
  const jobs = n ? multiSelectedJobs().length : 0;
  bar.innerHTML = `
    <div class="sc-multibar-info">
      <strong>${n}枠</strong>を選択中${jobs ? `<span>（予定 ${jobs}件）</span>` : ''}
      ${n ? '' : '<small>枠をタップして選んでください</small>'}
    </div>
    <div class="sc-multibar-actions">
      <button type="button" class="sc-btn sc-btn-sm" data-multibar="add" ${n ? '' : 'disabled'}>＋ 登録</button>
      ${jobs ? '<button type="button" class="sc-btn sc-btn-sm sc-btn-accent" data-multibar="edit">✎ 変更</button>' : ''}
      ${n ? '<button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-multibar="clear">解除</button>' : ''}
      <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-multibar="exit">終了</button>
    </div>`;
}

/* ------------------------------------------------------------
   音声（・文章）での予定入力
   「10月27日の13時から15時でOES入替作業で登録」のような話し言葉から、
   日付・時間・案件名・業務内容などを読み取って入力画面に反映する。
   解析はこの端末の中だけで行う（話した内容を外部に送らない）。
   音声の文字起こしは、端末（ブラウザ）の音声認識機能を使う。
   ------------------------------------------------------------ */

/** 全角英数字などを半角にそろえ、空白を整える */
function voiceNormalize(t) {
  return String(t == null ? '' : t).normalize('NFKC').replace(/[\s　]+/g, ' ').trim();
}

function voiceEscapeRe(str) {
  return String(str).replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&');
}

/** 名前の文字の間に「・」や空白が入っていても一致する正規表現にする（点検保守 ⇔ 点検・保守） */
function voiceLooseRe(name) {
  const chars = Array.from(voiceNormalize(name).replace(/[・･\/／\s]/g, ''));
  if (!chars.length) return null;
  return new RegExp(chars.map(voiceEscapeRe).join('[・･\\/／\\s]*'), 'i');
}

/** 候補の中から、文中に出てくるいちばん長いものを探す。見つかれば {name, re} */
function voiceFindName(text, names) {
  let best = null;
  (names || []).forEach((name) => {
    const re = voiceLooseRe(name);
    if (!re) return;
    const m = re.exec(text);
    if (m && (!best || voiceNormalize(name).length > voiceNormalize(best.name).length)) best = { name: name, re: re };
  });
  return best;
}

const VOICE_KEYS = [
  ['subject', 'タイトル'],
  ['note', 'メモ'],
  ['address', '住所'],
  ['storeName', '店舗名|店舗'],
  ['client', '業態'],
  ['project', '案件名|案件'],
  ['workType', '業務内容|業務'],
];
const VOICE_STOP = 'タイトル|メモ|住所|店舗|業態|案件|業務|区分';

/** 値の末尾の「です」「にして」などを取り除く */
function voiceTrimValue(v) {
  let out = String(v).trim();
  for (let i = 0; i < 3; i++) {
    const next = out.replace(/(?:(?:に|へ)?(?:変更|修正|訂正|変えて|直して)(?:して|します|してください)?|でお願い.*|にしてください|にして|にする|にします|として|です|でし?た?|だ|で)$/u, '').trim();
    if (next === out) break;
    out = next;
  }
  return out.replace(/^[「『"“]|[」』"”]$/g, '').trim();
}

/** 時刻（"13:00" など）を作る。mer は 午前/午後 などの語、h は時、m は分 */
function voiceClock(mer, h, m, half) {
  let hour = Number(h);
  let min = m != null && m !== '' ? Number(m) : (half ? 30 : 0);
  if (!isFinite(hour) || hour > 24 || min > 59) return null;
  if (mer === '午後' || mer === '夕方' || mer === '夜') { if (hour < 12) hour += 12; }
  else if (mer === '午前' || mer === '朝') { if (hour === 12) hour = 0; }
  else if (hour >= 1 && hour <= 4) hour += 12;   // 「3時から」は、ふつう午後
  return { hour: hour, min: min, explicit: !!mer };
}

function voiceTimeStr(c) {
  const total = Math.min(23 * 60 + 59, c.hour * 60 + c.min);
  return pad2(Math.floor(total / 60)) + ':' + pad2(total % 60);
}

/**
 * 話し言葉1回分を解析する。見つかった項目だけを fields に入れて返す。
 *   ctx.today … 基準の日付(Date)  ctx.projects … 案件名の一覧
 *   ctx.workTypes … 業務内容の候補  ctx.clientsFor(案件名) … 業態の候補
 *   ctx.draft … 直前までの内容（時刻の片方だけを直す場合に使う）
 * rest は、どの項目にも当てはまらなかった言葉。
 */
function parseVoiceText(raw, ctx) {
  const today = ctx.today || new Date();
  const fields = {};
  const labels = [];
  let s = ' ' + voiceNormalize(raw) + ' ';
  const take = (re) => {
    const m = re.exec(s);
    if (!m) return null;
    s = s.slice(0, m.index) + ' ' + s.slice(m.index + m[0].length);
    return m;
  };

  // 1) 「タイトルは〇〇」「メモは〇〇」など、項目名つきの言い方
  VOICE_KEYS.forEach(([key, kw]) => {
    const re = new RegExp(`(?:${kw})\\s*(?:は|を|が|:)?\\s*[「『"“]?([^「」『』"”、。,]+?)[」』"”]?(?=[、。,]|$|\\s|(?:${VOICE_STOP}))`, 'u');
    // 「自社案件」の「案件」のように、項目名のあとに値が続かないものは、取り除かずに残す
    const m = re.exec(s);
    if (!m) return;
    const v = voiceTrimValue(m[1]);
    if (!v) return;
    s = s.slice(0, m.index) + ' ' + s.slice(m.index + m[0].length);
    fields[key] = v;
  });
  // 項目名つきで言われた案件名・業務内容・業態は、登録済みの名前に合わせる（点検保守 → 点検・保守）
  [['project', ctx.projects], ['workType', ctx.workTypes], ['client', ctx.clientsFor ? ctx.clientsFor(fields.project || (ctx.draft && ctx.draft.project) || '') : []]].forEach(([k, names]) => {
    if (!fields[k]) return;
    const hit = voiceFindName(fields[k], names);
    if (hit) fields[k] = hit.name;
  });

  // 2) 案件名（登録済みの案件から探す）
  if (!fields.project) {
    const hit = voiceFindName(s, ctx.projects);
    if (hit) { fields.project = hit.name; take(hit.re); }
  }
  const projectName = fields.project || (ctx.draft && ctx.draft.project) || '';

  // 3) 区分（確定／仮出勤）
  if (take(/仮出勤|仮置き|仮押さえ|仮予定|仮登録|仮で|仮に|仮の|仮$/u)) fields.status = 'tentative';
  else if (take(/確定(?!日)(?:で|に|の)?/u)) fields.status = 'confirmed';

  // 4) 業務内容・業態（登録済みの候補から探す）
  if (!fields.workType) {
    const hit = voiceFindName(s, ctx.workTypes);
    if (hit) { fields.workType = hit.name; take(hit.re); }
  }
  if (!fields.client && ctx.clientsFor) {
    const hit = voiceFindName(s, ctx.clientsFor(projectName));
    if (hit) { fields.client = hit.name; take(hit.re); }
  }

  // 5) 日付
  const mk = (y, mo, d) => {
    const dt = new Date(y, mo - 1, d);
    return dt.getFullYear() === y && dt.getMonth() === mo - 1 && dt.getDate() === d ? dt : null;
  };
  const t0 = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  let date = null;
  let m = take(/(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日/);
  if (m) date = mk(+m[1], +m[2], +m[3]);
  if (!date && (m = take(/(来月|再来月|今月)の?\s*(\d{1,2})日/))) {
    const off = m[1] === '来月' ? 1 : m[1] === '再来月' ? 2 : 0;
    const base = new Date(t0.getFullYear(), t0.getMonth() + off, 1);
    date = mk(base.getFullYear(), base.getMonth() + 1, +m[2]);
  }
  if (!date && (m = take(/(\d{1,2})月\s*(\d{1,2})日/))) {
    date = mk(t0.getFullYear(), +m[1], +m[2]);
    if (date && date < t0) date = mk(t0.getFullYear() + 1, +m[1], +m[2]);
  }
  if (!date && (m = take(/(?<![\d:])(\d{1,2})\/(\d{1,2})(?![\d:])/))) {
    date = mk(t0.getFullYear(), +m[1], +m[2]);
    if (date && date < t0) date = mk(t0.getFullYear() + 1, +m[1], +m[2]);
  }
  if (!date && (m = take(/本日|今日|きょう/))) date = t0;
  if (!date && (m = take(/明後日|あさって/))) date = addDays(t0, 2);
  if (!date && (m = take(/明日|あした/))) date = addDays(t0, 1);
  if (!date && (m = take(/(今週|来週|再来週)?の?\s*([日月火水木金土])曜(?:日)?/))) {
    const w = '日月火水木金土'.indexOf(m[2]);
    if (m[1]) {
      const monday = addDays(t0, -((t0.getDay() + 6) % 7));
      const weeks = m[1] === '今週' ? 0 : m[1] === '来週' ? 1 : 2;
      date = addDays(monday, weeks * 7 + ((w + 6) % 7));
    } else {
      date = addDays(t0, ((w - t0.getDay() + 6) % 7) + 1);   // 次にくるその曜日
    }
  }
  if (!date && (m = take(/(?<![\d月])(\d{1,2})日(?!間)/))) {
    date = mk(t0.getFullYear(), t0.getMonth() + 1, +m[1]);
    if (date && date < t0) date = mk(t0.getFullYear(), t0.getMonth() + 2, +m[1]);
  }
  if (date) fields.date = toKey(date);

  // 6) 時間
  const CLK = '(午前|午後|朝|夕方|夜)?(\\d{1,2})(?::(\\d{2})|時(?!間)(?:(\\d{1,2})分|(半))?)';
  const SEP = '\\s*(?:から|より|〜|~|-|ー|→)\\s*';
  const parts = (mm, i) => voiceClock(mm[i], mm[i + 1], mm[i + 2] || mm[i + 3], !!mm[i + 4]);
  if (take(/終日|一日中|1日中/)) {
    fields.allDay = true;
  } else if ((m = take(new RegExp(CLK + SEP + CLK + '(?:まで|に)?')))) {
    const a = parts(m, 1);
    const b = parts(m, 6);
    if (a && b) {
      // 終わりの時刻に午前・午後の指定がなく、始まりより前になるときは午後とみなす
      if (!b.explicit && (b.hour * 60 + b.min) <= (a.hour * 60 + a.min) && b.hour < 12) b.hour += 12;
      fields.start = voiceTimeStr(a);
      fields.end = voiceTimeStr(b);
    }
  } else if ((m = take(new RegExp(CLK + '(?:から|より)?\\s*(\\d+(?:\\.\\d+)?)時間(半)?')))) {
    const a = parts(m, 1);
    if (a) {
      const dur = Math.round((parseFloat(m[6]) + (m[7] ? 0.5 : 0)) * 60);
      fields.start = voiceTimeStr(a);
      fields.end = voiceTimeStr({ hour: 0, min: a.hour * 60 + a.min + dur });
    }
  } else {
    if ((m = take(new RegExp(CLK + '\\s*(?:まで|終了|終わり|上がり|あがり)')))) {
      const b = parts(m, 1);
      if (b) {
        const st = (fields.start || (ctx.draft && ctx.draft.start) || '');
        if (!b.explicit && st && (b.hour * 60 + b.min) <= toMinutes(st) && b.hour < 12) b.hour += 12;
        fields.end = voiceTimeStr(b);
      }
    }
    if ((m = take(new RegExp(CLK + '(?:\\s*(?:から|より|〜|~|開始|スタート|入り))?')))) {
      const a = parts(m, 1);
      if (a) fields.start = voiceTimeStr(a);
    }
  }
  // 時刻の指定がないときは、時間帯の言葉（午前・午後・夜間など）から決める
  if (!fields.allDay && !fields.start && !fields.end) {
    const slotWords = [
      [/早朝/, '05:00', '08:00'], [/夜間|夜/, '18:00', '23:00'], [/午前|朝|AM/i, '09:00', '12:00'],
      [/午後|PM/i, '12:00', '18:00'], [/(?<![A-Za-z0-9])P1(?![A-Za-z0-9])/i, '12:00', '15:00'],
      [/(?<![A-Za-z0-9])P2(?![A-Za-z0-9])/i, '15:00', '18:00'],
    ];
    for (let i = 0; i < slotWords.length; i++) {
      if (take(slotWords[i][0])) { fields.start = slotWords[i][1]; fields.end = slotWords[i][2]; break; }
    }
  }

  // 7) 店舗名（「〇〇店」）
  if (!fields.storeName && (m = take(/([^\s、。,「」]{1,12}店)(?:で|に|の|へ|を)?/u))) fields.storeName = m[1];

  // 8) 残った言葉から、つなぎの言葉を除く
  const rest = s
    .replace(/スケジュール|予定|登録|入力|追加|お願い(?:します|できますか)?|してください|して|入れて|作業|ください|です|ます|あります/gu, ' ')
    .replace(/[、。,.\s]+/g, ' ')
    .replace(/(^| )(?:の|で|に|を|は|が|と|から|まで|中|ごろ|頃|くらい|ぐらい)(?= |$)/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return { fields: fields, rest: rest };
}

/** 直前までの内容に、新しく読み取れた項目を重ねる（時刻は片方だけの修正にも対応） */
function mergeVoiceDraft(draft, f) {
  const d = Object.assign({}, draft);
  ['date', 'project', 'workType', 'status', 'subject', 'storeName', 'client', 'address'].forEach((k) => {
    if (f[k] !== undefined) d[k] = f[k];
  });
  if (f.note !== undefined) d.note = f.note;
  if (f.allDay) { d.allDay = true; delete d.start; delete d.end; }
  if (f.start !== undefined || f.end !== undefined) {
    d.allDay = false;
    const oldS = toMinutes(draft.start);
    const oldE = toMinutes(draft.end);
    if (f.start !== undefined) {
      d.start = f.start;
      if (f.end !== undefined) d.end = f.end;
      else if (oldS !== null && oldE !== null && oldE > oldS) {
        // 開始だけ直したときは、これまでの長さを保って終了をずらす
        d.end = fromMinutes(Math.min(23 * 60 + 59, toMinutes(f.start) + (oldE - oldS)));
      }
    } else {
      d.end = f.end;
    }
  }
  return d;
}

/** 足りない項目（これがそろえば登録できる） */
function voiceMissing(d) {
  const out = [];
  if (!d.date) out.push('date');
  if (!d.allDay && !(d.start && d.end)) out.push(d.start ? 'end' : 'time');
  if (!d.project) out.push('project');
  if ((d.status || 'confirmed') === 'confirmed' && !d.workType) out.push('workType');
  return out;
}

const VOICE_LABELS = {
  date: '日付', time: '時間', end: '終了時間', project: '案件名', workType: '業務内容',
  status: '区分', subject: 'タイトル', storeName: '店舗名', client: '業態', address: '住所', note: 'メモ',
};

const voice = {
  open: false,
  draft: { status: 'confirmed' },
  msgs: [],
  rest: '',
  listening: false,
  recog: null,
};

function voiceSupported() {
  return !!(window.SpeechRecognition || window.webkitSpeechRecognition);
}

function voiceCtx() {
  const used = state.jobs.map((j) => (j.workType || '').trim()).filter(Boolean);
  return {
    today: new Date(),
    projects: state.settings.projects,
    workTypes: Array.from(new Set(WORK_TYPE_PRESETS.concat(used))),
    clientsFor: (p) => businessTypesForProject(p),
    draft: voice.draft,
  };
}

function voiceSummaryText(d) {
  const parts = [];
  if (d.date) parts.push(formatDate(d.date, 'long'));
  if (d.allDay) parts.push('終日');
  else if (d.start) parts.push(`${d.start}〜${d.end || '？'}`);
  if (d.project) parts.push(d.project);
  if (d.workType) parts.push(d.workType);
  return parts.join(' ／ ');
}

/** 話した（入力した）1回分を受け取り、解析して、返事を作る */
function voiceSubmit(text) {
  const t = voiceNormalize(text);
  if (!t) return;
  voice.msgs.push({ role: 'user', text: t });
  const { fields, rest } = parseVoiceText(t, voiceCtx());
  const keys = Object.keys(fields);
  if (!keys.length) {
    voice.msgs.push({ role: 'bot', text: '日付・時間・案件名などを聞き取れませんでした。例：「明日の13時から15時でOES入替、点検・保守」のように話してみてください。' });
    voice.rest = '';
    renderVoice();
    return;
  }
  voice.draft = mergeVoiceDraft(voice.draft, fields);
  voice.rest = rest;
  const got = [];
  if (fields.date) got.push('日付');
  if (fields.allDay || fields.start !== undefined || fields.end !== undefined) got.push('時間');
  ['project', 'workType', 'status', 'subject', 'storeName', 'client', 'address', 'note'].forEach((k) => {
    if (fields[k] !== undefined) got.push(VOICE_LABELS[k]);
  });
  const miss = voiceMissing(voice.draft);
  let reply = `${got.join('・')}を反映しました。`;
  if (miss.length) {
    reply += `あとは「${miss.map((k) => VOICE_LABELS[k]).join('・')}」です。下のボタンで選ぶか、続けて話してください。`;
  } else {
    reply += 'これで入力画面を開けます。直したいところは続けて話してください（例：「15時までにして」「仮出勤にして」）。';
  }
  if (rest) reply += `（「${rest}」は読み取れませんでした）`;
  voice.msgs.push({ role: 'bot', text: reply });
  renderVoice();
}

function voiceChipHtml(label, attrs) {
  return `<button type="button" class="sc-voice-chip" ${attrs}>${escapeHtml(label)}</button>`;
}

function renderVoice() {
  const chat = $('voiceChat');
  if (!chat) return;
  const d = voice.draft;
  chat.innerHTML = voice.msgs.map((m) =>
    `<div class="sc-voice-msg sc-voice-${m.role}">${escapeHtml(m.text)}</div>`).join('');
  chat.scrollTop = chat.scrollHeight;

  // いまの入力内容（足りない項目は目立たせる）
  const miss = voiceMissing(d);
  const row = (label, val, missing) => `<div class="sc-voice-row${missing ? ' sc-voice-row-miss' : ''}">`
    + `<span>${escapeHtml(label)}</span><strong>${val ? escapeHtml(val) : '未入力'}</strong></div>`;
  const timeVal = d.allDay ? '終日' : d.start ? `${d.start}〜${d.end || '？'}` : '';
  const rows = [
    row('日付', d.date ? formatDate(d.date, 'long') : '', miss.includes('date')),
    row('時間', timeVal, miss.includes('time') || miss.includes('end')),
    row('案件名', d.project || '', miss.includes('project')),
    row('業務内容', d.workType || '', miss.includes('workType')),
    row('区分', (d.status || 'confirmed') === 'tentative' ? '仮出勤' : '確定', false),
  ];
  [['subject', 'タイトル'], ['storeName', '店舗名'], ['client', '業態'], ['address', '住所'], ['note', 'メモ']].forEach(([k, label]) => {
    if (d[k]) rows.push(row(label, d[k], false));
  });
  $('voiceSummary').innerHTML = rows.join('');

  // 足りない項目は、タップで選べるようにする
  const chips = [];
  if (miss.includes('date')) {
    chips.push('<span class="sc-voice-chips-label">日付</span>');
    [['今日', 0], ['明日', 1], ['明後日', 2]].forEach(([l, n]) => {
      chips.push(voiceChipHtml(l, `data-voice-date="${toKey(addDays(new Date(), n))}"`));
    });
  }
  if (miss.includes('time')) {
    chips.push('<span class="sc-voice-chips-label">時間</span>');
    SLOTS.forEach((sl) => chips.push(voiceChipHtml(`${sl.name} ${shortTime(sl.start)}〜${shortTime(sl.end)}`, `data-voice-time="${sl.start}|${sl.end}"`)));
    chips.push(voiceChipHtml('終日', 'data-voice-time="allday"'));
  }
  if (miss.includes('end') && d.start) {
    chips.push('<span class="sc-voice-chips-label">長さ</span>');
    [1, 2, 3, 4, 6, 8].forEach((h) => chips.push(voiceChipHtml(`${h}時間`, `data-voice-len="${h}"`)));
  }
  if (miss.includes('project')) {
    chips.push('<span class="sc-voice-chips-label">案件名</span>');
    state.settings.projects.forEach((p) => chips.push(voiceChipHtml(p, `data-voice-project="${escapeHtml(p)}"`)));
  }
  if (miss.includes('workType')) {
    chips.push('<span class="sc-voice-chips-label">業務内容</span>');
    voiceCtx().workTypes.slice(0, 10).forEach((w) => chips.push(voiceChipHtml(w, `data-voice-work="${escapeHtml(w)}"`)));
  }
  chips.push('<span class="sc-voice-chips-label">区分</span>');
  chips.push(voiceChipHtml('確定', `data-voice-status="confirmed" ${(d.status || 'confirmed') === 'confirmed' ? 'aria-pressed="true"' : ''}`));
  chips.push(voiceChipHtml('仮出勤', `data-voice-status="tentative" ${d.status === 'tentative' ? 'aria-pressed="true"' : ''}`));
  $('voiceChips').innerHTML = chips.join('');

  const hasAny = !!(d.date || d.start || d.allDay || d.project || d.workType);
  const apply = $('voiceApply');
  apply.disabled = !hasAny;
  apply.textContent = miss.length ? '入力画面を開く' : '✓ この内容で入力画面を開く';

  const mic = $('voiceMic');
  mic.hidden = !voiceSupported();
  mic.classList.toggle('sc-voice-mic-on', voice.listening);
  mic.setAttribute('aria-label', voice.listening ? '聞き取りを止める' : '話して入力する');
  $('voiceNoMic').hidden = voiceSupported();
}

function openVoice() {
  if (voice.open) return;
  voice.open = true;
  voice.draft = { status: 'confirmed' };
  voice.rest = '';
  voice.msgs = [{ role: 'bot', text: '登録したい予定を話してください。例：「10月27日の13時から15時でOES入替作業で登録」。足りない項目はここで確認します。' }];
  $('voiceText').value = '';
  $('voiceOverlay').hidden = false;
  document.body.classList.add('sc-modal-open-body');
  renderVoice();
  syncHistoryLayers();
}

function closeVoice(skipHistory) {
  if (!voice.open) return;
  stopVoiceListening();
  voice.open = false;
  $('voiceOverlay').hidden = true;
  const sheet = elSideAside && elSideAside.classList.contains('sc-side-modal-open');
  if (!sheet) document.body.classList.remove('sc-modal-open-body');
  if (!skipHistory) syncHistoryLayers();
}

function stopVoiceListening() {
  if (voice.recog) { try { voice.recog.stop(); } catch (err) { /* すでに止まっている */ } }
  voice.listening = false;
}

function toggleVoiceListening() {
  if (voice.listening) { stopVoiceListening(); renderVoice(); return; }
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return;
  const rec = new SR();
  rec.lang = 'ja-JP';
  rec.interimResults = true;
  rec.continuous = false;
  rec.maxAlternatives = 1;
  const box = $('voiceText');
  rec.onresult = (ev) => {
    let interim = '';
    let final = '';
    for (let i = ev.resultIndex; i < ev.results.length; i++) {
      const tr = ev.results[i][0].transcript;
      if (ev.results[i].isFinal) final += tr; else interim += tr;
    }
    box.value = final || interim;
    if (final) { box.value = ''; voiceSubmit(final); }
  };
  rec.onerror = (ev) => {
    voice.listening = false;
    const e = ev && ev.error;
    let msg = '音声を聞き取れませんでした。もう一度お試しください。';
    if (e === 'not-allowed' || e === 'service-not-allowed') msg = 'マイクの使用が許可されていません。ブラウザの設定でマイクを許可するか、下の入力欄に文字で入力してください（キーボードのマイクも使えます）。';
    else if (e === 'no-speech') msg = '声が聞こえませんでした。マイクのボタンを押してから話してください。';
    else if (e === 'network') msg = '音声認識に接続できませんでした。通信を確認するか、文字で入力してください。';
    else if (e === 'aborted') { renderVoice(); return; }
    voice.msgs.push({ role: 'bot', text: msg });
    renderVoice();
  };
  rec.onend = () => { voice.listening = false; renderVoice(); };
  voice.recog = rec;
  try {
    rec.start();
    voice.listening = true;
  } catch (err) {
    voice.listening = false;
  }
  renderVoice();
}

/** 聞き取った内容を、予定の入力画面（日付の選択つき）に反映して開く */
function applyVoiceDraft() {
  const d = voice.draft;
  const f = blankForm();
  const date = d.date || (view.selected && view.selected >= todayKey() ? view.selected : todayKey());
  f.date = date;
  f.status = d.status || 'confirmed';
  if (d.allDay) f.allDay = true;
  else if (d.start) { f.start = d.start; f.end = d.end || fromMinutes(Math.min(23 * 60 + 59, toMinutes(d.start) + 60)); }
  if (d.project) {
    if (state.settings.projects.includes(d.project)) f.titleSel = d.project;
    else { f.titleSel = PROJECT_FREE; f.titleFree = d.project; }
  }
  f.workType = d.workType || '';
  f.subject = d.subject || '';
  f.storeName = d.storeName || '';
  f.address = d.address || '';
  f.note = d.note || '';
  if (d.client) {
    if (businessTypesForProject(d.project).includes(d.client)) f.clientSel = d.client;
    else { f.clientSel = CLIENT_FREE; f.clientFree = d.client; }
  }
  // 画面を閉じる処理で履歴が動かないよう、ここでは履歴の同期は後の入力画面の表示に任せる
  closeVoice(true);
  if (view.paint === 'multi') setPaintMode(null, { noRender: true });
  selectDate(date, { openForm: true, popup: true });
  view.form = f;
  view.ack = false;
  renderSidePanel();
  applyClientOverrideNote();
  updateFormAlert();
  syncHistoryLayers();
  if (isNarrow()) scrollToDay(date, false);
  toast('反映しました。確認して「登録する」を押してください');
}

function bindVoice() {
  $('fabVoice').addEventListener('click', openVoice);
  const pill = $('voiceBtn');
  if (pill) pill.addEventListener('click', openVoice);
  $('voiceClose').addEventListener('click', () => closeVoice());
  $('voiceOverlay').addEventListener('click', (ev) => {
    if (ev.target === $('voiceOverlay')) closeVoice();
  });
  $('voiceMic').addEventListener('click', toggleVoiceListening);
  const send = () => {
    const box = $('voiceText');
    const t = box.value;
    box.value = '';
    box.style.height = '';
    voiceSubmit(t);
  };
  $('voiceSend').addEventListener('click', send);
  $('voiceText').addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) { ev.preventDefault(); send(); }
  });
  $('voiceReset').addEventListener('click', () => {
    voice.draft = { status: 'confirmed' };
    voice.rest = '';
    voice.msgs.push({ role: 'bot', text: '内容をクリアしました。もう一度話してください。' });
    renderVoice();
  });
  $('voiceApply').addEventListener('click', applyVoiceDraft);
  $('voiceChips').addEventListener('click', (ev) => {
    const b = ev.target.closest('.sc-voice-chip');
    if (!b) return;
    const d = voice.draft;
    if (b.dataset.voiceDate) d.date = b.dataset.voiceDate;
    else if (b.dataset.voiceTime) {
      if (b.dataset.voiceTime === 'allday') { d.allDay = true; delete d.start; delete d.end; }
      else { const [s, e] = b.dataset.voiceTime.split('|'); d.allDay = false; d.start = s; d.end = e; }
    } else if (b.dataset.voiceLen) d.end = fromMinutes(Math.min(23 * 60 + 59, toMinutes(d.start) + Number(b.dataset.voiceLen) * 60));
    else if (b.dataset.voiceProject) d.project = b.dataset.voiceProject;
    else if (b.dataset.voiceWork) d.workType = b.dataset.voiceWork;
    else if (b.dataset.voiceStatus) d.status = b.dataset.voiceStatus;
    const miss = voiceMissing(d);
    if (!miss.length) voice.msgs.push({ role: 'bot', text: `${voiceSummaryText(d)}。これで入力画面を開けます。` });
    renderVoice();
  });
}

/* ------------------------------------------------------------
   スマホ向けの画面まわり（PWAとしてスマホで使うことを主に想定）
   ------------------------------------------------------------ */

/**
 * 操作モードのボタンを、スマホでは月の切り替えの下（上部に貼り付く帯）に移し、
 * パソコンでは右のパネルの上に戻す。貼り付く帯の高さを測って、時間帯の見出し行をその下に貼り付ける。
 */
function placeToolbar() {
  const toolbar = document.querySelector('.sc-side-toolbar');
  const monthnav = document.querySelector('.sc-monthnav');
  if (!toolbar || !monthnav || !elSideAside) return;
  if (isNarrow()) {
    if (toolbar.parentElement !== monthnav) monthnav.appendChild(toolbar);
  } else if (toolbar.parentElement !== elSideAside) {
    elSideAside.insertBefore(toolbar, elSideAside.firstChild);
  }
  measureSticky();
}

function measureSticky() {
  const header = document.querySelector('.sc-header');
  const monthnav = document.querySelector('.sc-monthnav');
  if (!header || !monthnav) return;
  const root = document.documentElement.style;
  const headH = Math.round(header.getBoundingClientRect().height);
  const navH = Math.round(monthnav.getBoundingClientRect().height);
  root.setProperty('--sc-head-h', headH + 'px');
  root.setProperty('--sc-stick-top', (headH + navH) + 'px');
}

/** その日の行が、上に貼り付いている帯と見出し行のすぐ下に来るようにスクロールする */
function scrollToDay(key, smooth) {
  const row = elCalendar.querySelector(`[data-role="date"][data-date="${key}"]`);
  if (!row) return;
  const stick = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--sc-stick-top'), 10) || 0;
  const head = elCalendar.querySelector('.sc-vhead');
  const headH = head ? head.getBoundingClientRect().height : 0;
  const top = row.getBoundingClientRect().top + window.scrollY - stick - headH - 6;
  window.scrollTo({ top: Math.max(0, top), behavior: smooth ? 'smooth' : 'auto' });
}

/** 今日が表示中の月にあれば、今日の行まで移動する（月の初めのうちは先頭のままにする） */
function scrollToTodayIfNeeded(smooth) {
  const t = todayKey();
  const d = fromKey(t);
  if (d.getFullYear() !== view.year || d.getMonth() !== view.month) return;
  if (d.getDate() <= 3) { scrollToCalendarTop(smooth); return; }
  scrollToDay(t, smooth);
}

function scrollToCalendarTop(smooth) {
  const main = document.querySelector('.sc-main');
  if (!main) return;
  const stick = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--sc-stick-top'), 10) || 0;
  const top = main.getBoundingClientRect().top + window.scrollY - stick;
  if (window.scrollY > top) window.scrollTo({ top: Math.max(0, top), behavior: smooth ? 'smooth' : 'auto' });
}

/** 月を切り替えたときに、左右どちらから来たか分かるよう少しだけ動かす */
function animateMonthChange(delta) {
  elCalendar.classList.remove('sc-cal-from-left', 'sc-cal-from-right');
  void elCalendar.offsetWidth;   // アニメーションをやり直すため
  elCalendar.classList.add(delta > 0 ? 'sc-cal-from-right' : 'sc-cal-from-left');
}

/**
 * Androidの「戻る」ボタン・ジェスチャーで、開いているシートやまとめて選択を閉じられるようにする。
 * 開いている“層”の数だけ履歴を積んでおき、戻るで1つずつ閉じる（アプリが終了してしまわないように）。
 */
let historyPushed = 0;
let historyIgnorePops = 0;
function syncHistoryLayers() {
  if (!window.history || !history.pushState) return;
  const sheetOpen = elSideAside && elSideAside.classList.contains('sc-side-modal-open');
  const want = (view.paint === 'multi' && isNarrow() ? 1 : 0) + (sheetOpen ? 1 : 0) + (voice.open ? 1 : 0);
  while (historyPushed < want) {
    history.pushState({ vcLayer: historyPushed + 1 }, '');
    historyPushed++;
  }
  if (historyPushed > want) {
    const n = historyPushed - want;
    historyPushed = want;
    historyIgnorePops++;
    history.go(-n);
  }
}

function onHistoryPop() {
  if (historyIgnorePops > 0) { historyIgnorePops--; return; }
  if (historyPushed > 0) historyPushed--;
  if (!elConfirmOverlay.hidden) {
    closeCustomConfirm(false);
    syncHistoryLayers();
    return;
  }
  if (voice.open) { closeVoice(true); return; }
  if (elSideAside.classList.contains('sc-side-modal-open')) { closeForm(); return; }
  if (view.paint === 'multi') { setPaintMode(null); return; }
}

/** スマホの「＋」ボタン：選んでいる日（なければ今日）に予定を追加する */
function openQuickAdd() {
  const today = todayKey();
  const base = view.selected && view.selected >= today ? view.selected : today;
  selectDate(base, { openForm: true, popup: true });
}

/** シートを下に引っぱって閉じる（いちばん上までスクロールしている状態のときだけ） */
function bindSheetSwipe() {
  let startY = 0;
  let dragging = false;
  let tracking = false;
  let dy = 0;
  elSidePanel.addEventListener('touchstart', (ev) => {
    if (!elSideAside.classList.contains('sc-side-modal-open') || ev.touches.length !== 1) return;
    if (ev.target.closest('input, textarea, select')) return;
    tracking = elSidePanel.scrollTop <= 0;
    dragging = false;
    startY = ev.touches[0].clientY;
    dy = 0;
  }, { passive: true });
  elSidePanel.addEventListener('touchmove', (ev) => {
    if (!tracking) return;
    dy = ev.touches[0].clientY - startY;
    if (!dragging) {
      if (dy > 8 && elSidePanel.scrollTop <= 0) dragging = true;
      else if (dy < -4) { tracking = false; return; }
      else return;
    }
    ev.preventDefault();
    elSidePanel.style.transition = 'none';
    elSidePanel.style.transform = `translateY(${Math.max(0, dy)}px)`;
  }, { passive: false });
  const end = () => {
    if (!tracking) return;
    tracking = false;
    if (!dragging) return;
    dragging = false;
    elSidePanel.style.transition = 'transform 0.18s ease-out';
    if (dy > 110) {
      elSidePanel.style.transform = 'translateY(100%)';
      setTimeout(() => {
        elSidePanel.style.transition = '';
        elSidePanel.style.transform = '';
        closeForm();
      }, 170);
    } else {
      elSidePanel.style.transform = '';
      setTimeout(() => { elSidePanel.style.transition = ''; }, 200);
    }
  };
  elSidePanel.addEventListener('touchend', end);
  elSidePanel.addEventListener('touchcancel', end);
}

/** カレンダーを左右にスワイプして月を切り替える */
function bindMonthSwipe() {
  let sx = 0, sy = 0, st = 0, valid = false;
  elCalendar.addEventListener('touchstart', (ev) => {
    valid = ev.touches.length === 1;
    if (!valid) return;
    sx = ev.touches[0].clientX;
    sy = ev.touches[0].clientY;
    st = Date.now();
  }, { passive: true });
  elCalendar.addEventListener('touchmove', () => {
    if (longPressActive) valid = false;   // 長押しでなぞって選んでいるときは月を変えない
  }, { passive: true });
  elCalendar.addEventListener('touchend', (ev) => {
    if (!valid) return;
    valid = false;
    const t = ev.changedTouches[0];
    if (!t) return;
    const dx = t.clientX - sx;
    const dy = t.clientY - sy;
    if (Date.now() - st > 600 || Math.abs(dx) < 70 || Math.abs(dy) > 45 || Math.abs(dx) < Math.abs(dy) * 2) return;
    moveMonth(dx < 0 ? 1 : -1);
  }, { passive: true });
}

function clearLongPress() {
  clearTimeout(longPressTimer);
  longPressTimer = null;
}

function bindEvents() {
  $('prevMonth').addEventListener('click', () => moveMonth(-1));
  $('nextMonth').addEventListener('click', () => moveMonth(1));
  $('todayBtn').addEventListener('click', () => {
    const d = new Date();
    view.year = d.getFullYear();
    view.month = d.getMonth();
    renderAll();
    scrollToTodayIfNeeded(true);
  });

  // スマホ向け：戻るボタン・＋ボタン・シートを引いて閉じる・左右スワイプで月送り
  window.addEventListener('popstate', onHistoryPop);
  $('fabAdd').addEventListener('click', openQuickAdd);
  bindVoice();
  bindSheetSwipe();
  bindMonthSwipe();
  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { placeToolbar(); updateSideModalState(); }, 120);
  });
  if (window.ResizeObserver) {
    const ro = new ResizeObserver(() => measureSticky());
    ro.observe(document.querySelector('.sc-header'));
    ro.observe(document.querySelector('.sc-monthnav'));
  }
  // シートの中の入力欄を触ったとき、キーボードに隠れないよう見える位置まで送る
  elSidePanel.addEventListener('focusin', (ev) => {
    if (!isNarrow() || !ev.target.matches('input, textarea, select')) return;
    setTimeout(() => {
      try { ev.target.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (err) { /* 古い端末は無視 */ }
    }, 300);
  });
  elMonthPicker.addEventListener('change', () => {
    const v = elMonthPicker.value;
    if (!/^\d{4}-\d{2}$/.test(v)) return;
    view.year = Number(v.slice(0, 4));
    view.month = Number(v.slice(5, 7)) - 1;
    renderAll();
  });

  // 操作方法の説明は初期状態では畳んでおき、「？」で必要なときだけ開く
  const hintToggle = $('paintHintToggle');
  if (hintToggle) {
    hintToggle.addEventListener('click', () => {
      const hint = $('paintHint');
      const open = hint.hidden;
      hint.hidden = !open;
      hintToggle.setAttribute('aria-expanded', String(open));
    });
  }

  // まとめて入力モード
  document.querySelectorAll('.sc-paint-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      const v = tab.dataset.paint;
      setPaintMode(v === 'off-mode' ? null : v);
    });
  });

  // カレンダー：日付ラベル＝希望の設定、枠＝予定の登録
  elCalendar.addEventListener('pointerdown', (ev) => {
    const dateBtn = ev.target.closest('[data-role="date"]');
    const cell = ev.target.closest('[data-pick]');
    if (!dateBtn && !cell) return;
    if (ev.button !== undefined && ev.button > 0) return;   // 右クリックなどは対象外

    // スマホでのスクロール操作との誤操作防止用に、押した位置を覚えておく
    paintPointerId = ev.pointerId;
    paintIsTouch = ev.pointerType === 'touch' || ev.pointerType === 'pen';
    paintStartX = ev.clientX;
    paintStartY = ev.clientY;
    longPressActive = false;
    clearLongPress();

    // 希望の塗りは日付ラベルの列だけ。
    // ここではまだ反映しない（1回押しただけで休みになってしまう誤操作を防ぐため）。
    // ドラッグして2日目に触れた時点でまとめて反映する。1日だけなら endPaint で確認する。
    if (dateBtn && view.paint && view.paint !== 'multi') {
      painting = true;
      paintTouched.clear();
      paintTouched.add(dateBtn.dataset.date);
      return;
    }

    if (!cell) return;

    // パソコンでShift／Ctrl／⌘を押しながらクリックすると、そのまま複数選択に入る
    if (!view.paint && !paintIsTouch && (ev.shiftKey || ev.ctrlKey || ev.metaKey)) {
      switchToMulti();
      view.multi.add(cell.dataset.pick);
      renderAll();
      return;
    }

    painting = true;
    paintTouched.clear();
    paintTouched.add(cell.dataset.pick);

    if (view.paint === 'multi') {
      paintAdd = !view.multi.has(cell.dataset.pick);
      // マウスは押した瞬間に切り替える（そのままドラッグで連続選択）。
      // 指の場合はスクロールかもしれないので、離したときに切り替える（endPaint）。
      if (!paintIsTouch) setMultiPick(cell.dataset.pick, paintAdd);
    } else {
      paintAdd = true;
    }

    // 指で長押しすると、その枠から複数選択を始める（指を離さずになぞると続けて選べる）
    if (paintIsTouch) {
      const pick = cell.dataset.pick;
      longPressTimer = setTimeout(() => {
        longPressTimer = null;
        if (!painting) return;
        longPressActive = true;
        // 指を置いたままの枠の要素を作り直すと、そのあとのなぞり操作が届かなくなるため、
        // カレンダー全体は描き直さずに、その枠の見た目とパネルだけを更新する
        if (view.paint !== 'multi') switchToMulti();
        setMultiPick(pick, true);
        paintAdd = true;
        if (navigator.vibrate) { try { navigator.vibrate(15); } catch (err) { /* 振動できなくてもよい */ } }
      }, LONG_PRESS_MS);
    }
  });

  elCalendar.addEventListener('pointerover', (ev) => {
    if (!painting || paintIsTouch) return;

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
      setMultiPick(cell.dataset.pick, paintAdd);
    } else {
      // 選択のみでドラッグしたときは、その場で複数選択に切り替える
      switchToMulti();
      paintTouched.forEach((pk) => setMultiPick(pk, true));
    }
  });

  // 長押しが成立したあとは、指でなぞった枠を続けて選ぶ（画面のスクロールは止める）
  const pickAt = (x, y) => {
    const el = document.elementFromPoint(x, y);
    const cell = el && el.closest ? el.closest('[data-pick]') : null;
    if (!cell || paintTouched.has(cell.dataset.pick)) return;
    paintTouched.add(cell.dataset.pick);
    setMultiPick(cell.dataset.pick, paintAdd);
  };
  // 指が画面の上端・下端に近づいたら、自動でスクロールして続きの日も選べるようにする
  let dragPoint = null;
  let dragScrollFrame = 0;
  const dragScrollSpeed = () => {
    if (!dragPoint) return 0;
    const stick = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--sc-stick-top'), 10) || 0;
    const bar = $('multiBar');
    const bottomLimit = window.innerHeight - (bar && !bar.hidden ? bar.getBoundingClientRect().height : 0);
    // 見出し行の分を含めた上端、操作バーを除いた下端の、ごく端に指が来たときだけ動かす
    const topEdge = stick + 40 + 28;
    const bottomEdge = bottomLimit - 28;
    if (dragPoint.y > bottomEdge) return Math.min(12, 2 + (dragPoint.y - bottomEdge) / 3);
    if (dragPoint.y < topEdge) return -Math.min(12, 2 + (topEdge - dragPoint.y) / 3);
    return 0;
  };
  const dragScrollLoop = () => {
    dragScrollFrame = 0;
    if (!longPressActive || !painting || !dragPoint) return;
    const v = dragScrollSpeed();
    if (!v) return;
    window.scrollBy(0, v);
    pickAt(dragPoint.x, dragPoint.y);
    dragScrollFrame = requestAnimationFrame(dragScrollLoop);
  };
  elCalendar.addEventListener('touchmove', (ev) => {
    if (!longPressActive || !painting) return;
    ev.preventDefault();
    const t = ev.touches[0];
    if (!t) return;
    dragPoint = { x: t.clientX, y: t.clientY };
    pickAt(t.clientX, t.clientY);
    if (!dragScrollFrame && dragScrollSpeed()) dragScrollFrame = requestAnimationFrame(dragScrollLoop);
  }, { passive: false });

  // 長押しで出る文字選択・メニューを出さない
  elCalendar.addEventListener('contextmenu', (ev) => {
    if (ev.target.closest('[data-pick]') && (paintIsTouch || longPressActive)) ev.preventDefault();
  });

  const endPaint = (ev) => {
    clearLongPress();
    if (!painting) return;
    painting = false;
    const wasLongPress = longPressActive;
    longPressActive = false;
    const isCancel = ev && ev.type === 'pointercancel';
    // 指を離したこの瞬間にシートや確認を開くと、ブラウザがこのあと出すクリックが
    // 開いたシートの中のボタン（削除など）に当たってしまう。指の操作ではそのクリックを捨てる
    if (paintIsTouch && !isCancel) swallowTouchEnd = true;

    if (view.paint === 'multi') {
      // 指で1枠だけタップした（長押し・なぞりではない）ときは、ここで選択を切り替える
      if (paintIsTouch && !wasLongPress && !isCancel && paintTouched.size === 1) {
        const pick = Array.from(paintTouched)[0];
        setMultiPick(pick, !view.multi.has(pick));
      }
      paintTouched.clear();
      return;
    }
    if (view.paint) {
      // 1日だけ押した（ドラッグしなかった）ときは、まだ何も変更していない。
      // 確認ダイアログでその場で確定できるようにする（誤タップ防止と、操作の軽さを両立させる）。
      if (paintTouched.size === 1) { if (!isCancel) confirmSingleDayPaint(Array.from(paintTouched)[0]); }
      else renderAll();   // ドラッグして複数日を触れた場合は pointerover 側ですでに反映済み
      paintTouched.clear();
      return;
    }
    // 選択のみ：1回タップするだけで開く。
    //   空いている枠 … すぐに登録画面
    //   予定がある枠 … その予定の内容（編集・確定・複製・削除のボタン付き）
    if (paintTouched.size === 1 && !isCancel) {
      const pick = Array.from(paintTouched)[0];
      const [d, sid] = pick.split('|');
      const openForm = !jobsInSlot(d, sid).length;
      selectDate(d, { slot: sid, openForm, popup: true });
      if (openForm && !isNarrow()) {
        // スマホは自動で入力欄に移るとキーボードや選択肢が急に出てしまうので、パソコンだけにする
        const el = $('fTitle');
        if (el) el.focus();
      }
    }
    paintTouched.clear();
  };
  window.addEventListener('pointerup', endPaint);
  window.addEventListener('pointercancel', endPaint);
  window.addEventListener('touchend', (ev) => {
    if (!swallowTouchEnd) return;
    swallowTouchEnd = false;
    if (ev.cancelable) ev.preventDefault();   // 続くマウス互換イベントとクリックを出さない
  }, { capture: true, passive: false });
  window.addEventListener('touchstart', () => { swallowTouchEnd = false; }, { capture: true, passive: true });

  // スマホでは、指を押した要素にそのままイベントが送られ続ける（他の枠に指が移っても
  // pointerover は飛ばない）ため、スクロールしようとして指が動いただけでも
  // 「1枠だけ押した」ことになり、そのままその枠が選ばれてしまう。
  // 一定以上指が動いたらスクロール操作とみなし、選択操作を中止する（長押しが成立したあとは除く）。
  window.addEventListener('pointermove', (ev) => {
    if (!painting || !paintIsTouch || longPressActive || ev.pointerId !== paintPointerId) return;
    const dx = ev.clientX - paintStartX;
    const dy = ev.clientY - paintStartY;
    if (Math.hypot(dx, dy) > SCROLL_CANCEL_PX) {
      painting = false;
      clearLongPress();
      paintTouched.clear();
    }
  }, { passive: true });

  // 念のため、画面が実際にスクロールされたときも同様に選択操作を中止する
  window.addEventListener('scroll', () => {
    if (!painting || longPressActive) return;
    painting = false;
    clearLongPress();
    paintTouched.clear();
  }, { passive: true });

  elCalendar.addEventListener('click', (ev) => {
    const dateBtn = ev.target.closest('[data-role="date"]');
    if (!dateBtn) return;
    const key = dateBtn.dataset.date;
    if (view.paint === 'multi') {
      // まとめて操作中に日付をタップすると、その日の枠をまとめて選ぶ（すべて選んであれば解除）
      const picks = SLOTS.map((sl) => key + '|' + sl.id);
      const allOn = picks.every((pk) => view.multi.has(pk));
      picks.forEach((pk) => setMultiPick(pk, !allOn));
      return;
    }
    if (view.paint) return;
    // 日付をタップ ＝ その日の内容（予定の一覧と希望の設定）を開く
    selectDate(key, { popup: true });
  });

  // スマホでシート表示中は、背景（枠外）をタップすると閉じる。
  // ただし開いた直後のタップ（ダブルタップの癖など）では閉じない
  elSideAside.addEventListener('click', (ev) => {
    if (ev.target !== elSideAside || !elSideAside.classList.contains('sc-side-modal-open')) return;
    if (Date.now() - view.sheetOpenedAt < SHEET_GUARD_MS) return;
    closeForm();
  });

  // スマホの「まとめて操作」バー
  $('multiBar').addEventListener('click', (ev) => {
    const btn = ev.target.closest('[data-multibar]');
    if (!btn) return;
    const act = btn.dataset.multibar;
    if (act === 'exit') { setPaintMode(null); return; }
    if (act === 'clear') {
      Array.from(view.multi).forEach((pk) => setMultiPick(pk, false));
      renderSidePanel();
      return;
    }
    view.multiTab = act === 'edit' ? 'edit' : 'add';
    view.popup = true;
    view.sheetOpenedAt = Date.now();
    renderSidePanel();
    elSidePanel.scrollTop = 0;
  });

  // サイドパネル内の操作（委譲）
  elSidePanel.addEventListener('click', (ev) => {
    const unpick = ev.target.closest('[data-unpick]');
    if (unpick) {
      setMultiPick(unpick.dataset.unpick, false);
      return;
    }

    if (ev.target.id === 'multiClear') {
      Array.from(view.multi).forEach((pk) => setMultiPick(pk, false));
      return;
    }

    // まとめて操作：新しく登録／予定を変更 の切り替え
    const multiTab = ev.target.closest('[data-multitab]');
    if (multiTab) {
      if (view.paint === 'multi' && $('jobForm')) view.form = readForm();
      if ($('bulkForm')) view.bulkForm = readBulkForm();
      view.multiTab = multiTab.dataset.multitab;
      renderSidePanel();
      return;
    }

    if (ev.target.id === 'bulkConfirmBtn') { bulkConfirmTentatives(); return; }
    if (ev.target.id === 'bulkDeleteBtn') { bulkDeleteSelected(); return; }

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
      const target = view.selected;
      const apply = () => { setWish(target, next); renderAll(); };
      if (next === WISH_OFF && jobsOn(target).length) {
        customConfirm('この日にはすでに予定が入っています。休み希望にしますか？').then((ok) => { if (ok) apply(); });
      } else {
        apply();
      }
      return;
    }

    if (ev.target.id === 'openForm') {
      view.formOpen = true;
      view.form = blankForm();
      renderSidePanel();
      elSidePanel.scrollTop = 0;
      const el = $('fTitle');
      if (el && !isNarrow()) el.focus();
      return;
    }

    if (ev.target.closest('[data-modal-close]')) { closeForm(); return; }

    const mapBtn = ev.target.closest('[data-map]');
    if (mapBtn) {
      const row = mapBtn.dataset.mapRow;
      const addrEl = row === 'primary' ? $('fAddress') : $('fExtraAddress-' + row);
      const loc = addrEl ? addrEl.value.trim() : '';
      if (!loc) { toast('住所を入力してください', true); if (addrEl) addrEl.focus(); return; }
      window.open(mapUrl(mapBtn.dataset.map, loc), '_blank', 'noopener');
      return;
    }

    if (ev.target.id === 'addVisitBtn') {
      view.form = readForm();
      view.form.extraAddresses = (view.form.extraAddresses || []).concat(['']);
      renderSidePanel();
      const idx = view.form.extraAddresses.length - 1;
      const el = $('fExtraAddress-' + idx);
      if (el) el.focus();
      return;
    }

    const removeVisitBtn = ev.target.closest('[data-remove-visit]');
    if (removeVisitBtn) {
      view.form = readForm();
      const idx = Number(removeVisitBtn.dataset.removeVisit);
      view.form.extraAddresses = (view.form.extraAddresses || []).filter((a, i) => i !== idx);
      renderSidePanel();
      return;
    }

    const confirmBtn = ev.target.closest('[data-confirm]');
    if (confirmBtn) { startConfirm(confirmBtn.dataset.confirm); return; }

    const editBtn = ev.target.closest('[data-edit]');
    if (editBtn) { startEdit(editBtn.dataset.edit); return; }

    const dupBtn = ev.target.closest('[data-dup]');
    if (dupBtn) { startDuplicate(dupBtn.dataset.dup); return; }

    const delBtn = ev.target.closest('[data-del]');
    if (delBtn) { deleteJob(delBtn.dataset.del); return; }

    if (ev.target.id === 'fCancel') { closeForm(); }
  });

  // 業務内容はすでに値が入っていると、その文字で始まる候補しか出せないブラウザの仕様上、
  // 別の候補に選び直せなくなる。触れた時点で一度空にして、候補を選ばずに離れたら元に戻す。
  let workTypeBeforeFocus = null;
  elSidePanel.addEventListener('focusin', (ev) => {
    if (ev.target.id !== 'fWorkType') return;
    workTypeBeforeFocus = ev.target.value;
    ev.target.value = '';
  });
  elSidePanel.addEventListener('focusout', (ev) => {
    if (ev.target.id !== 'fWorkType') return;
    if (!ev.target.value.trim() && workTypeBeforeFocus) ev.target.value = workTypeBeforeFocus;
    workTypeBeforeFocus = null;
    view.form = readForm();
    updateFormAlert();
  });

  elSidePanel.addEventListener('input', (ev) => {
    // 承知チェックは change 側で扱う（ここで警告欄を作り直すと反応が消えるため）
    if (ev.target.id === 'fAck') return;
    if (ev.target.closest('#bulkForm')) { view.bulkForm = readBulkForm(); return; }
    if (!ev.target.closest('#jobForm')) return;
    view.form = readForm();
    updateFormAlert();
    const m = /^(?:fAddress|fExtraAddress-(\d+))$/.exec(ev.target.id);
    if (m) {
      const rowKey = ev.target.id === 'fAddress' ? 'primary' : m[1];
      const hasAddr = !!ev.target.value.trim();
      document.querySelectorAll(`[data-map-row="${rowKey}"]`).forEach((btn) => { btn.hidden = !hasAddr; });
    }
  });

  elSidePanel.addEventListener('change', (ev) => {
    // まとめて変更：対象の予定のチェック
    const bulkPick = ev.target.closest('[data-bulk-pick]');
    if (bulkPick) {
      if (bulkPick.checked) view.bulkExclude.delete(bulkPick.dataset.bulkPick);
      else view.bulkExclude.add(bulkPick.dataset.bulkPick);
      view.bulkForm = readBulkForm();
      renderSidePanel();
      return;
    }
    if (ev.target.closest('#bulkForm')) {
      view.bulkForm = readBulkForm();
      if (ev.target.id === 'bTimeMode') {
        const row = $('bTimeRow');
        if (row) row.hidden = ev.target.value !== 'time';
      }
      return;
    }
    if (ev.target.id === 'fAck') {
      view.ack = ev.target.checked;
      const btn = $('fSubmit');
      if (btn) btn.disabled = !view.ack;
      return;
    }
    if (ev.target.id === 'fAllDay' || ev.target.id === 'fUseSlotTime') {
      view.form = readForm();
      const multi = view.paint === 'multi';
      const row = $('timeRow');
      if (row) row.hidden = view.form.allDay || (multi && view.form.useSlotTime);
      const slotRow = $('useSlotTimeRow');
      if (slotRow) slotRow.hidden = view.form.allDay;
      updateFormAlert();
      return;
    }
    // 「フリー」を選んだときだけ自由入力欄を出す
    if (ev.target.id === 'fTitle') {
      view.form = readForm();
      const row = $('titleFreeRow');
      if (row) row.hidden = ev.target.value !== PROJECT_FREE;
      if (ev.target.value === PROJECT_FREE) $('fTitleFree').focus();
      refreshClientOptions();
      view.form = readForm();
      updateFormAlert();
    }
    // 業態を選ぶと、その業態の説明文テンプレートをメモへ自動で入れる
    if (ev.target.id === 'fClient') {
      view.form = readForm();
      const row = $('clientFreeRow');
      if (row) row.hidden = ev.target.value !== CLIENT_FREE;
      if (ev.target.value === CLIENT_FREE) {
        $('fClientFree').focus();
      } else if (ev.target.value) {
        applyClientOverrideNote();
      }
      updateFormAlert();
    }
    // 店舗名・業務内容をあとから入力・変更したときも、テンプレートのメモへ反映し直す
    if (ev.target.id === 'fStoreName' || ev.target.id === 'fWorkType') {
      view.form = readForm();
      applyClientOverrideNote();
    }
  });

  elSidePanel.addEventListener('submit', (ev) => {
    if (ev.target.id === 'jobForm') submitJob(ev);
    if (ev.target.id === 'bulkForm') { ev.preventDefault(); applyBulkEdit(); }
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

  // ToDoリスト
  $('addTodoBtn').addEventListener('click', () => {
    view.todoEditingId = null;
    view.todoFormOpen = true;
    renderTodoForm();
    const el = $('tdTitle');
    if (el) el.focus();
  });

  document.querySelectorAll('.sc-todo-filter-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.sc-todo-filter-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      view.todoFilter = tab.dataset.todofilter;
      renderTodos();
    });
  });

  $('todoList').addEventListener('click', (ev) => {
    const editBtn = ev.target.closest('[data-todo-edit]');
    if (editBtn) {
      view.todoEditingId = editBtn.dataset.todoEdit;
      view.todoFormOpen = true;
      renderTodoForm();
      const el = $('tdTitle');
      if (el) el.focus();
      return;
    }
    const delBtn = ev.target.closest('[data-todo-del]');
    if (delBtn) { deleteTodo(delBtn.dataset.todoDel); return; }
  });

  $('todoList').addEventListener('change', (ev) => {
    const cb = ev.target.closest('[data-todo-toggle]');
    if (cb) toggleTodoDone(cb.dataset.todoToggle);
  });

  $('todoFormWrap').addEventListener('submit', (ev) => {
    if (ev.target.id !== 'todoForm') return;
    ev.preventDefault();
    const title = $('tdTitle').value.trim();
    if (!title) { toast('項目名を入力してください', true); $('tdTitle').focus(); return; }
    const data = {
      title,
      note: $('tdNote').value,
      category: $('tdCategory').value,
      dueDate: $('tdDueDate').value,
      dueTime: $('tdDueTime').value,
    };
    if (view.todoEditingId) updateTodo(view.todoEditingId, data);
    else addTodo(data);
    view.todoFormOpen = false;
    view.todoEditingId = null;
    renderTodos();
    renderStats();
    renderTodoForm();
  });

  $('todoFormWrap').addEventListener('click', (ev) => {
    if (ev.target.id === 'tdCancel') {
      view.todoFormOpen = false;
      view.todoEditingId = null;
      renderTodoForm();
    }
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
    const chip = ev.target.closest('.sc-proj-chip-name');
    if (chip) removeProject(chip.dataset.project);
  });
  $('projectChips').addEventListener('input', (ev) => {
    const picker = ev.target.closest('[data-project-color]');
    if (picker) setProjectColor(picker.dataset.projectColor, picker.value);
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
  $('notifyEnabled').addEventListener('change', () => {
    state.settings.notify.enabled = $('notifyEnabled').checked;
    saveState();
    renderNotifyStatus();
    if (state.settings.notify.enabled && notifySupported() && Notification.permission !== 'granted') {
      requestNotifyPermission();
    } else {
      ensureNotifyChecker();
    }
  });
  $('notifyLead').addEventListener('change', () => {
    state.settings.notify.leadMinutes = Number($('notifyLead').value) || 0;
    saveState();
  });
  $('notifyPermBtn').addEventListener('click', requestNotifyPermission);

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
    ensureNotifyChecker();
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
  const SECTIONS = ['calendar', 'mail', 'list', 'todo', 'settings'];
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
  // 用紙の向き（A4の横／縦）。印刷の設定は @page でしか指定できないため、選んだ向きを <style> に書き出す
  const orientSel = $('printOrient');
  const applyPrintOrient = () => {
    const portrait = orientSel.value === 'portrait';
    document.body.classList.toggle('sc-print-portrait', portrait);
    let st = $('printPageStyle');
    if (!st) { st = document.createElement('style'); st.id = 'printPageStyle'; document.head.appendChild(st); }
    st.textContent = `@page { size: A4 ${portrait ? 'portrait' : 'landscape'}; margin: ${portrait ? '8mm 8mm' : '7mm 6mm'}; }`;
  };
  try { const saved = localStorage.getItem('ms-schedule-print-orient'); if (saved === 'portrait' || saved === 'landscape') orientSel.value = saved; } catch (err) { /* 保存できなくても使える */ }
  applyPrintOrient();
  orientSel.addEventListener('change', () => {
    applyPrintOrient();
    try { localStorage.setItem('ms-schedule-print-orient', orientSel.value); } catch (err) { /* 保存できなくても使える */ }
  });
  window.addEventListener('beforeprint', () => {
    applyPrintOrient();
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
    if (ev.key === 'Escape' && !elConfirmOverlay.hidden) { closeCustomConfirm(false); return; }
    if (ev.key === 'Escape' && voice.open) { closeVoice(); return; }
    if (ev.key === 'Escape' && (elSideAside.classList.contains('sc-side-modal-open') || (view.formOpen && !view.editingId))) { closeForm(); return; }
    if (ev.target.matches('input, textarea, select')) return;
    if (ev.key === 'ArrowLeft') moveMonth(-1);
    if (ev.key === 'ArrowRight') moveMonth(1);
  });

  // 自前の確認ダイアログ
  elConfirmOk.addEventListener('click', () => closeCustomConfirm(true));
  elConfirmCancel.addEventListener('click', () => closeCustomConfirm(false));
  elConfirmOverlay.addEventListener('click', (ev) => {
    // 開いた直後のタップ（日付をタップした指のクリック）では閉じない
    if (ev.target === elConfirmOverlay && Date.now() - confirmOpenedAt > SHEET_GUARD_MS) closeCustomConfirm(false);
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
  elConfirmOverlay = $('confirmOverlay');
  elConfirmMsg = $('confirmMsg');
  elConfirmOk = $('confirmOkBtn');
  elConfirmCancel = $('confirmCancelBtn');

  loadState();
  loadSyncConfig();
  syncSettingsInputs();
  syncSyncInputs();
  refreshWorkTypeOptions();
  refreshProjectFilterOptions();
  view.form = blankForm();
  bindEvents();
  placeToolbar();
  renderAll();
  // スマホで開いたときは、今日の行がすぐ見えるようにする
  if (isNarrow()) requestAnimationFrame(() => { measureSticky(); scrollToTodayIfNeeded(false); });

  // 起動時に、他の端末で更新されていないか確認する
  if (sync.auto && syncConfigured()) syncNow(true);
  startSyncPolling();
  ensureNotifyChecker();

  // アプリとして追加できるようにする（file:// で開いた場合は動かないので黙って見送る）
  if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}

document.addEventListener('DOMContentLoaded', init);

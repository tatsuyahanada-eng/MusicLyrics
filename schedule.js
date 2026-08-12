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

/** 案件名の選択肢。これ以外は「フリー」を選んで自由入力する */
const PROJECT_PRESETS = [
  'リテイルオンサイト', 'JCOM', 'JT', '自社案件', 'くらしのマーケット', 'ミツモア',
];
const PROJECT_FREE = '__free__';

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
    settings: { bufferMin: 30, defStart: '09:00', defEnd: '18:00', senderName: '' },
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
  paint: null,             // null | 'available' | 'off' | 'clear' | 'multi'
  multi: new Set(),        // まとめて登録する日（'YYYY-MM-DD'）
  editingId: null,
  confirming: false,       // 仮出勤 → 確定 への切り替え中
  jobFilter: 'all',        // 予定一覧の絞り込み
  ack: false,              // 「重複を承知で登録する」
  form: null,              // 入力途中の値を保持
};

let painting = false;
let paintAdd = true;      // 複数日モードでドラッグ中に追加するか解除するか
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
let elCalendar, elMonthLabel, elMonthPicker, elSidePanel, elStats,
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
  const first = new Date(view.year, view.month, 1);
  const start = addDays(first, -first.getDay());
  const today = todayKey();

  const cells = [];
  for (let i = 0; i < 42; i++) {
    const date = addDays(start, i);
    const key = toKey(date);
    const inMonth = date.getMonth() === view.month;
    const dow = date.getDay();
    const hol = holidayName(key);
    const wish = state.wishes[key] || null;
    const dayJobs = jobsOn(key);
    const hasConflict = dayJobs.some((j) => conflicts.has(j.id));

    const hasConfirmed = dayJobs.some((j) => j.status !== 'tentative');
    const hasTentative = dayJobs.some((j) => j.status === 'tentative');

    // 希望も予定も入っていない、これからの日（＝まだ決まっていない日）
    const isEmpty = inMonth && key >= today && !wish && !dayJobs.length;

    const classes = ['sc-cell'];
    if (!inMonth) classes.push('sc-cell-out');
    if (key < today) classes.push('sc-cell-past');
    if (isEmpty) classes.push('sc-cell-empty');
    if (view.multi.has(key)) classes.push('sc-cell-multi');
    if (key === today) classes.push('sc-cell-today');
    if (key === view.selected) classes.push('sc-cell-selected');
    if (wish === WISH_AVAILABLE) classes.push('sc-cell-available');
    if (wish === WISH_OFF) classes.push('sc-cell-off');
    // 確定がある日は希望の色より優先して塗る（一目で「入っている日」がわかるように）
    if (hasConfirmed) classes.push('sc-cell-confirmed');
    else if (hasTentative) classes.push('sc-cell-tentative');
    if (hasConflict) classes.push('sc-cell-conflict');

    let numClass = 'sc-daynum';
    if (hol) numClass += ' sc-daynum-holiday';
    else if (dow === 0) numClass += ' sc-daynum-sun';
    else if (dow === 6) numClass += ' sc-daynum-sat';

    const marks = [];
    if (hasConfirmed) marks.push('<span class="sc-mark-confirmed">確定</span>');
    else if (hasTentative) marks.push('<span class="sc-mark-tentative">仮</span>');
    if (wish === WISH_AVAILABLE && !dayJobs.length) marks.push('<span class="sc-mark-available">◯</span>');
    if (wish === WISH_OFF) marks.push('<span class="sc-mark-off">✕</span>');
    if (view.multi.has(key)) marks.push('<span class="sc-mark-multi">✓</span>');
    if (isEmpty) marks.push('<span class="sc-mark-empty">未定</span>');
    if (hasConflict) marks.push('<span class="sc-mark-conflict">⚠</span>');

    const pills = dayJobs.slice(0, 3).map((j) => {
      const cls = ['sc-job-pill', j.status === 'tentative' ? 'sc-job-pill-tentative' : 'sc-job-pill-confirmed'];
      if (conflicts.has(j.id)) cls.push('sc-job-pill-conflict');
      const time = j.allDay ? '終日' : j.start;
      const tip = [j.title, j.workType, j.client, j.place].filter(Boolean).join(' / ');
      return `<span class="${cls.join(' ')}" title="${escapeHtml(tip)}">` +
        `${j.status === 'tentative' ? '<span class="sc-pill-mark">仮</span>' : ''}` +
        `${escapeHtml(time)} ${escapeHtml(j.title || '(無題)')}</span>`;
    }).join('');
    // 画面幅で表示できる件数が変わるため、desktop（3件表示）と mobile（2件表示）で残数を出し分ける
    const more =
      (dayJobs.length > 3 ? `<span class="sc-job-more sc-more-desktop">ほか${dayJobs.length - 3}件</span>` : '') +
      (dayJobs.length > 2 ? `<span class="sc-job-more sc-more-mobile">ほか${dayJobs.length - 2}件</span>` : '');

    const label = `${date.getMonth() + 1}月${date.getDate()}日 ${WD[dow]}曜日`
      + (hol ? ` ${hol}` : '')
      + (wish === WISH_AVAILABLE ? ' 稼働可能' : wish === WISH_OFF ? ' 休み希望' : '')
      + (isEmpty ? ' 未定' : '')
      + (hasConfirmed ? ' 確定あり' : hasTentative ? ' 仮出勤あり' : '')
      + (dayJobs.length ? ` 予定${dayJobs.length}件` : '')
      + (hasConflict ? ' 重複あり' : '');

    cells.push(
      `<button type="button" class="${classes.join(' ')}" data-date="${key}" role="gridcell" aria-label="${escapeHtml(label)}">` +
        `<span class="sc-cell-head">` +
          `<span class="${numClass}">${date.getDate()}</span>` +
          `<span class="sc-cell-marks">${marks.join('')}</span>` +
        `</span>` +
        (hol ? `<span class="sc-holiday-name">${escapeHtml(hol)}</span>` : '') +
        `<span class="sc-cell-jobs">${pills}${more}</span>` +
      `</button>`
    );
  }
  elCalendar.innerHTML = cells.join('');
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
        <label class="sc-field">
          <span class="sc-field-label">案件名 <span aria-hidden="true">*</span></span>
          <select id="fTitle" class="sc-input">
            <option value="" ${f.titleSel === '' ? 'selected' : ''}>選択してください</option>
            ${PROJECT_PRESETS.map((p) =>
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
          <span class="sc-field-label">メモ</span>
          <input id="fNote" class="sc-input" type="text" value="${escapeHtml(f.note)}" autocomplete="off">
        </label>

        <div id="formAlert"></div>

        <div class="sc-form-actions">
          <button type="submit" id="fSubmit" class="sc-btn${view.confirming ? ' sc-btn-confirm' : ''}">${submitLabel()}</button>
          ${view.editingId ? '<button type="button" id="fCancel" class="sc-btn sc-btn-outline">中止</button>' : ''}
        </div>
      </form>
    </div>`;
}

/** 複数日をまとめて扱うときのパネル */
function renderMultiPanel() {
  const dates = Array.from(view.multi).sort();
  const f = view.form || blankForm();

  if (!dates.length) {
    elSidePanel.innerHTML = `
      <p class="sc-side-date">複数日をまとめて登録</p>
      <p class="sc-side-date-sub">カレンダーの日付をクリックして選んでください（ドラッグで連続選択）。</p>
      <p class="sc-side-empty">まだ選ばれていません。</p>`;
    return;
  }

  const chips = dates.map((d) =>
    `<button type="button" class="sc-date-chip" data-unpick="${d}" title="外す">${formatDate(d)} <span aria-hidden="true">×</span></button>`
  ).join('');

  const withJobs = dates.filter((d) => jobsOn(d).length);
  const offDays = dates.filter((d) => (state.wishes[d] || null) === WISH_OFF);

  elSidePanel.innerHTML = `
    <div>
      <p class="sc-side-date">${dates.length}日を選択中</p>
      <p class="sc-side-date-sub">同じ内容の予定を、まとめて登録できます。</p>
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

    ${(withJobs.length || offDays.length) ? `<div class="sc-side-block">
      ${withJobs.length ? `<p class="sc-job-meta">・すでに予定がある日：${withJobs.map((d) => formatDate(d)).join('、')}</p>` : ''}
      ${offDays.length ? `<p class="sc-job-meta">・休み希望の日：${offDays.map((d) => formatDate(d)).join('、')}</p>` : ''}
    </div>` : ''}

    ${jobFormHtml(f)}
  `;

  updateFormAlert();
}

function renderSidePanel() {
  if (view.paint === 'multi') { renderMultiPanel(); return; }

  const key = view.selected;
  if (!key) {
    elSidePanel.innerHTML = '<p class="sc-side-empty">カレンダーの日付を選択してください。</p>';
    return;
  }

  const hol = holidayName(key);
  const wish = state.wishes[key] || null;
  const conflicts = conflictingJobIds();
  const dayJobs = jobsOn(key);

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
    return `<div class="${cls.join(' ')}">
      <div class="sc-job-card-top">
        <span class="sc-job-status">${tentative ? '仮出勤' : '確定'}</span>
        <span class="sc-job-time">${escapeHtml(time)}</span>
        <span class="sc-job-title">${escapeHtml(j.title || '(無題)')}</span>
      </div>
      ${j.workType ? `<p class="sc-job-meta"><span class="sc-worktype-tag">${escapeHtml(j.workType)}</span></p>` : ''}
      ${meta ? `<p class="sc-job-meta">${meta}</p>` : ''}
      ${j.note ? `<p class="sc-job-meta">📝 ${escapeHtml(j.note)}</p>` : ''}
      ${warn}
      <div class="sc-job-actions">
        ${tentative ? `<button type="button" class="sc-btn sc-btn-sm sc-btn-confirm" data-confirm="${j.id}">✓ 確定にする</button>` : ''}
        ${gcalUrl ? `<a class="sc-btn sc-btn-sm sc-btn-outline" href="${escapeHtml(gcalUrl)}" target="_blank" rel="noopener">📆 追加</a>` : ''}
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline" data-edit="${j.id}">編集</button>
        <button type="button" class="sc-btn sc-btn-sm sc-btn-outline sc-btn-danger" data-del="${j.id}">削除</button>
      </div>
    </div>`;
  }).join('');

  const f = view.form || blankForm();

  elSidePanel.innerHTML = `
    <div>
      <p class="sc-side-date">${formatDate(key, 'long')}</p>
      <p class="sc-side-date-sub">${hol ? '🎌 ' + escapeHtml(hol) : ''}${key < todayKey() ? ' （過去の日付）' : ''}</p>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">この日の希望</p>
      <div class="sc-wish-row">
        <button type="button" class="sc-wish-btn ${wish === WISH_AVAILABLE ? 'active-available' : ''}" data-wish="available">◯ 稼働可</button>
        <button type="button" class="sc-wish-btn ${wish === WISH_OFF ? 'active-off' : ''}" data-wish="off">✕ 休み希望</button>
        <button type="button" class="sc-wish-btn ${!wish ? 'active-none' : ''}" data-wish="none">− 未定</button>
      </div>
    </div>

    <div class="sc-side-block">
      <p class="sc-side-block-title">この日の予定（${dayJobs.length}件）</p>
      ${cards || '<p class="sc-empty-note">まだ予定はありません。</p>'}
    </div>

    ${jobFormHtml(f)}
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
    const meta = [j.client, j.place, j.note].filter(Boolean).map(escapeHtml).join(' / ');
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

function listedDayKeys() {
  const target = $('listTarget').value;             // 'off' = 休み希望日 / 'available' = 稼働可能日
  const excludeBooked = $('excludeBooked').checked;
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
  const name = target === 'off' ? '休み希望日' : '稼働可能日';
  const title = `${view.year}年${view.month + 1}月`;

  if (!keys.length) {
    return target === 'off'
      ? `${title}の休み希望日は登録されていません。\nカレンダーで「✕ 休み希望」を設定してください。`
      : `${title}の稼働可能日は登録されていません。\nカレンダーで「◯ 稼働可」を設定してください。`;
  }

  if (format === 'inline') return keys.map((k) => formatDate(k)).join('、');
  if (format === 'dayonly') return keys.map((k) => Number(k.slice(8))).join('、') + '日';

  return [`${title} ${name}（全${keys.length}日）`, '']
    .concat(keys.map((k) => '・' + formatDate(k)))
    .join('\n');
}

function renderExport() {
  const target = $('listTarget').value;
  // 「予定が入っている日は除く」は稼働可能日のときだけ意味がある
  $('excludeBookedWrap').hidden = target !== 'available';
  const keys = listedDayKeys();
  const name = target === 'off' ? '休み希望' : '稼働可能';
  $('availCount').textContent = `${view.year}年${view.month + 1}月の${name}：${keys.length}日`;
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
    const meta = [j.workType, j.client, j.place].filter(Boolean).join(' / ');
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
  const monthKeys = monthDayKeys();
  const today = todayKey();
  return state.jobs.filter((j) => {
    if (scope === 'month-confirmed') return monthKeys.includes(j.date) && j.status !== 'tentative';
    if (scope === 'month-all') return monthKeys.includes(j.date);
    if (scope === 'future-confirmed') return j.date >= today && j.status !== 'tentative';
    return true;
  }).sort((a, b) => jobRange(a).s - jobRange(b).s);
}

function icsSummary(job, prefix) {
  const tentative = job.status === 'tentative';
  return (prefix ? prefix : '') + (tentative ? '【仮】' : '') + (job.title || '(無題)');
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

    const desc = [
      job.workType ? '業務内容: ' + job.workType : '',
      job.client ? '依頼元: ' + job.client : '',
      job.note ? 'メモ: ' + job.note : '',
      job.status === 'tentative' ? '※ 未確定（仮出勤）' : '',
    ].filter(Boolean).join('\n');

    lines.push('BEGIN:VEVENT');
    lines.push('UID:' + job.id + '@shift-calendar');
    lines.push('DTSTAMP:' + stamp);
    lines.push('SEQUENCE:' + (Number(job.rev) || 0));
    timeLines.forEach((l) => lines.push(l));
    lines.push('SUMMARY:' + icsEscape(icsSummary(job, prefix)));
    if (desc) lines.push('DESCRIPTION:' + icsEscape(desc));
    if (job.place) lines.push('LOCATION:' + icsEscape(job.place));
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
  const details = [
    job.workType ? '業務内容: ' + job.workType : '',
    job.client ? '依頼元: ' + job.client : '',
    job.note ? 'メモ: ' + job.note : '',
    job.status === 'tentative' ? '※ 未確定（仮出勤）' : '',
  ].filter(Boolean).join('\n');

  return 'https://calendar.google.com/calendar/render?action=TEMPLATE'
    + '&text=' + encodeURIComponent(icsSummary(job, ''))
    + '&dates=' + dates
    + '&ctz=Asia/Tokyo'
    + (details ? '&details=' + encodeURIComponent(details) : '')
    + (job.place ? '&location=' + encodeURIComponent(job.place) : '');
}

/* ------------------------------------------------------------
   入力フォーム
   ------------------------------------------------------------ */

function blankForm() {
  return {
    titleSel: '', titleFree: '', workType: '', status: 'confirmed', allDay: false,
    start: state.settings.defStart, end: state.settings.defEnd,
    client: '', place: '', note: '',
  };
}

function formFromJob(job) {
  const title = job.title || '';
  const preset = PROJECT_PRESETS.includes(title);
  return {
    titleSel: title ? (preset ? title : PROJECT_FREE) : '',
    titleFree: preset ? '' : title,
    workType: job.workType || '',
    status: job.status || 'confirmed',
    allDay: !!job.allDay,
    start: job.start || state.settings.defStart,
    end: job.end || state.settings.defEnd,
    client: job.client || '',
    place: job.place || '',
    note: job.note || '',
  };
}

function readForm() {
  if (!$('jobForm')) return null;
  return {
    titleSel: $('fTitle').value,
    titleFree: $('fTitleFree').value,
    workType: $('fWorkType').value,
    status: $('fStatus').value,
    allDay: $('fAllDay').checked,
    start: $('fStart').value,
    end: $('fEnd').value,
    client: $('fClient').value,
    place: $('fPlace').value,
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
    date: view.selected,
    allDay: !!f.allDay,
    start: f.start || state.settings.defStart,
    end: f.end || state.settings.defEnd,
    title: formTitle(f),
    workType: f.workType.trim(),
    client: f.client.trim(),
    place: f.place.trim(),
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
  if ((state.wishes[view.selected] || null) === WISH_OFF) {
    warns.push('この日は「休み希望」に設定されています。');
  }
  if (view.selected < todayKey()) {
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

/** 選んだ日それぞれについて、登録しようとしている内容の重複を調べる */
function multiConflicts() {
  const f = view.form || blankForm();
  if (!f.titleSel) return [];
  return Array.from(view.multi).sort().map((date) => {
    const draft = Object.assign(draftJob(), { id: '__draft__', date: date });
    return { date: date, conflicts: findConflicts(draft, null) };
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
        return `<li>${formatDate(r.date)}：${names}</li>`;
      }).join('');
      html = `<div class="sc-alert sc-alert-danger">
        <span class="sc-alert-title">⚠ ${found.length}日分が既存の予定と重なります</span>
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
    return hasConflict ? `重複を承知で ${n}日分を登録` : `${n}日分をまとめて登録`;
  }
  if (view.confirming) return hasConflict ? '重複を承知で確定する' : 'この内容で確定する';
  if (view.editingId) return hasConflict ? '重複を承知で更新' : '更新する';
  return hasConflict ? '重複を承知で登録' : '登録する';
}

/** 選んだ日すべてに同じ内容の予定を作る */
function submitMultiJobs() {
  const base = draftJob();
  const dates = Array.from(view.multi).sort();

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
  dates.forEach((date) => {
    const job = Object.assign({}, base, {
      id: newId(), date: date, createdAt: at, updatedAt: at,
    });
    if (job.status === 'confirmed') job.confirmedAt = at;
    state.jobs.push(job);
    if (state.wishes[date] === WISH_OFF) setWish(date, null, true);
  });

  view.multi.clear();
  view.ack = false;
  view.form = blankForm();
  saveState();
  refreshWorkTypeOptions();
  renderAll();
  toast(`${dates.length}日分を登録しました`);
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

  view.editingId = null;
  view.confirming = false;
  view.ack = false;
  view.form = blankForm();
  saveState();

  // 確定させたときは、同じ時間帯に残っている仮出勤をまとめて取り消せるようにする
  if (draft.status === 'confirmed') cleanupTentatives(savedId);

  refreshWorkTypeOptions();
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

function selectDate(key, opts) {
  view.selected = key;
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

  // 1カラム表示のときは詳細パネルが画面外になるため送る
  // （まとめて入力中は、続けて塗れるようにその場へとどまる）
  if (!(opts && opts.noScroll) && window.innerWidth <= 900) {
    elSidePanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
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
        ? 'まとめて入力中です。ドラッグで一括設定、1日だけクリックするとその日の詳細も開きます。終わったら「選択のみ」に戻してください。'
        : '日付をクリックすると詳細パネルが開きます。まとめて入力を選ぶと、クリック（ドラッグ）で一括設定できます。';
      document.querySelector('.sc-calendar').classList.toggle('sc-calendar-painting', !!view.paint);
    });
  });

  // カレンダー：クリック／ドラッグ塗り
  elCalendar.addEventListener('pointerdown', (ev) => {
    const cell = ev.target.closest('.sc-cell');
    if (!cell) return;
    const key = cell.dataset.date;
    if (!view.paint) return;

    painting = true;
    paintTouched.clear();
    paintTouched.add(key);

    if (view.paint === 'multi') {
      // 最初の1日で決めた向き（追加か解除か）に、ドラッグ中もそろえる
      paintAdd = !view.multi.has(key);
      if (paintAdd) view.multi.add(key); else view.multi.delete(key);
      renderCalendar();
      renderSidePanel();
      return;
    }

    applyPaint(key);
    renderCalendar();
    renderStats();
    renderExport();
  });

  elCalendar.addEventListener('pointerover', (ev) => {
    if (!painting || !view.paint) return;
    const cell = ev.target.closest('.sc-cell');
    if (!cell) return;
    const key = cell.dataset.date;
    if (paintTouched.has(key)) return;
    paintTouched.add(key);

    if (view.paint === 'multi') {
      if (paintAdd) view.multi.add(key); else view.multi.delete(key);
      renderCalendar();
      renderSidePanel();
      return;
    }

    applyPaint(key);
    renderCalendar();
  });

  const endPaint = () => {
    if (!painting) return;
    painting = false;
    if (view.paint === 'multi') { renderAll(); return; }
    // 1日だけ押したときは、その日を選んで詳細も開く。
    // （まとめて入力のままだと日付を選べず、予定を登録できないと誤解されるため）
    if (paintTouched.size === 1) {
      selectDate(Array.from(paintTouched)[0], { noScroll: true });
    } else {
      renderAll();
    }
  };
  window.addEventListener('pointerup', endPaint);
  window.addEventListener('pointercancel', endPaint);

  elCalendar.addEventListener('click', (ev) => {
    const cell = ev.target.closest('.sc-cell');
    if (!cell) return;
    if (view.paint) return;   // 塗りモード中は選択しない
    selectDate(cell.dataset.date);
  });

  // クイック設定
  $('quickWeekday').addEventListener('click', () => {
    monthDayKeys().forEach((key) => {
      const d = fromKey(key);
      if (d.getDay() !== 0 && d.getDay() !== 6 && !holidayName(key)) {
        setWish(key, WISH_AVAILABLE, true);
      }
    });
    saveState();
    renderAll();
    toast('平日を稼働可能に設定しました');
  });

  $('quickWeekend').addEventListener('click', () => {
    monthDayKeys().forEach((key) => {
      const d = fromKey(key);
      if (d.getDay() === 0 || d.getDay() === 6 || holidayName(key)) {
        setWish(key, WISH_OFF, true);
      }
    });
    saveState();
    renderAll();
    toast('土日祝を休み希望に設定しました');
  });

  $('quickClear').addEventListener('click', () => {
    if (!confirm(`${view.year}年${view.month + 1}月の希望（稼働可・休み希望）をすべて消します。予定は残ります。よろしいですか？`)) return;
    monthDayKeys().forEach((key) => { setWish(key, null, true); });
    saveState();
    renderAll();
    toast('今月の希望をクリアしました');
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
      const dates = Array.from(view.multi);
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

    const confirmBtn = ev.target.closest('[data-confirm]');
    if (confirmBtn) { startConfirm(confirmBtn.dataset.confirm); return; }

    const editBtn = ev.target.closest('[data-edit]');
    if (editBtn) { startEdit(editBtn.dataset.edit); return; }

    const delBtn = ev.target.closest('[data-del]');
    if (delBtn) { deleteJob(delBtn.dataset.del); return; }

    if (ev.target.id === 'fCancel') {
      view.editingId = null;
      view.confirming = false;
      view.ack = false;
      view.form = blankForm();
      renderSidePanel();
    }
  });

  elSidePanel.addEventListener('input', (ev) => {
    // 承知チェックは change 側で扱う（ここで警告欄を作り直すと反応が消えるため）
    if (ev.target.id === 'fAck') return;
    if (!ev.target.closest('#jobForm')) return;
    view.form = readForm();
    updateFormAlert();
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

  // 休み希望日の一覧
  ['listTarget', 'exportFormat', 'excludeBooked'].forEach((id) => {
    $(id).addEventListener('change', renderExport);
  });

  $('copyExport').addEventListener('click', async () => {
    const ok = await copyText(elExportText.value);
    toast(ok ? 'コピーしました' : 'コピーできませんでした。手動で選択してください', !ok);
  });

  // Googleカレンダー用ファイル
  $('icsScope').addEventListener('change', renderIcsPreview);
  $('icsPrefix').addEventListener('input', renderIcsPreview);
  $('downloadIcs').addEventListener('click', downloadIcs);

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
  const SECTIONS = ['calendar', 'mail', 'list', 'gcal', 'sync', 'settings'];
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

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

function defaultState() {
  return {
    version: 1,
    settings: { bufferMin: 30, defStart: '09:00', defEnd: '18:00', senderName: '' },
    wishes: {},   // 'YYYY-MM-DD' → 'available' | 'off'
    jobs: [],     // { id, date, allDay, start, end, title, client, place, note, status }
  };
}

let state = defaultState();

const now = new Date();
const view = {
  year: now.getFullYear(),
  month: now.getMonth(),   // 0-11
  selected: null,          // 'YYYY-MM-DD'
  paint: null,             // null | 'available' | 'off' | 'clear'
  editingId: null,
  ack: false,              // 「重複を承知で登録する」
  form: null,              // 入力途中の値を保持
};

let painting = false;
const paintTouched = new Set();

function loadState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return;
    state = {
      version: 1,
      settings: Object.assign(defaultState().settings, parsed.settings || {}),
      wishes: parsed.wishes && typeof parsed.wishes === 'object' ? parsed.wishes : {},
      jobs: Array.isArray(parsed.jobs) ? parsed.jobs.filter(isValidJob) : [],
    };
  } catch (err) {
    console.warn('保存データの読み込みに失敗しました', err);
  }
}

function isValidJob(j) {
  return j && typeof j === 'object' && typeof j.id === 'string'
    && typeof j.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(j.date);
}

function saveState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch (err) {
    toast('保存に失敗しました（ブラウザの設定をご確認ください）', true);
  }
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

    const classes = ['sc-cell'];
    if (!inMonth) classes.push('sc-cell-out');
    if (key < today) classes.push('sc-cell-past');
    if (key === today) classes.push('sc-cell-today');
    if (key === view.selected) classes.push('sc-cell-selected');
    if (wish === WISH_AVAILABLE) classes.push('sc-cell-available');
    if (wish === WISH_OFF) classes.push('sc-cell-off');
    if (hasConflict) classes.push('sc-cell-conflict');

    let numClass = 'sc-daynum';
    if (hol) numClass += ' sc-daynum-holiday';
    else if (dow === 0) numClass += ' sc-daynum-sun';
    else if (dow === 6) numClass += ' sc-daynum-sat';

    const marks = [];
    if (wish === WISH_AVAILABLE) marks.push('<span class="sc-mark-available">◯</span>');
    if (wish === WISH_OFF) marks.push('<span class="sc-mark-off">✕</span>');
    if (hasConflict) marks.push('<span class="sc-mark-conflict">⚠</span>');

    const pills = dayJobs.slice(0, 3).map((j) => {
      const cls = ['sc-job-pill', j.status === 'tentative' ? 'sc-job-pill-tentative' : 'sc-job-pill-confirmed'];
      if (conflicts.has(j.id)) cls.push('sc-job-pill-conflict');
      const time = j.allDay ? '終日' : j.start;
      return `<span class="${cls.join(' ')}">${escapeHtml(time)} ${escapeHtml(j.title || '(無題)')}</span>`;
    }).join('');
    const more = dayJobs.length > 3
      ? `<span class="sc-job-more">ほか${dayJobs.length - 3}件</span>` : '';

    const label = `${date.getMonth() + 1}月${date.getDate()}日 ${WD[dow]}曜日`
      + (hol ? ` ${hol}` : '')
      + (wish === WISH_AVAILABLE ? ' 稼働可能' : wish === WISH_OFF ? ' 休み希望' : '')
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

function renderSidePanel() {
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
    const cls = ['sc-job-card'];
    if (j.status === 'tentative') cls.push('sc-job-card-tentative');
    if (conflicts.has(j.id)) cls.push('sc-job-card-conflict');
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.client, j.place].filter(Boolean).map(escapeHtml).join(' / ');
    const others = findConflicts(j, null);
    const warn = others.length
      ? `<p class="sc-job-warn">⚠ ${others.map((c) => (c.type === 'overlap' ? '時間が重複' : '移動時間が不足') + '：' + formatDate(c.job.date) + ' ' + escapeHtml(c.job.title || '(無題)')).join(' / ')}</p>`
      : '';
    return `<div class="${cls.join(' ')}">
      <div class="sc-job-card-top">
        <span class="sc-job-time">${escapeHtml(time)}</span>
        <span class="sc-job-title">${escapeHtml(j.title || '(無題)')}</span>
        <span class="sc-job-status">${j.status === 'tentative' ? '仮' : '確定'}</span>
      </div>
      ${meta ? `<p class="sc-job-meta">${meta}</p>` : ''}
      ${j.note ? `<p class="sc-job-meta">📝 ${escapeHtml(j.note)}</p>` : ''}
      ${warn}
      <div class="sc-job-actions">
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

    <div class="sc-side-block">
      <p class="sc-side-block-title">${view.editingId ? '予定を編集' : '予定を追加'}</p>
      <form id="jobForm" class="sc-form">
        <label class="sc-field">
          <span class="sc-field-label">案件名・現場名 <span aria-hidden="true">*</span></span>
          <input id="fTitle" class="sc-input" type="text" value="${escapeHtml(f.title)}" placeholder="例）○○ホール 音響" required autocomplete="off">
        </label>

        <div class="sc-form-row">
          <label class="sc-field">
            <span class="sc-field-label">区分</span>
            <select id="fStatus" class="sc-input">
              <option value="confirmed" ${f.status === 'confirmed' ? 'selected' : ''}>確定</option>
              <option value="tentative" ${f.status === 'tentative' ? 'selected' : ''}>仮おさえ</option>
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
          <button type="submit" id="fSubmit" class="sc-btn">${view.editingId ? '更新する' : '登録する'}</button>
          ${view.editingId ? '<button type="button" id="fCancel" class="sc-btn sc-btn-outline">中止</button>' : ''}
        </div>
      </form>
    </div>
  `;

  updateFormAlert();
}

function renderStats() {
  const days = monthDayKeys();
  let avail = 0, off = 0, jobDays = 0, jobCount = 0, hours = 0;
  days.forEach((key) => {
    const w = state.wishes[key];
    if (w === WISH_AVAILABLE) avail++;
    if (w === WISH_OFF) off++;
    const js = jobsOn(key);
    if (js.length) jobDays++;
    jobCount += js.length;
    js.forEach((j) => { hours += jobDuration(j); });
  });

  const stat = (label, value, unit) =>
    `<div class="sc-stat"><span class="sc-stat-label">${label}</span>` +
    `<span class="sc-stat-value">${value}<span class="sc-stat-unit">${unit}</span></span></div>`;

  elStats.innerHTML =
    stat('稼働可能', avail, '日') +
    stat('休み希望', off, '日') +
    stat('予定', jobCount, '件') +
    stat('稼働日', jobDays, '日') +
    stat('稼働時間', Math.round(hours * 10) / 10, 'h');
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
  const list = state.jobs
    .filter((j) => keys.includes(j.date))
    .sort((a, b) => jobRange(a).s - jobRange(b).s);

  elJobCount.textContent = list.length ? `${list.length}件` : '';

  if (!list.length) {
    elJobList.innerHTML = '<p class="sc-empty-note">この月に登録された予定はありません。</p>';
    return;
  }

  elJobList.innerHTML = list.map((j) => {
    const cls = ['sc-joblist-row'];
    if (j.status === 'tentative') cls.push('sc-joblist-row-tentative');
    if (conflicts.has(j.id)) cls.push('sc-joblist-row-conflict');
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.client, j.place, j.note].filter(Boolean).map(escapeHtml).join(' / ');
    return `<div class="${cls.join(' ')}" data-goto="${j.date}">
      <span class="sc-joblist-date">${formatDate(j.date)}</span>
      <span class="sc-joblist-time">${escapeHtml(time)}</span>
      <span class="sc-joblist-title">${escapeHtml(j.title || '(無題)')}</span>
      ${meta ? `<span class="sc-joblist-meta">${meta}</span>` : ''}
      ${conflicts.has(j.id) ? '<span class="sc-joblist-meta" style="color:#fca5a5">⚠ 重複</span>' : ''}
      ${j.status === 'tentative' ? '<span class="sc-joblist-meta">（仮）</span>' : ''}
    </div>`;
  }).join('');
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

function availableDayKeys() {
  const excludeBooked = $('excludeBooked').checked;
  const includeUndecided = $('includeUndecided').checked;
  return monthDayKeys().filter((key) => {
    const w = state.wishes[key] || null;
    if (w === WISH_OFF) return false;
    if (w !== WISH_AVAILABLE && !includeUndecided) return false;
    if (excludeBooked && jobsOn(key).length) return false;
    return true;
  });
}

function buildExportText() {
  const keys = availableDayKeys();
  const format = $('exportFormat').value;
  const sender = $('senderName').value.trim();
  const title = `${view.year}年${view.month + 1}月`;

  if (!keys.length) {
    return `${title}の稼働可能日は登録されていません。\nカレンダーで「◯ 稼働可」を設定してください。`;
  }

  const bullets = keys.map((k) => '・' + formatDate(k)).join('\n');

  if (format === 'bullet') return bullets;
  if (format === 'inline') return keys.map((k) => formatDate(k)).join('、');
  if (format === 'dayonly') return keys.map((k) => Number(k.slice(8))).join('、') + '日';

  const lines = [];
  lines.push('お世話になっております。' + (sender ? sender + 'です。' : ''));
  lines.push('');
  lines.push(`${title}の稼働可能日をご連絡いたします。`);
  lines.push('');
  lines.push(`■ 稼働可能日（全${keys.length}日）`);
  lines.push(bullets);
  lines.push('');
  lines.push('※上記以外の日は対応が難しい状況です。');
  lines.push('※ご依頼が確定しましたら、ご一報いただけますと幸いです。');
  lines.push('');
  lines.push('何卒よろしくお願いいたします。');
  if (sender) {
    lines.push('');
    lines.push(sender);
  }
  return lines.join('\n');
}

function renderExport() {
  const keys = availableDayKeys();
  elAvailCount.textContent = `${view.year}年${view.month + 1}月：${keys.length}日`;
  elExportText.value = buildExportText();
}

function buildJobsText() {
  const keys = monthDayKeys();
  const list = state.jobs
    .filter((j) => keys.includes(j.date))
    .sort((a, b) => jobRange(a).s - jobRange(b).s);
  if (!list.length) return `${view.year}年${view.month + 1}月の予定はありません。`;

  const lines = [`${view.year}年${view.month + 1}月の予定（${list.length}件）`, ''];
  list.forEach((j) => {
    const time = j.allDay ? '終日' : `${j.start}〜${j.end}`;
    const meta = [j.client, j.place].filter(Boolean).join(' / ');
    lines.push(`・${formatDate(j.date)} ${time} ${j.title || '(無題)'}`
      + (meta ? `（${meta}）` : '')
      + (j.status === 'tentative' ? ' ※仮おさえ' : ''));
  });
  return lines.join('\n');
}

/* ------------------------------------------------------------
   入力フォーム
   ------------------------------------------------------------ */

function blankForm() {
  return {
    title: '', status: 'confirmed', allDay: false,
    start: state.settings.defStart, end: state.settings.defEnd,
    client: '', place: '', note: '',
  };
}

function readForm() {
  if (!$('jobForm')) return null;
  return {
    title: $('fTitle').value,
    status: $('fStatus').value,
    allDay: $('fAllDay').checked,
    start: $('fStart').value,
    end: $('fEnd').value,
    client: $('fClient').value,
    place: $('fPlace').value,
    note: $('fNote').value,
  };
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
    title: f.title.trim(),
    client: f.client.trim(),
    place: f.place.trim(),
    note: f.note.trim(),
    status: f.status,
  };
}

/** 重複・注意事項を再計算して警告欄だけを更新（入力中のフォーカスを維持） */
function updateFormAlert() {
  const alertBox = $('formAlert');
  if (!alertBox || !view.selected) return;

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
    btn.textContent = needAck
      ? (view.editingId ? '重複を承知で更新' : '重複を承知で登録')
      : (view.editingId ? '更新する' : '登録する');
  }
}

function submitJob(ev) {
  ev.preventDefault();
  view.form = readForm();

  const draft = draftJob();
  if (!draft.title) {
    toast('案件名を入力してください', true);
    $('fTitle').focus();
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

  if (view.editingId) {
    const idx = state.jobs.findIndex((j) => j.id === view.editingId);
    if (idx >= 0) {
      state.jobs[idx] = Object.assign({}, state.jobs[idx], draft, { id: view.editingId });
    }
    toast('予定を更新しました');
  } else {
    draft.id = newId();
    draft.createdAt = new Date().toISOString();
    state.jobs.push(draft);
    toast(conflicts.length ? '重複を承知で登録しました' : '予定を登録しました');
  }

  // 予定を入れた日は自動で「休み希望」を解除して稼働扱いにそろえる
  if (state.wishes[draft.date] === WISH_OFF) delete state.wishes[draft.date];

  view.editingId = null;
  view.ack = false;
  view.form = blankForm();
  saveState();
  renderAll();
}

function startEdit(id) {
  const job = state.jobs.find((j) => j.id === id);
  if (!job) return;
  view.selected = job.date;
  view.editingId = id;
  view.ack = false;
  view.form = {
    title: job.title || '', status: job.status || 'confirmed', allDay: !!job.allDay,
    start: job.start || state.settings.defStart, end: job.end || state.settings.defEnd,
    client: job.client || '', place: job.place || '', note: job.note || '',
  };
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
  state.jobs = state.jobs.filter((j) => j.id !== id);
  if (view.editingId === id) {
    view.editingId = null;
    view.form = blankForm();
  }
  saveState();
  renderAll();
  toast('予定を削除しました');
}

/* ------------------------------------------------------------
   希望日の設定
   ------------------------------------------------------------ */

function setWish(dateKey, wish) {
  if (!wish) delete state.wishes[dateKey];
  else state.wishes[dateKey] = wish;
  saveState();
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
      view.form = blankForm();
      saveState();
      syncSettingsInputs();
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
  $('senderName').value = state.settings.senderName || '';
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

function selectDate(key) {
  view.selected = key;
  view.editingId = null;
  view.ack = false;
  view.form = blankForm();
  const d = fromKey(key);
  if (d.getMonth() !== view.month || d.getFullYear() !== view.year) {
    view.year = d.getFullYear();
    view.month = d.getMonth();
  }
  renderAll();

  // 1カラム表示のときは詳細パネルが画面外になるため送る
  if (window.innerWidth <= 900) {
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
      $('paintHint').textContent = view.paint
        ? 'カレンダーをクリック（ドラッグ）すると、まとめて設定できます。'
        : '日付をクリックすると詳細パネルが開きます。まとめて入力を選ぶと、クリック（ドラッグ）で一括設定できます。';
    });
  });

  // カレンダー：クリック／ドラッグ塗り
  elCalendar.addEventListener('pointerdown', (ev) => {
    const cell = ev.target.closest('.sc-cell');
    if (!cell) return;
    const key = cell.dataset.date;
    if (view.paint) {
      painting = true;
      paintTouched.clear();
      paintTouched.add(key);
      applyPaint(key);
      renderCalendar();
      renderStats();
      renderExport();
    }
  });

  elCalendar.addEventListener('pointerover', (ev) => {
    if (!painting || !view.paint) return;
    const cell = ev.target.closest('.sc-cell');
    if (!cell) return;
    const key = cell.dataset.date;
    if (paintTouched.has(key)) return;
    paintTouched.add(key);
    applyPaint(key);
    renderCalendar();
  });

  const endPaint = () => {
    if (!painting) return;
    painting = false;
    renderAll();
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
        state.wishes[key] = WISH_AVAILABLE;
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
        state.wishes[key] = WISH_OFF;
      }
    });
    saveState();
    renderAll();
    toast('土日祝を休み希望に設定しました');
  });

  $('quickClear').addEventListener('click', () => {
    if (!confirm(`${view.year}年${view.month + 1}月の希望（稼働可・休み希望）をすべて消します。予定は残ります。よろしいですか？`)) return;
    monthDayKeys().forEach((key) => { delete state.wishes[key]; });
    saveState();
    renderAll();
    toast('今月の希望をクリアしました');
  });

  // サイドパネル内の操作（委譲）
  elSidePanel.addEventListener('click', (ev) => {
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

    const editBtn = ev.target.closest('[data-edit]');
    if (editBtn) { startEdit(editBtn.dataset.edit); return; }

    const delBtn = ev.target.closest('[data-del]');
    if (delBtn) { deleteJob(delBtn.dataset.del); return; }

    if (ev.target.id === 'fCancel') {
      view.editingId = null;
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

  // 稼働可能日の書き出し
  ['exportFormat', 'excludeBooked', 'includeUndecided'].forEach((id) => {
    $(id).addEventListener('change', renderExport);
  });
  $('senderName').addEventListener('input', () => {
    state.settings.senderName = $('senderName').value;
    saveState();
    renderExport();
  });

  $('copyExport').addEventListener('click', async () => {
    const ok = await copyText(elExportText.value);
    toast(ok ? 'コピーしました。メールに貼り付けてください' : 'コピーできませんでした。手動で選択してください', !ok);
  });

  $('mailtoExport').addEventListener('click', () => {
    const subject = `【稼働可能日のご連絡】${view.year}年${view.month + 1}月`;
    window.location.href = 'mailto:?subject=' + encodeURIComponent(subject)
      + '&body=' + encodeURIComponent(elExportText.value);
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

  $('exportJson').addEventListener('click', exportJson);
  $('importJson').addEventListener('change', (ev) => {
    const file = ev.target.files && ev.target.files[0];
    if (file) importJson(file);
    ev.target.value = '';
  });
  $('resetAll').addEventListener('click', () => {
    if (!confirm('保存されているすべての希望・予定を削除します。元に戻せません。よろしいですか？')) return;
    if (!confirm('本当に削除してよろしいですか？')) return;
    state = defaultState();
    view.selected = null;
    view.editingId = null;
    view.form = blankForm();
    saveState();
    syncSettingsInputs();
    renderAll();
    toast('すべてのデータを削除しました');
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
  syncSettingsInputs();
  view.form = blankForm();
  bindEvents();
  renderAll();
}

document.addEventListener('DOMContentLoaded', init);

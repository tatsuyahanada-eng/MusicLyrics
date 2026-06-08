/* ========== OESタスク管理 ========== */
/* 試作版：MySQL連携前のモックデータでフロー確認用 */

(function () {
  'use strict';

  const APP_VERSION = 'v2 (2026-06-08)';
  console.log('[OESタスク管理] script loaded:', APP_VERSION);

  /* ---------- Constants ---------- */
  const STATUS = {
    assigned:     { label: 'アサイン',       step: 1 },
    mpr_received: { label: 'MPR受取済',     step: 2 },
    work_done:    { label: '当日作業完了',   step: 3 },
    returned:     { label: '撤去機返送済',   step: 4 },
    completed:    { label: '完了',           step: 5 },
  };
  const FLOW_STEPS = [
    { key: 'assigned',     label: '①アサイン' },
    { key: 'mpr_received', label: '②MPR受取' },
    { key: 'work_done',    label: '③当日作業' },
    { key: 'returned',     label: '④撤去機返送' },
    { key: 'completed',    label: '⑤完了' },
  ];

  /* ---------- Mock Data ---------- */
  const STAFFS = [
    { id: 'S001', name: '田中 太郎',  role: '関東エリア' },
    { id: 'S002', name: '佐藤 花子',  role: '関西エリア' },
    { id: 'S003', name: '鈴木 一郎',  role: '中部エリア' },
    { id: 'S004', name: '高橋 健太',  role: '九州エリア' },
    { id: 'S005', name: '渡辺 美咲',  role: '東北エリア' },
  ];

  const STORE_TEMPLATES = [
    { name: 'セブンイレブン {city}{branch}店',     addr: '{pref}{city}{street}' },
    { name: 'ファミリーマート {city}{branch}店',   addr: '{pref}{city}{street}' },
    { name: 'ローソン {city}{branch}店',           addr: '{pref}{city}{street}' },
    { name: 'ミニストップ {city}{branch}店',       addr: '{pref}{city}{street}' },
    { name: 'デイリーヤマザキ {city}{branch}店',   addr: '{pref}{city}{street}' },
  ];

  const REGIONS = {
    'S001': { pref: '東京都', cities: ['渋谷区', '新宿区', '世田谷区', '練馬区', '杉並区', '中野区', '品川区', '目黒区'] },
    'S002': { pref: '大阪府', cities: ['大阪市北区', '大阪市中央区', '吹田市', '堺市', '東大阪市', '豊中市', '高槻市'] },
    'S003': { pref: '愛知県', cities: ['名古屋市中区', '名古屋市東区', '豊田市', '岡崎市', '春日井市', '一宮市'] },
    'S004': { pref: '福岡県', cities: ['福岡市博多区', '福岡市中央区', '北九州市小倉北区', '久留米市', '大牟田市'] },
    'S005': { pref: '宮城県', cities: ['仙台市青葉区', '仙台市宮城野区', '石巻市', '大崎市'] },
  };

  const BRANCHES = ['駅前', '中央', '西口', '北', '南口', '東', '本町', '緑町', '桜', '元町'];
  const STREETS  = ['1-2-3', '4-5-6', '7-8-9', '10-11-12', '15-1', '3-7', '2-14-9', '6-22'];

  function seededRandom(seed) {
    let s = seed;
    return () => {
      s = (s * 9301 + 49297) % 233280;
      return s / 233280;
    };
  }

  function pick(rand, arr) { return arr[Math.floor(rand() * arr.length)]; }

  /** Generate ~10-20 tasks per staff for current and previous months */
  function generateTasks() {
    const tasks = [];
    const today = new Date();
    const rand = seededRandom(20260608);
    let serial = 1;

    // 当月 + 先月の2か月分を生成
    for (let monthOffset = -1; monthOffset <= 0; monthOffset++) {
      const base = new Date(today.getFullYear(), today.getMonth() + monthOffset, 1);

      STAFFS.forEach(staff => {
        const count = 10 + Math.floor(rand() * 11); // 10-20件
        const region = REGIONS[staff.id];

        for (let i = 0; i < count; i++) {
          const tpl = pick(rand, STORE_TEMPLATES);
          const city = pick(rand, region.cities);
          const branch = pick(rand, BRANCHES);
          const street = pick(rand, STREETS);
          const workDay = 1 + Math.floor(rand() * 27);
          const workDate = new Date(base.getFullYear(), base.getMonth(), workDay);
          const assignedDate = new Date(workDate);
          assignedDate.setDate(workDate.getDate() - (5 + Math.floor(rand() * 8)));

          // ステータスを日付に応じて決定（過去日は進行、未来日はアサイン中心）
          const daysDiff = Math.floor((workDate - today) / 86400000);
          let status, mprReceivedDate = null, shippingFee = null, receiptName = null, returnedDate = null;

          if (daysDiff <= -3) {
            // 作業日が3日以上前 → ほぼ完了 or 返送済
            const r = rand();
            status = r < 0.7 ? 'completed' : (r < 0.9 ? 'returned' : 'work_done');
            mprReceivedDate = addDays(workDate, -2 - Math.floor(rand() * 2));
            if (status !== 'work_done') {
              shippingFee = 800 + Math.floor(rand() * 1700);
              receiptName = `receipt_${staff.id}_${serial}.jpg`;
              returnedDate = addDays(workDate, 1 + Math.floor(rand() * 4));
            }
          } else if (daysDiff <= 0) {
            // 作業日が今日〜2日前
            const r = rand();
            status = r < 0.5 ? 'work_done' : 'mpr_received';
            mprReceivedDate = addDays(workDate, -2 - Math.floor(rand() * 2));
          } else if (daysDiff <= 3) {
            // 直近の作業日 → MPR受取 or 未受取（アラート対象になりうる）
            status = rand() < 0.55 ? 'mpr_received' : 'assigned';
            if (status === 'mpr_received') mprReceivedDate = addDays(today, -Math.floor(rand() * 2));
          } else {
            // 未来 → アサイン中心
            status = rand() < 0.25 ? 'mpr_received' : 'assigned';
            if (status === 'mpr_received') mprReceivedDate = addDays(today, -Math.floor(rand() * 3));
          }

          tasks.push({
            id: `OES-${base.getFullYear()}${pad2(base.getMonth()+1)}-${pad4(serial++)}`,
            staffId: staff.id,
            staffName: staff.name,
            storeName: tpl.name.replace('{city}', city.replace(/(.+区|.+市)$/, '')).replace('{branch}', branch),
            storeAddress: `${region.pref}${city}${street}`,
            assignedDate: ymd(assignedDate),
            workDate: ymd(workDate),
            status,
            mprReceivedDate: mprReceivedDate ? ymd(mprReceivedDate) : null,
            shippingFee,
            receiptName,
            receiptDataUrl: null,
            returnedDate: returnedDate ? ymd(returnedDate) : null,
            note: '',
          });
        }
      });
    }
    return tasks.sort((a, b) => a.workDate.localeCompare(b.workDate));
  }

  /* ---------- Date Utils ---------- */
  function pad2(n) { return String(n).padStart(2, '0'); }
  function pad4(n) { return String(n).padStart(4, '0'); }
  function ymd(d) { return `${d.getFullYear()}-${pad2(d.getMonth()+1)}-${pad2(d.getDate())}`; }
  function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
  function parseYmd(s) { const [y, m, d] = s.split('-').map(Number); return new Date(y, m-1, d); }
  function daysBetween(a, b) { return Math.floor((parseYmd(a) - parseYmd(b)) / 86400000); }
  function todayYmd() { return ymd(new Date()); }
  function fmtJpDate(s) {
    if (!s) return '—';
    const d = parseYmd(s);
    const w = ['日','月','火','水','木','金','土'][d.getDay()];
    return `${d.getMonth()+1}/${d.getDate()}(${w})`;
  }
  function fmtMonth(d) { return `${d.getFullYear()}年${d.getMonth()+1}月`; }
  function monthKey(s) { return s.slice(0, 7); }

  /* ---------- App State ---------- */
  const state = {
    role: 'staff',
    tasks: generateTasks(),
    currentStaffId: STAFFS[0].id,
    staffStatusFilter: 'all',
    adminMonth: monthKey(todayYmd()),
    adminStaffId: STAFFS[0].id,
  };

  /* ---------- Alert detection ----------
   * 作業日まで2日以内 かつ MPR未受取 → アラート
   */
  function isAlert(t) {
    if (t.status !== 'assigned') return false;
    const diff = daysBetween(t.workDate, todayYmd());
    return diff >= 0 && diff <= 2;
  }

  /* ---------- Renderers: Staff View ---------- */
  function renderStaffSelect() {
    const sel = document.getElementById('staffSelect');
    sel.innerHTML = STAFFS.map(s =>
      `<option value="${s.id}">${s.name}（${s.role}）</option>`
    ).join('');
    sel.value = state.currentStaffId;
  }

  function renderStaffSummary() {
    const wrap = document.getElementById('staffSummary');
    const month = monthKey(todayYmd());
    const mine = state.tasks.filter(t => t.staffId === state.currentStaffId && monthKey(t.workDate) === month);
    const completed = mine.filter(t => t.status === 'completed' || t.status === 'returned').length;
    const inProgress = mine.filter(t => !['completed'].includes(t.status)).length;
    const totalFee = mine.reduce((s, t) => s + (t.shippingFee || 0), 0);

    wrap.innerHTML = `
      <div class="oes-summary-card"><span class="label">今月の総件数</span><span class="value">${mine.length}</span></div>
      <div class="oes-summary-card"><span class="label">進行中</span><span class="value">${inProgress}</span></div>
      <div class="oes-summary-card"><span class="label">完了/返送済</span><span class="value">${completed}</span></div>
      <div class="oes-summary-card"><span class="label">今月の送料合計</span><span class="value">¥${totalFee.toLocaleString()}</span></div>
    `;
  }

  function renderStaffAlerts() {
    const wrap = document.getElementById('staffAlerts');
    const alerts = state.tasks.filter(t => t.staffId === state.currentStaffId && isAlert(t));
    if (!alerts.length) { wrap.innerHTML = ''; return; }
    wrap.innerHTML = alerts.map(t => {
      const diff = daysBetween(t.workDate, todayYmd());
      const when = diff === 0 ? '本日' : `あと${diff}日`;
      return `<div class="oes-alert">⚠️ 【MPR未受取】 ${t.storeName}（作業日 ${fmtJpDate(t.workDate)} / ${when}）— セイコー配送状況を確認してください</div>`;
    }).join('');
  }

  function flowHtml(status) {
    const cur = STATUS[status].step;
    return `<div class="oes-flow">` + FLOW_STEPS.map((s, i) => {
      const stepNo = i + 1;
      const cls = stepNo < cur ? 'done' : (stepNo === cur ? 'active' : '');
      const arrow = i < FLOW_STEPS.length - 1 ? `<span class="oes-flow-arrow">▶</span>` : '';
      return `<span class="oes-flow-step ${cls}">${s.label}</span>${arrow}`;
    }).join('') + `</div>`;
  }

  function taskCardHtml(t, opts = {}) {
    const alert = isAlert(t);
    const showStaff = !!opts.showStaff;
    return `
      <article class="oes-task-card ${alert ? 'alert' : ''}" data-task-id="${t.id}">
        <div class="oes-task-head">
          <div>
            <div class="oes-task-id">${t.id}${showStaff ? ` / ${t.staffName}` : ''}</div>
            <div class="oes-task-store">${t.storeName}</div>
            <div class="oes-task-addr">📍 ${t.storeAddress}</div>
          </div>
          <span class="oes-badge ${t.status}">${STATUS[t.status].label}</span>
        </div>
        <div class="oes-task-meta">
          <div><span class="key">作業日:</span><b>${fmtJpDate(t.workDate)}</b></div>
          <div><span class="key">アサイン:</span>${fmtJpDate(t.assignedDate)}</div>
          <div><span class="key">MPR受取:</span>${fmtJpDate(t.mprReceivedDate)}</div>
          ${t.shippingFee != null ? `<div><span class="key">送料:</span>¥${t.shippingFee.toLocaleString()}</div>` : ''}
        </div>
        ${flowHtml(t.status)}
        <div class="oes-task-actions">
          <button class="oes-btn oes-btn-sm" data-action="detail" data-id="${t.id}">詳細</button>
          ${actionButtonsHtml(t)}
        </div>
      </article>
    `;
  }

  function actionButtonsHtml(t) {
    switch (t.status) {
      case 'assigned':
        return `<button class="oes-btn oes-btn-sm oes-btn-primary" data-action="mpr" data-id="${t.id}">📦 MPR受取報告</button>`;
      case 'mpr_received':
        return `<button class="oes-btn oes-btn-sm oes-btn-primary" data-action="work" data-id="${t.id}">🛠 作業完了</button>`;
      case 'work_done':
        return `<button class="oes-btn oes-btn-sm oes-btn-primary" data-action="return" data-id="${t.id}">🚚 返送・送料登録</button>`;
      case 'returned':
        return `<button class="oes-btn oes-btn-sm oes-btn-success" data-action="complete" data-id="${t.id}">✅ 完了にする</button>`;
      default:
        return '';
    }
  }

  function renderStaffTasks() {
    const wrap = document.getElementById('staffTaskList');
    const filter = state.staffStatusFilter;
    let list = state.tasks.filter(t => t.staffId === state.currentStaffId);
    if (filter !== 'all') {
      if (filter === 'active') list = list.filter(t => t.status !== 'completed');
      else list = list.filter(t => t.status === filter);
    }
    list.sort((a, b) => a.workDate.localeCompare(b.workDate));
    if (!list.length) {
      wrap.innerHTML = `<div class="oes-info-block">該当するタスクはありません。</div>`;
      return;
    }
    wrap.innerHTML = list.map(t => taskCardHtml(t)).join('');
  }

  /* ---------- Renderers: Admin View ---------- */
  function buildMonthOptions() {
    const sel = document.getElementById('adminMonthSelect');
    const months = Array.from(new Set(state.tasks.map(t => monthKey(t.workDate)))).sort().reverse();
    sel.innerHTML = months.map(m => {
      const [y, mo] = m.split('-');
      return `<option value="${m}">${y}年${parseInt(mo,10)}月</option>`;
    }).join('');
    sel.value = state.adminMonth;
  }

  function buildAdminStaffOptions() {
    const sel = document.getElementById('adminStaffSelect');
    sel.innerHTML = STAFFS.map(s => `<option value="${s.id}">${s.name}</option>`).join('');
    sel.value = state.adminStaffId;
  }

  function renderAdminAlerts() {
    const wrap = document.getElementById('adminAlerts');
    const alerts = state.tasks.filter(isAlert);
    if (!alerts.length) { wrap.innerHTML = ''; return; }
    const grouped = {};
    alerts.forEach(t => { (grouped[t.staffName] = grouped[t.staffName] || []).push(t); });
    wrap.innerHTML = Object.entries(grouped).map(([name, list]) =>
      `<div class="oes-alert">⚠️ ${name} さん：MPR未受取が ${list.length} 件あります（最直近 ${fmtJpDate(list[0].workDate)}）</div>`
    ).join('');
  }

  function renderAdminSummary() {
    const wrap = document.getElementById('adminStaffSummary');
    const month = state.adminMonth;
    wrap.innerHTML = STAFFS.map(s => {
      const tasks = state.tasks.filter(t => t.staffId === s.id && monthKey(t.workDate) === month);
      const done = tasks.filter(t => t.status === 'completed' || t.status === 'returned').length;
      const inProg = tasks.filter(t => t.status !== 'completed').length;
      const fee = tasks.reduce((sum, t) => sum + (t.shippingFee || 0), 0);
      const alertCnt = tasks.filter(isAlert).length;
      const pct = tasks.length ? Math.round((done / tasks.length) * 100) : 0;
      return `
        <div class="oes-admin-card">
          <div class="name">${s.name}<span class="role">${s.role}</span></div>
          <div class="oes-mini-stats">
            <div>件数<b>${tasks.length}</b></div>
            <div>進行中<b>${inProg}</b></div>
            <div>完了<b>${done}</b></div>
          </div>
          <div class="oes-progress-bar"><span style="width:${pct}%"></span></div>
          <div class="oes-fee-line"><span>月間送料合計</span><b>¥${fee.toLocaleString()}</b></div>
          ${alertCnt ? `<div class="oes-fee-line" style="color:var(--c-danger)"><span>⚠️ 未受取アラート</span><b>${alertCnt}件</b></div>` : ''}
        </div>
      `;
    }).join('');
  }

  function renderAdminStaffDetail() {
    const wrap = document.getElementById('adminStaffDetail');
    const list = state.tasks
      .filter(t => t.staffId === state.adminStaffId && monthKey(t.workDate) === state.adminMonth)
      .sort((a, b) => a.workDate.localeCompare(b.workDate));
    if (!list.length) {
      wrap.innerHTML = `<div class="oes-info-block">対象月のタスクはありません。</div>`;
      return;
    }
    wrap.innerHTML = list.map(t => taskCardHtml(t, { showStaff: true })).join('');
  }

  /* ---------- Modal ---------- */
  function openTaskModal(taskId, mode) {
    try {
      _openTaskModal(taskId, mode);
    } catch (err) {
      console.error('[OES] openTaskModal failed:', err);
      const bodyEl = document.getElementById('modalBody');
      if (bodyEl) bodyEl.innerHTML = `<div class="oes-alert">表示エラー: ${err.message}<br><small>${(err.stack||'').slice(0,200)}</small></div>`;
      const m = document.getElementById('taskModal');
      if (m) { m.hidden = false; m.setAttribute('aria-hidden', 'false'); }
    }
  }
  function _openTaskModal(taskId, mode) {
    const t = state.tasks.find(x => x.id === taskId);
    if (!t) { console.warn('[OES] task not found:', taskId); return; }
    const titleEl = document.getElementById('modalTitle');
    const bodyEl = document.getElementById('modalBody');
    if (!titleEl || !bodyEl) { console.error('[OES] modal elements missing'); return; }

    const infoHtml = `
      <div class="oes-info-block">
        <div class="row"><span class="k">ID</span><span class="v">${t.id}</span></div>
        <div class="row"><span class="k">店舗</span><span class="v">${t.storeName}</span></div>
        <div class="row"><span class="k">住所</span><span class="v">${t.storeAddress}</span></div>
        <div class="row"><span class="k">スタッフ</span><span class="v">${t.staffName}</span></div>
        <div class="row"><span class="k">アサイン日</span><span class="v">${fmtJpDate(t.assignedDate)}</span></div>
        <div class="row"><span class="k">作業日</span><span class="v">${fmtJpDate(t.workDate)}</span></div>
        <div class="row"><span class="k">ステータス</span><span class="v"><span class="oes-badge ${t.status}">${STATUS[t.status].label}</span></span></div>
      </div>
    `;

    if (mode === 'mpr') {
      titleEl.textContent = '📦 MPR受取報告';
      bodyEl.innerHTML = `
        ${infoHtml}
        <div class="oes-form-row">
          <label for="mprDate">受取日</label>
          <input type="date" id="mprDate" class="oes-input" value="${todayYmd()}">
        </div>
        <button class="oes-btn oes-btn-primary oes-btn-block" id="submitMpr">受取完了を報告</button>
      `;
      document.getElementById('submitMpr').onclick = () => {
        t.mprReceivedDate = document.getElementById('mprDate').value || todayYmd();
        t.status = 'mpr_received';
        closeModal(); rerender();
      };
    } else if (mode === 'work') {
      titleEl.textContent = '🛠 当日作業完了';
      bodyEl.innerHTML = `
        ${infoHtml}
        <div class="oes-form-row">
          <label for="workNote">作業メモ（任意）</label>
          <textarea id="workNote" class="oes-textarea" placeholder="特記事項があれば入力">${t.note || ''}</textarea>
        </div>
        <button class="oes-btn oes-btn-primary oes-btn-block" id="submitWork">作業完了を報告</button>
      `;
      document.getElementById('submitWork').onclick = () => {
        t.note = document.getElementById('workNote').value;
        t.status = 'work_done';
        closeModal(); rerender();
      };
    } else if (mode === 'return') {
      titleEl.textContent = '🚚 撤去機 返送・送料登録';
      bodyEl.innerHTML = `
        ${infoHtml}
        <div class="oes-form-row two-col">
          <div>
            <label for="retDate">返送日</label>
            <input type="date" id="retDate" class="oes-input" value="${t.returnedDate || todayYmd()}">
          </div>
          <div>
            <label for="retFee">送料（円）</label>
            <input type="number" id="retFee" class="oes-input" min="0" step="10" placeholder="例: 1500" value="${t.shippingFee || ''}">
          </div>
        </div>
        <div class="oes-form-row">
          <label for="retReceipt">領収書画像</label>
          <input type="file" id="retReceipt" class="oes-input" accept="image/*" capture="environment">
          <div id="retReceiptPreview"></div>
        </div>
        <button class="oes-btn oes-btn-primary oes-btn-block" id="submitReturn">返送・送料を登録</button>
      `;
      const fileInput = document.getElementById('retReceipt');
      const preview = document.getElementById('retReceiptPreview');
      let pendingDataUrl = t.receiptDataUrl;
      let pendingName = t.receiptName;
      if (pendingDataUrl) preview.innerHTML = `<img src="${pendingDataUrl}" class="oes-receipt-thumb" alt="領収書">`;
      fileInput.onchange = (e) => {
        const f = e.target.files[0];
        if (!f) return;
        const reader = new FileReader();
        reader.onload = () => {
          pendingDataUrl = reader.result;
          pendingName = f.name;
          preview.innerHTML = `<img src="${pendingDataUrl}" class="oes-receipt-thumb" alt="領収書">`;
        };
        reader.readAsDataURL(f);
      };
      document.getElementById('submitReturn').onclick = () => {
        const fee = parseInt(document.getElementById('retFee').value, 10);
        if (!fee || fee <= 0) { alert('送料を入力してください'); return; }
        if (!pendingDataUrl) { if (!confirm('領収書画像が未添付です。このまま登録しますか？')) return; }
        t.shippingFee = fee;
        t.returnedDate = document.getElementById('retDate').value || todayYmd();
        t.receiptDataUrl = pendingDataUrl;
        t.receiptName = pendingName;
        t.status = 'returned';
        closeModal(); rerender();
      };
    } else if (mode === 'complete') {
      titleEl.textContent = '✅ タスク完了';
      bodyEl.innerHTML = `
        ${infoHtml}
        <p>このタスクを「完了」にしてよろしいですか？</p>
        <button class="oes-btn oes-btn-success oes-btn-block" id="submitComplete">完了にする</button>
      `;
      document.getElementById('submitComplete').onclick = () => {
        t.status = 'completed';
        closeModal(); rerender();
      };
    } else {
      // detail
      titleEl.textContent = 'タスク詳細';
      const receiptHtml = t.receiptDataUrl
        ? `<img src="${t.receiptDataUrl}" class="oes-receipt-thumb" alt="領収書">`
        : (t.receiptName ? `<span style="color:var(--c-muted)">📎 ${t.receiptName}（モックデータ）</span>` : '<span style="color:var(--c-muted)">なし</span>');
      bodyEl.innerHTML = `
        ${infoHtml}
        ${flowHtml(t.status)}
        <div class="oes-info-block" style="margin-top:14px">
          <div class="row"><span class="k">MPR受取日</span><span class="v">${fmtJpDate(t.mprReceivedDate)}</span></div>
          <div class="row"><span class="k">返送日</span><span class="v">${fmtJpDate(t.returnedDate)}</span></div>
          <div class="row"><span class="k">送料</span><span class="v">${t.shippingFee != null ? '¥' + t.shippingFee.toLocaleString() : '—'}</span></div>
          <div class="row"><span class="k">作業メモ</span><span class="v">${t.note || '—'}</span></div>
        </div>
        <div style="margin-top:8px">
          <div class="oes-label" style="margin-bottom:4px">領収書</div>
          ${receiptHtml}
        </div>
      `;
    }

    const m = document.getElementById('taskModal');
    m.hidden = false;
    m.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  }
  function closeModal() {
    const m = document.getElementById('taskModal');
    m.hidden = true;
    m.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  }

  /* ---------- Excel Export ---------- */
  function exportExcel() {
    if (typeof XLSX === 'undefined') {
      alert('Excel出力ライブラリの読み込みに失敗しました。ネットワーク状況をご確認ください。');
      return;
    }
    const month = state.adminMonth;
    const monthlyTasks = state.tasks.filter(t => monthKey(t.workDate) === month);

    // Sheet1: タスク一覧
    const taskRows = monthlyTasks.map(t => ({
      'タスクID': t.id,
      'スタッフ': t.staffName,
      '店舗名': t.storeName,
      '住所': t.storeAddress,
      'アサイン日': t.assignedDate,
      '作業日': t.workDate,
      'MPR受取日': t.mprReceivedDate || '',
      '返送日': t.returnedDate || '',
      '送料(円)': t.shippingFee || 0,
      'ステータス': STATUS[t.status].label,
      '領収書': t.receiptName || '',
      '備考': t.note || '',
    }));

    // Sheet2: スタッフ別月次集計
    const summaryRows = STAFFS.map(s => {
      const list = monthlyTasks.filter(t => t.staffId === s.id);
      const done = list.filter(t => t.status === 'completed' || t.status === 'returned').length;
      const fee = list.reduce((sum, t) => sum + (t.shippingFee || 0), 0);
      return {
        'スタッフID': s.id,
        'スタッフ名': s.name,
        'エリア': s.role,
        '総件数': list.length,
        '完了件数': done,
        '未着アラート件数': list.filter(isAlert).length,
        '送料合計(円)': fee,
      };
    });

    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(taskRows),    'タスク一覧');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(summaryRows), 'スタッフ別集計');
    XLSX.writeFile(wb, `OESタスク管理_${month}.xlsx`);
  }

  /* ---------- Render orchestration ---------- */
  function rerender() {
    if (state.role === 'staff') {
      renderStaffSummary();
      renderStaffAlerts();
      renderStaffTasks();
    } else {
      renderAdminAlerts();
      renderAdminSummary();
      renderAdminStaffDetail();
    }
  }

  function switchRole(role) {
    state.role = role;
    document.querySelectorAll('.oes-role-btn').forEach(b => {
      const active = b.dataset.role === role;
      b.classList.toggle('active', active);
      b.setAttribute('aria-selected', active);
    });
    document.getElementById('staffView').hidden = role !== 'staff';
    document.getElementById('adminView').hidden = role !== 'admin';
    rerender();
  }

  /* ---------- Event Wiring ---------- */
  function wireEvents() {
    document.querySelectorAll('.oes-role-btn').forEach(b => {
      b.addEventListener('click', () => switchRole(b.dataset.role));
    });
    document.getElementById('staffSelect').addEventListener('change', e => {
      state.currentStaffId = e.target.value; rerender();
    });
    document.getElementById('staffStatusFilter').addEventListener('change', e => {
      state.staffStatusFilter = e.target.value; renderStaffTasks();
    });
    document.getElementById('adminMonthSelect').addEventListener('change', e => {
      state.adminMonth = e.target.value; rerender();
    });
    document.getElementById('adminStaffSelect').addEventListener('change', e => {
      state.adminStaffId = e.target.value; renderAdminStaffDetail();
    });
    document.getElementById('exportExcelBtn').addEventListener('click', exportExcel);

    document.body.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-action]');
      if (btn) {
        const action = btn.dataset.action;
        const id = btn.dataset.id;
        openTaskModal(id, action === 'detail' ? 'detail' : action);
        return;
      }
      if (e.target.closest('[data-close]')) closeModal();
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeModal();
    });
  }

  /* ---------- Init ---------- */
  function init() {
    renderStaffSelect();
    buildMonthOptions();
    buildAdminStaffOptions();
    wireEvents();
    rerender();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

// SPA ルーター + レンダラ

const $app = document.getElementById('app');
const $nav = document.getElementById('site-nav');

let _torikumiDay = null;

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// 漢字にフリガナ（ルビ）を振る
function rubyHtml(kanji, kana) {
  if (!kana) return escapeHtml(kanji);
  return `<ruby>${escapeHtml(kanji)}<rt>${escapeHtml(kana)}</rt></ruby>`;
}

// 四股名（上）＋下の名前（名乗り）。通向けにフルの四股名をフリガナ付きで表示する。
function fullShikonaRuby(rikishi) {
  const given = getShikonaGiven(rikishi.id);
  const givenKana = getShikonaGivenKana(rikishi.id);
  let html = rubyHtml(rikishi.name, rikishi.nameKana);
  if (given) {
    html += `<span class="shikona-given">${rubyHtml(given, givenKana)}</span>`;
  }
  return html;
}

function rankBadgeClass(rank) {
  if (rank === '横綱') return 'rank-yokozuna';
  if (rank === '大関') return 'rank-ozeki';
  if (rank === '関脇') return 'rank-sekiwake';
  if (rank === '小結') return 'rank-komusubi';
  return '';
}

function rankBadge(rikishi) {
  const cls = rankBadgeClass(rikishi.rank);
  return `<span class="badge ${cls}">${escapeHtml(rikishi.rank)}</span>`;
}

function sideBadge(rikishi) {
  if (rikishi.side === '東') return `<span class="badge east">東</span>`;
  if (rikishi.side === '西') return `<span class="badge west">西</span>`;
  return '';
}

function countryBadge(rikishi) {
  if (rikishi.birthplaceCountry && rikishi.birthplaceCountry !== '日本') {
    return `<span class="badge country">${escapeHtml(rikishi.birthplaceCountry)}</span>`;
  }
  return '';
}

// ========== 次回番付発表のお知らせ ==========
function nextBanzukeNoticeHtml() {
  if (typeof BANZUKE_SCHEDULE === 'undefined' || !BANZUKE_SCHEDULE.length) return '';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const upcoming = BANZUKE_SCHEDULE
    .filter(b => !b.banzukeAnnounced)
    .map(b => ({ ...b, dateObj: new Date(b.banzukeDate) }))
    .sort((a, b) => a.dateObj - b.dateObj)[0];
  if (!upcoming) return '';
  const diff = Math.ceil((upcoming.dateObj - today) / (24 * 3600 * 1000));
  const formatted = formatJaDate(upcoming.banzukeDate);
  let countdown;
  if (diff > 0) countdown = `あと ${diff} 日`;
  else if (diff === 0) countdown = '本日発表';
  else countdown = `${-diff} 日前に発表済み（データ更新待ち）`;
  return `
    <div class="banzuke-notice">
      <div class="banzuke-notice-label">次回 番付発表</div>
      <div class="banzuke-notice-main">
        <span class="banzuke-notice-date">${escapeHtml(formatted)}</span>
        <span class="banzuke-notice-countdown">${escapeHtml(countdown)}</span>
      </div>
      <div class="banzuke-notice-sub">${escapeHtml(upcoming.name)}（${escapeHtml(upcoming.basho)}／${escapeHtml(upcoming.venue)}）</div>
    </div>
  `;
}

function formatJaDate(ymd) {
  const d = new Date(ymd);
  if (isNaN(d)) return ymd;
  const days = ['日', '月', '火', '水', '木', '金', '土'];
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日（${days[d.getDay()]}）`;
}

// ========== Top（番付）==========
function renderBanzuke() {
  const sanyaku = [
    { rank: '横綱', top: true },
    { rank: '大関', top: true },
    { rank: '関脇', top: false },
    { rank: '小結', top: false },
  ];

  let html = `
    <h1 class="page-title">現在の番付（幕内）</h1>
    <p class="lead">${escapeHtml(SITE_META.dataAsOf)}時点の幕内番付です。</p>
    ${nextBanzukeNoticeHtml()}
    <div class="banzuke-section">
  `;

  for (const sy of sanyaku) {
    const east = RIKISHI.filter(r => !r.retired && r.rank === sy.rank && r.side === '東');
    const west = RIKISHI.filter(r => !r.retired && r.rank === sy.rank && r.side === '西');
    if (!east.length && !west.length) continue;
    html += banzukeRow(east, sy.rank, west, sy.top);
  }

  // 前頭
  for (let i = 1; i <= 17; i++) {
    const rank = `前頭${i}`;
    const east = RIKISHI.filter(r => !r.retired && r.rank === rank && r.side === '東');
    const west = RIKISHI.filter(r => !r.retired && r.rank === rank && r.side === '西');
    if (!east.length && !west.length) continue;
    html += banzukeRow(east, rank, west, false);
  }

  html += `</div>`;

  // 直近場所結果
  const latest = TOURNAMENTS[0];
  if (latest) {
    const winner = getRikishiById(latest.yushoMakuuchi.rikishiId);
    html += `
      <h2 class="section-title">直近場所の結果</h2>
      <div class="tournament-card">
        <h3><a href="#/tournaments">${escapeHtml(latest.name)}</a></h3>
        <div class="meta">${escapeHtml(latest.venue)} ／ ${escapeHtml(latest.period)}</div>
        <div class="yusho">
          <div class="label">幕内優勝</div>
          <div class="winner">
            ${winner ? `<a href="#/rikishi/${winner.id}">${fullShikonaRuby(winner)}</a>` : '?'} （${escapeHtml(latest.yushoMakuuchi.record)}）
          </div>
          ${latest.yushoMakuuchi.note ? `<div class="meta">${escapeHtml(latest.yushoMakuuchi.note)}</div>` : ''}
        </div>
        <p>${escapeHtml(latest.summary)}</p>
      </div>
    `;
  }

  $app.innerHTML = html;
}

function banzukeRow(eastList, rank, westList, isTop) {
  const eastHtml = eastList.length
    ? eastList.map(r => banzukeCell(r, 'east')).join('')
    : '<div class="banzuke-cell banzuke-empty">―</div>';
  const westHtml = westList.length
    ? westList.map(r => banzukeCell(r, 'west')).join('')
    : '<div class="banzuke-cell banzuke-empty">―</div>';
  return `
    <div class="banzuke-row">
      <div class="banzuke-side">${eastHtml}</div>
      <div class="banzuke-rank ${isTop ? 'top' : ''}">${escapeHtml(rank)}</div>
      <div class="banzuke-side">${westHtml}</div>
    </div>
  `;
}

function banzukeCell(rikishi, sideClass) {
  const stable = getStableById(rikishi.stableId);
  const sideLabel = rikishi.side === '東' ? '東' : '西';
  return `
    <div class="banzuke-cell ${sideClass}">
      <span class="side-tag ${sideClass}">${sideLabel}</span>
      <div class="name"><a href="#/rikishi/${rikishi.id}">${fullShikonaRuby(rikishi)}</a></div>
      <div class="sub">${escapeHtml(rikishi.birthplace)}</div>
      <div class="sub">${stable ? `<a href="#/stable/${stable.id}">${escapeHtml(stable.name)}</a>` : ''}</div>
    </div>
  `;
}

// ========== 力士一覧 ==========
let rikishiFilter = { q: '', stable: '', birthCountry: '', rank: '' };

function renderRikishiList() {
  const stableOpts = STABLES
    .map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`)
    .join('');
  const countries = [...new Set(RIKISHI.map(r => r.birthplaceCountry))];
  const countryOpts = countries
    .map(c => `<option value="${c}">${escapeHtml(c)}</option>`)
    .join('');
  const rankDropOrder = r => {
    if (r === '横綱') return 1;
    if (r === '大関') return 2;
    if (r === '関脇') return 3;
    if (r === '小結') return 4;
    const m = r.match(/前頭(\d+)/);
    return m ? 5000 + parseInt(m[1], 10) : 9999;
  };
  const ranks = [...new Set(RIKISHI.map(r => r.rank))].sort((a, b) => rankDropOrder(a) - rankDropOrder(b));
  const rankOpts = ranks
    .map(r => `<option value="${r}">${escapeHtml(r)}</option>`)
    .join('');

  $app.innerHTML = `
    <h1 class="page-title">力士一覧</h1>
    <p class="lead">幕内力士の一覧です。検索・絞り込みができます。</p>
    <div class="toolbar">
      <input type="search" id="f-q" placeholder="四股名・出身地で検索" value="${escapeHtml(rikishiFilter.q)}">
      <select id="f-rank">
        <option value="">階級で絞り込み</option>
        ${rankOpts}
      </select>
      <select id="f-stable">
        <option value="">部屋で絞り込み</option>
        ${stableOpts}
      </select>
      <select id="f-country">
        <option value="">出身国で絞り込み</option>
        ${countryOpts}
      </select>
      <span class="result-count" id="result-count"></span>
    </div>
    <div class="card-grid" id="rikishi-grid"></div>
  `;

  // 値復元
  document.getElementById('f-rank').value = rikishiFilter.rank;
  document.getElementById('f-stable').value = rikishiFilter.stable;
  document.getElementById('f-country').value = rikishiFilter.birthCountry;

  const apply = () => {
    rikishiFilter.q = document.getElementById('f-q').value.trim();
    rikishiFilter.rank = document.getElementById('f-rank').value;
    rikishiFilter.stable = document.getElementById('f-stable').value;
    rikishiFilter.birthCountry = document.getElementById('f-country').value;
    renderRikishiGrid();
  };
  document.getElementById('f-q').addEventListener('input', apply);
  document.getElementById('f-rank').addEventListener('change', apply);
  document.getElementById('f-stable').addEventListener('change', apply);
  document.getElementById('f-country').addEventListener('change', apply);

  renderRikishiGrid();
}

function renderRikishiGrid() {
  const q = rikishiFilter.q.toLowerCase();
  const list = sortByRank(RIKISHI.filter(r => {
    if (r.retired) return false;
    if (rikishiFilter.rank && r.rank !== rikishiFilter.rank) return false;
    if (rikishiFilter.stable && r.stableId !== rikishiFilter.stable) return false;
    if (rikishiFilter.birthCountry && r.birthplaceCountry !== rikishiFilter.birthCountry) return false;
    if (q) {
      const hay = `${r.name} ${r.nameKana || ''} ${r.nameRomaji || ''} ${r.birthplace}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }));

  document.getElementById('result-count').textContent = `${list.length}名`;
  const $grid = document.getElementById('rikishi-grid');
  if (!list.length) {
    $grid.innerHTML = `<div class="empty-state">該当する力士が見つかりませんでした。</div>`;
    return;
  }
  $grid.innerHTML = list.map(r => {
    const stable = getStableById(r.stableId);
    const nickname = getNickname(r.id);
    return `
      <div class="card">
        <h3><a href="#/rikishi/${r.id}">${fullShikonaRuby(r)}</a></h3>
        <div class="meta">${escapeHtml(r.nameRomaji || '')}</div>
        ${nickname ? `<div class="nickname">「${escapeHtml(nickname)}」</div>` : ''}
        <div class="badge-row">
          ${rankBadge(r)}
          ${sideBadge(r)}
          ${countryBadge(r)}
        </div>
        <div class="meta" style="margin-top:8px;">
          ${escapeHtml(r.birthplace)}<br>
          ${stable ? `<a href="#/stable/${stable.id}">${escapeHtml(stable.name)}</a>` : ''}
        </div>
      </div>
    `;
  }).join('');
}

// ========== 力士詳細 ==========
function renderRikishiDetail(id) {
  const r = getRikishiById(id);
  if (!r) return render404();
  const stable = getStableById(r.stableId);
  const mates = stable ? getRikishiByStable(stable.id).filter(m => m.id !== r.id) : [];

  const winsTournament = TOURNAMENTS.filter(t => t.yushoMakuuchi.rikishiId === r.id);
  const sanshoCount =
    (r.sansho.shukunsho || 0) + (r.sansho.kantosho || 0) + (r.sansho.ginosho || 0);

  $app.innerHTML = `
    <div class="breadcrumb">
      <a href="#/">トップ</a> ＞ <a href="#/rikishi">力士一覧</a> ＞ ${escapeHtml(r.name)}
    </div>
    <div class="detail-hero">
      <div class="name-large">${fullShikonaRuby(r)}</div>
      <div class="name-kana">${escapeHtml(r.nameRomaji || '')}</div>
      <div class="badge-row">
        ${rankBadge(r)}
        ${sideBadge(r)}
        ${countryBadge(r)}
        ${stable ? `<span class="badge">${escapeHtml(stable.name)}</span>` : ''}
      </div>
    </div>

    <h2 class="section-title">基本情報</h2>
    <table class="info-table">
      <tr><th>本名</th><td>${escapeHtml(r.realName || '―')}</td></tr>
      <tr><th>ニックネーム</th><td>${getNickname(r.id) ? escapeHtml(getNickname(r.id)) : '<span class="muted">なし</span>'}</td></tr>
      <tr><th>現在の番付</th><td>${escapeHtml(r.side + ' ' + r.rank)}</td></tr>
      <tr><th>所属部屋</th><td>${stable ? `<a href="#/stable/${stable.id}">${escapeHtml(stable.name)}</a>（${escapeHtml(stable.ichimon || '')}）` : '―'}</td></tr>
      <tr><th>出身地</th><td>${escapeHtml(r.birthplace)}</td></tr>
      <tr><th>生年月日</th><td>${escapeHtml(r.birthdate)} （${calcAge(r.birthdate)}歳）</td></tr>
      <tr><th>身長 / 体重</th><td>${r.height} cm ／ ${r.weight} kg</td></tr>
      <tr><th>初土俵</th><td>${escapeHtml(r.debut)} （${escapeHtml(r.debutRank || '')}）</td></tr>
      <tr><th>得意技</th><td>${(r.favoriteKimarite || []).map(k => `<span class="tag-link">${escapeHtml(k)}</span>`).join(' ')}</td></tr>
    </table>

    <h2 class="section-title">通算成績・受賞</h2>
    <table class="info-table">
      <tr><th>幕内最高優勝</th><td>${r.yusho} 回</td></tr>
      <tr><th>殊勲賞</th><td>${r.sansho.shukunsho || 0} 回</td></tr>
      <tr><th>敢闘賞</th><td>${r.sansho.kantosho || 0} 回</td></tr>
      <tr><th>技能賞</th><td>${r.sansho.ginosho || 0} 回</td></tr>
      <tr><th>三賞合計</th><td>${sanshoCount} 回</td></tr>
      <tr><th>金星</th><td>${r.kinboshi || 0} 個</td></tr>
    </table>

    <h2 class="section-title">プロフィール</h2>
    <p>${escapeHtml(r.profile || '―')}</p>

    ${winsTournament.length ? `
      <h2 class="section-title">優勝した場所（記録）</h2>
      <ul>
        ${winsTournament.map(t => `
          <li><a href="#/tournaments">${escapeHtml(t.name)}</a> （${escapeHtml(t.yushoMakuuchi.record)}）${t.yushoMakuuchi.note ? ` ― ${escapeHtml(t.yushoMakuuchi.note)}` : ''}</li>
        `).join('')}
      </ul>
    ` : ''}

    ${stable ? `
      <h2 class="section-title">同部屋の力士</h2>
      ${mates.length ? `
        <ul class="rikishi-mini-list">
          ${sortByRank(mates).map(m => `
            <li>
              <a href="#/rikishi/${m.id}">${fullShikonaRuby(m)}</a>
              <span class="rank">${escapeHtml(m.rank)}</span>
            </li>
          `).join('')}
        </ul>
      ` : `<p>同部屋の他の幕内力士はいません。</p>`}
    ` : ''}
  `;
}

function calcAge(birthdate) {
  if (!birthdate) return '―';
  const today = new Date();
  const b = new Date(birthdate);
  let age = today.getFullYear() - b.getFullYear();
  const m = today.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && today.getDate() < b.getDate())) age--;
  return age;
}

// ========== 部屋一覧 ==========
let stableFilter = { q: '', ichimon: '' };

function renderStableList() {
  const ichimons = [...new Set(STABLES.map(s => s.ichimon).filter(Boolean))];
  const ichimonOpts = ichimons.map(i => `<option value="${i}">${escapeHtml(i)}</option>`).join('');

  $app.innerHTML = `
    <h1 class="page-title">部屋一覧</h1>
    <p class="lead">所属する部屋（相撲部屋）の一覧です。</p>
    <div class="toolbar">
      <input type="search" id="sf-q" placeholder="部屋名で検索" value="${escapeHtml(stableFilter.q)}">
      <select id="sf-ichimon">
        <option value="">一門で絞り込み</option>
        ${ichimonOpts}
      </select>
      <span class="result-count" id="result-count"></span>
    </div>
    <div class="card-grid" id="stable-grid"></div>
  `;

  document.getElementById('sf-ichimon').value = stableFilter.ichimon;
  const apply = () => {
    stableFilter.q = document.getElementById('sf-q').value.trim();
    stableFilter.ichimon = document.getElementById('sf-ichimon').value;
    renderStableGrid();
  };
  document.getElementById('sf-q').addEventListener('input', apply);
  document.getElementById('sf-ichimon').addEventListener('change', apply);

  renderStableGrid();
}

function renderStableGrid() {
  const q = stableFilter.q.toLowerCase();
  const list = STABLES.filter(s => {
    if (stableFilter.ichimon && s.ichimon !== stableFilter.ichimon) return false;
    if (q) {
      const hay = `${s.name} ${s.nameRomaji || ''} ${s.location || ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }).sort((a, b) => a.name.localeCompare(b.name, 'ja'));

  document.getElementById('result-count').textContent = `${list.length}部屋`;
  const $grid = document.getElementById('stable-grid');
  if (!list.length) {
    $grid.innerHTML = `<div class="empty-state">該当する部屋が見つかりませんでした。</div>`;
    return;
  }
  $grid.innerHTML = list.map(s => {
    const rikishiList = getRikishiByStable(s.id);
    return `
      <div class="card">
        <h3><a href="#/stable/${s.id}">${escapeHtml(s.name)}</a></h3>
        <div class="meta">${escapeHtml(s.ichimon || '')} ／ ${escapeHtml(s.location || '')}</div>
        <div class="badge-row">
          <span class="badge">関取 ${rikishiList.length}名</span>
        </div>
        <p class="meta" style="margin-top:8px;">${escapeHtml((s.description || '').slice(0, 60))}${(s.description || '').length > 60 ? '…' : ''}</p>
      </div>
    `;
  }).join('');
}

// ========== 部屋詳細 ==========
function renderStableDetail(id) {
  const s = getStableById(id);
  if (!s) return render404();
  const rikishiList = sortByRank(getRikishiByStable(s.id));
  const sameIchimon = STABLES
    .filter(x => x.ichimon === s.ichimon && x.id !== s.id);

  $app.innerHTML = `
    <div class="breadcrumb">
      <a href="#/">トップ</a> ＞ <a href="#/stables">部屋一覧</a> ＞ ${escapeHtml(s.name)}
    </div>
    <div class="detail-hero">
      <div class="name-large">${escapeHtml(s.name)}</div>
      <div class="name-kana">${escapeHtml(s.nameRomaji || '')}</div>
      <div class="badge-row">
        <span class="badge">${escapeHtml(s.ichimon || '―')}</span>
        <span class="badge">関取 ${rikishiList.length}名</span>
      </div>
    </div>

    <h2 class="section-title">部屋の概要</h2>
    <table class="info-table">
      <tr><th>所在地</th><td>${escapeHtml(s.location || '―')}</td></tr>
      <tr><th>師匠</th><td>${escapeHtml(s.master || '―')}</td></tr>
      <tr><th>創設</th><td>${escapeHtml(s.established || '―')}</td></tr>
      <tr><th>所属一門</th><td>${escapeHtml(s.ichimon || '―')}</td></tr>
    </table>
    <p>${escapeHtml(s.description || '')}</p>

    <h2 class="section-title">所属力士（幕内）</h2>
    ${rikishiList.length ? `
      <ul class="rikishi-mini-list">
        ${rikishiList.map(r => `
          <li>
            <a href="#/rikishi/${r.id}">${fullShikonaRuby(r)}</a>
            <span class="rank">${escapeHtml(r.rank)}</span>
          </li>
        `).join('')}
      </ul>
    ` : `<p>幕内所属力士のデータはありません。</p>`}

    ${sameIchimon.length ? `
      <h2 class="section-title">同じ一門の部屋</h2>
      <div class="card-grid">
        ${sameIchimon.map(x => `
          <div class="card">
            <h3><a href="#/stable/${x.id}">${escapeHtml(x.name)}</a></h3>
            <div class="meta">${escapeHtml(x.location || '')}</div>
          </div>
        `).join('')}
      </div>
    ` : ''}
  `;
}

// ========== 場所結果 ==========
function renderTournaments() {
  $app.innerHTML = `
    <h1 class="page-title">場所結果</h1>
    <p class="lead">直近の本場所結果です。</p>
    ${TOURNAMENTS.map(t => {
      const winner = getRikishiById(t.yushoMakuuchi.rikishiId);
      return `
        <div class="tournament-card">
          <h3>${escapeHtml(t.name)}</h3>
          <div class="meta">${escapeHtml(t.venue)} ／ ${escapeHtml(t.period)}</div>
          <div class="yusho">
            <div class="label">幕内優勝</div>
            <div class="winner">
              ${winner ? `<a href="#/rikishi/${winner.id}">${fullShikonaRuby(winner)}</a>` : '?'}
              （${escapeHtml(t.yushoMakuuchi.record)}）
            </div>
            ${t.yushoMakuuchi.note ? `<div class="meta">${escapeHtml(t.yushoMakuuchi.note)}</div>` : ''}
          </div>
          ${renderSansho(t.sansho)}
          <p>${escapeHtml(t.summary || '')}</p>
        </div>
      `;
    }).join('')}
  `;
}

function renderSansho(sansho) {
  if (!sansho) return '';
  const fmt = (label, ids) => {
    if (!ids || !ids.length) return '';
    const names = ids.map(id => {
      const r = getRikishiById(id);
      return r ? `<a href="#/rikishi/${r.id}">${escapeHtml(r.name)}</a>` : id;
    }).join('、');
    return `<span><strong>${label}</strong>${names}</span>`;
  };
  const inner = [
    fmt('殊勲賞', sansho.shukunsho),
    fmt('敢闘賞', sansho.kantosho),
    fmt('技能賞', sansho.ginosho),
  ].join('');
  if (!inner) return '';
  return `<div class="sansho">${inner}</div>`;
}

// ========== 巡業スケジュール ==========
function formatDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const w = ['日', '月', '火', '水', '木', '金', '土'][d.getDay()];
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日（${w}）`;
}

function renderJungyo() {
  const hasTentative = (typeof JUNGYO !== 'undefined') && JUNGYO.some(j => j.tentative);
  $app.innerHTML = `
    <h1 class="page-title">巡業スケジュール</h1>
    <p class="lead">本場所と本場所の間に各地で行われる地方巡業の日程です。</p>
    ${hasTentative ? `
      <div class="notice">
        ※「予定」と表示された巡業はサンプル・暫定日程です。公式日程が発表されたら更新してください（更新方法は <a href="#/about">サイトについて</a> を参照）。
      </div>
    ` : ''}
    ${(typeof JUNGYO === 'undefined' ? [] : JUNGYO).map(j => `
      <div class="tournament-card">
        <h3>${escapeHtml(j.name)} ${j.tentative ? '<span class="badge tentative">予定</span>' : ''}</h3>
        <div class="meta">${escapeHtml(j.period || '')}</div>
        ${j.note ? `<p>${escapeHtml(j.note)}</p>` : ''}
        ${j.stops && j.stops.length ? `
          <div class="table-scroll">
            <table class="info-table schedule-table">
              <thead>
                <tr><th>日付</th><th>都道府県</th><th>会場</th></tr>
              </thead>
              <tbody>
                ${j.stops.map(s => `
                  <tr>
                    <td>${escapeHtml(formatDate(s.date))}</td>
                    <td>${escapeHtml(s.pref || '')}</td>
                    <td>${escapeHtml(s.venue || '未定')}${s.event ? `（${escapeHtml(s.event)}）` : ''}</td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          </div>
        ` : '<p>開催地は未定です。</p>'}
      </div>
    `).join('')}
  `;
}

// ========== About ==========
function renderAbout() {
  const ichimons = [...new Set(STABLES.map(s => s.ichimon).filter(Boolean))];
  $app.innerHTML = `
    <h1 class="page-title">このサイトについて</h1>
    <p class="lead">日本の大相撲をひととおり眺められる、シンプルな閲覧サイトです。</p>

    <h2 class="section-title">収録内容</h2>
    <ul>
      <li>現在の番付（幕内）</li>
      <li>幕内力士（${RIKISHI.length}名）のプロフィール・四股名（下の名前）・通算成績・受賞歴</li>
      <li>相撲部屋（${STABLES.length}部屋）の概要と所属力士、一門</li>
      <li>直近の本場所結果</li>
      <li>巡業スケジュール</li>
      <li>同部屋・同一門のクロスリンク</li>
    </ul>

    <h2 class="section-title">大相撲の階級</h2>
    <table class="info-table">
      <tr><th>横綱</th><td>力士の最高位。降格はなく、引退となる。</td></tr>
      <tr><th>大関</th><td>横綱に次ぐ階級。2場所連続負け越しで関脇に陥落する。</td></tr>
      <tr><th>関脇</th><td>三役の一つ。優勝経験で大関昇進の可能性。</td></tr>
      <tr><th>小結</th><td>三役の一つ。三役の最下位。</td></tr>
      <tr><th>前頭</th><td>幕内の平幕力士。番付の枚数で序列がつく。</td></tr>
      <tr><th>十両</th><td>幕内の下、関取の最下位。給与あり。</td></tr>
      <tr><th>幕下以下</th><td>無給。番付は幕下・三段目・序二段・序ノ口の順。</td></tr>
    </table>

    <h2 class="section-title">一門について</h2>
    <p>大相撲には部屋を超えた集合体である「一門」があります。本サイト収録分の一門は以下です。</p>
    <ul>
      ${ichimons.map(i => `<li>${escapeHtml(i)}</li>`).join('')}
    </ul>

    <h2 class="section-title">データについて</h2>
    <p>${escapeHtml(SITE_META.note)}</p>
    <table class="info-table">
      <tr><th>収録時点</th><td>${escapeHtml(SITE_META.dataAsOf)}</td></tr>
      <tr><th>データ最終更新日</th><td>${escapeHtml(SITE_META.lastUpdated || '―')}</td></tr>
    </table>

    <h2 class="section-title">情報の更新について</h2>
    <p>最新の番付・成績・巡業日程に更新するには、<code>data.js</code> 内の各データを書き換えるだけです（プログラムの変更は不要）。</p>
    <ul>
      <li><strong>番付</strong>：各力士の <code>rank</code>（例: 横綱／大関／前頭3）と <code>side</code>（東／西）を変更</li>
      <li><strong>力士の追加</strong>：<code>RIKISHI</code> 配列に新しい力士を追加（<code>id</code> は他と重複しない英数字）</li>
      <li><strong>下の名前・フリガナ</strong>：<code>SHIKONA_GIVEN</code>（漢字）と <code>SHIKONA_GIVEN_KANA</code>（読み）に追加</li>
      <li><strong>ニックネーム</strong>：<code>NICKNAMES</code> に <code>id: '愛称'</code> を追加（未登録は「なし」と表示）</li>
      <li><strong>場所結果</strong>：<code>TOURNAMENTS</code> 配列の先頭に新しい場所を追加</li>
      <li><strong>巡業</strong>：<code>JUNGYO</code> 配列の各 <code>stops</code>（日付・都道府県・会場）を更新し、確定したら <code>tentative</code> を <code>false</code> に</li>
      <li>更新後は <code>SITE_META.lastUpdated</code> の日付も書き換え、ファイルをサーバへ再アップロード</li>
    </ul>
    <p>詳しい手順とテンプレートは、配布物に同梱の <code>UPDATE.md</code> をご覧ください。</p>
  `;
}

// ========== 番付編集（画面から編集→data.jsをダウンロード）==========
const RANK_OPTIONS = (() => {
  const opts = ['横綱', '大関', '関脇', '小結'];
  for (let i = 1; i <= 17; i++) opts.push(`前頭${i}`);
  opts.push('十両以下'); // 幕内から外れた場合の選択肢
  return opts;
})();

// 編集状態（メモリ上）。{rikishiId: {rank, side, retired}}
let banzukeEdits = {};

function renderEdit() {
  // RIKISHI を rank 順にソートして表示
  const sorted = sortByRank(RIKISHI);
  banzukeEdits = {};
  sorted.forEach(r => {
    banzukeEdits[r.id] = { rank: r.rank, side: r.side, retired: !!r.retired };
  });

  $app.innerHTML = `
    <h1 class="page-title">番付エディタ</h1>
    <p class="lead">
      画面上で番付を編集して、最新の <code>data.js</code> をダウンロードできます。
      ダウンロードしたファイルを <code>sumo/data.js</code> として上書きアップロード（FTP）するだけで、サイトに反映されます。
    </p>
    <div class="edit-toolbar">
      <label>
        データ最終更新日:
        <input type="date" id="edit-lastUpdated" value="${escapeHtml(SITE_META.lastUpdated || '')}">
      </label>
      <label>
        収録時点の説明:
        <input type="text" id="edit-dataAsOf" value="${escapeHtml(SITE_META.dataAsOf || '')}" size="40">
      </label>
      <button id="edit-download" class="primary">data.js をダウンロード</button>
    </div>
    <p class="edit-hint">
      ヒント：階級を「十両以下」にすると番付から外れます（プロフィールは残ります）。引退チェックを入れると番付・力士一覧から除外されます。
    </p>
    <div class="edit-table-wrapper">
      <table class="edit-table">
        <thead>
          <tr>
            <th>四股名</th>
            <th>所属部屋</th>
            <th>階級</th>
            <th>東/西</th>
            <th>引退</th>
          </tr>
        </thead>
        <tbody>
          ${sorted.map(r => {
            const stable = getStableById(r.stableId);
            return `
              <tr data-id="${escapeHtml(r.id)}">
                <td class="edit-name">${escapeHtml(r.name)}<span class="edit-id">${escapeHtml(r.id)}</span></td>
                <td class="edit-stable">${stable ? escapeHtml(stable.name) : '―'}</td>
                <td>
                  <select class="edit-rank">
                    ${RANK_OPTIONS.map(o => `<option value="${escapeHtml(o)}"${o === r.rank ? ' selected' : ''}>${escapeHtml(o)}</option>`).join('')}
                  </select>
                </td>
                <td>
                  <select class="edit-side">
                    <option value="東"${r.side === '東' ? ' selected' : ''}>東</option>
                    <option value="西"${r.side === '西' ? ' selected' : ''}>西</option>
                  </select>
                </td>
                <td class="edit-retired-cell">
                  <input type="checkbox" class="edit-retired"${r.retired ? ' checked' : ''}>
                </td>
              </tr>
            `;
          }).join('')}
        </tbody>
      </table>
    </div>
  `;

  // 値の収集
  $app.querySelectorAll('tr[data-id]').forEach(tr => {
    const id = tr.dataset.id;
    const rankSel = tr.querySelector('.edit-rank');
    const sideSel = tr.querySelector('.edit-side');
    const retChk = tr.querySelector('.edit-retired');
    const sync = () => {
      banzukeEdits[id] = { rank: rankSel.value, side: sideSel.value, retired: retChk.checked };
    };
    rankSel.addEventListener('change', sync);
    sideSel.addEventListener('change', sync);
    retChk.addEventListener('change', sync);
  });

  document.getElementById('edit-download').addEventListener('click', () => downloadUpdatedDataJs());
}

function downloadUpdatedDataJs() {
  // 元の data.js テキストを fetch して、編集箇所だけ書き換える
  fetch('data.js')
    .then(res => res.text())
    .then(src => {
      let updated = src;
      const newLastUpdated = document.getElementById('edit-lastUpdated').value;
      const newDataAsOf = document.getElementById('edit-dataAsOf').value;

      // SITE_META.lastUpdated と dataAsOf を書き換え
      if (newLastUpdated) {
        updated = updated.replace(/lastUpdated:\s*'[^']*'/, `lastUpdated: '${newLastUpdated}'`);
      }
      if (newDataAsOf) {
        updated = updated.replace(/dataAsOf:\s*'[^']*'/, `dataAsOf: '${newDataAsOf.replace(/'/g, "\\'")}'`);
      }

      // 各力士の rank / side / retired を書き換え
      let errors = [];
      Object.keys(banzukeEdits).forEach(id => {
        const edit = banzukeEdits[id];
        const result = applyRikishiEdit(updated, id, edit);
        if (result.error) errors.push(`${id}: ${result.error}`);
        else updated = result.text;
      });

      if (errors.length) {
        alert('一部の力士で書き換えに失敗しました:\n' + errors.join('\n'));
      }

      // ダウンロード
      const blob = new Blob([updated], { type: 'application/javascript;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'data.js';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    })
    .catch(err => alert('data.js の読み込みに失敗しました: ' + err.message));
}

// 1名分の rank/side/retired を書き換える
function applyRikishiEdit(text, id, edit) {
  // RIKISHI 内の対象オブジェクト範囲を特定
  const idMarker = `id: '${id}'`;
  const idx = text.indexOf(idMarker);
  if (idx < 0) return { error: 'idが見つかりません' };
  // オブジェクトの開始 { と終端 }, を探す
  let braceStart = text.lastIndexOf('{', idx);
  if (braceStart < 0) return { error: 'オブジェクト開始が見つかりません' };
  let depth = 0;
  let i = braceStart;
  for (; i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}') { depth--; if (depth === 0) break; }
  }
  if (depth !== 0) return { error: 'オブジェクト終端が見つかりません' };
  let objText = text.slice(braceStart, i + 1);

  // rank
  if (/rank:\s*'[^']*'/.test(objText)) {
    objText = objText.replace(/rank:\s*'[^']*'/, `rank: '${edit.rank}'`);
  }
  // side
  if (/side:\s*'[^']*'/.test(objText)) {
    objText = objText.replace(/side:\s*'[^']*'/, `side: '${edit.side}'`);
  }
  // retired (既存の場合は更新、ない場合は realName の直後に挿入)
  if (/retired:\s*(true|false)/.test(objText)) {
    objText = objText.replace(/retired:\s*(true|false)/, `retired: ${edit.retired ? 'true' : 'false'}`);
  } else if (edit.retired) {
    // realName 行 or rank 行の直前に挿入
    const insertMatch = objText.match(/(\n\s*)rank:/);
    if (insertMatch) {
      objText = objText.replace(insertMatch[0], `${insertMatch[1]}retired: true,${insertMatch[0]}`);
    }
  }
  return { text: text.slice(0, braceStart) + objText + text.slice(i + 1) };
}

// ========== 取り組み・星取 ==========

function formatDateShort(ymd) {
  if (!ymd) return '';
  const p = ymd.split('-');
  return parseInt(p[1]) + '月' + parseInt(p[2]) + '日';
}

function buildHoshitori(days, currentDay) {
  const rec = {};
  const ensure = (id) => { if (!rec[id]) rec[id] = { wins: 0, losses: 0, dayBouts: {} }; };
  for (const d of days) {
    if (d.day > currentDay) continue;
    for (const b of d.bouts) {
      ensure(b.east);
      ensure(b.west);
      const pending = b.winner === null;
      if (b.winner === '東') { rec[b.east].wins++; rec[b.west].losses++; }
      else if (b.winner === '西') { rec[b.west].wins++; rec[b.east].losses++; }
      const eastWon = b.winner === '東' ? true : b.winner === '西' ? false : null;
      rec[b.east].dayBouts[d.day] = { won: eastWon, pending };
      rec[b.west].dayBouts[d.day] = { won: eastWon === null ? null : !eastWon, pending };
    }
  }
  return rec;
}

function renderTorikumi() {
  if (typeof BASHO_TORIKUMI === 'undefined' || !BASHO_TORIKUMI.length) {
    $app.innerHTML = '<div class="empty-state"><p>取り組みデータがありません。</p><p><a href="#/">トップに戻る</a></p></div>';
    return;
  }

  const bashoIds = [...new Set(BASHO_TORIKUMI.map(d => d.bashoId))];
  const currentBashoId = bashoIds[bashoIds.length - 1];
  const days = BASHO_TORIKUMI.filter(d => d.bashoId === currentBashoId).sort((a, b) => a.day - b.day);
  if (!days.length) {
    $app.innerHTML = '<div class="empty-state"><p>取り組みデータがありません。</p></div>';
    return;
  }

  const today = new Date().toISOString().slice(0, 10);
  const todayEntry = days.find(d => d.date === today);
  const defaultDay = todayEntry ? todayEntry.day : days[days.length - 1].day;
  const currentDay = _torikumiDay !== null ? _torikumiDay : defaultDay;
  const dayData = days.find(d => d.day === currentDay) || days[days.length - 1];
  const isToday = dayData.date === today;

  const hasPrev = days.some(d => d.day < currentDay);
  const hasNext = days.some(d => d.day > currentDay);

  const hoshi = buildHoshitori(days, currentDay);

  const rankOrder = (r) => {
    if (!r) return 99999;
    if (r.rank === '横綱') return 1000 + (r.side === '西' ? 1 : 0);
    if (r.rank === '大関') return 2000 + (r.side === '西' ? 1 : 0);
    if (r.rank === '関脇') return 3000 + (r.side === '西' ? 1 : 0);
    if (r.rank === '小結') return 4000 + (r.side === '西' ? 1 : 0);
    const m = r.rank.match(/前頭(\d+)/);
    return m ? 5000 + parseInt(m[1], 10) * 10 + (r.side === '西' ? 1 : 0) : 9000;
  };

  // Collect all rikishi who appear in any day of this basho
  const allRikishiSet = new Set();
  for (const d of days) {
    for (const b of d.bouts) { allRikishiSet.add(b.east); allRikishiSet.add(b.west); }
  }
  const sortedIds = [...allRikishiSet].sort((a, b) =>
    rankOrder(RIKISHI.find(r => r.id === a)) - rankOrder(RIKISHI.find(r => r.id === b))
  );

  // Days with data up to and including current day (for star columns)
  const knownDays = days.filter(d => d.day <= currentDay);

  // Bout list (reversed: highest-rank bout at top)
  const boutRows = [...dayData.bouts].reverse().map(b => {
    const er = RIKISHI.find(r => r.id === b.east);
    const wr = RIKISHI.find(r => r.id === b.west);
    const eName = er ? escapeHtml(er.name) : escapeHtml(b.east);
    const wName = wr ? escapeHtml(wr.name) : escapeHtml(b.west);
    const eRank = er ? escapeHtml(er.rank) : '';
    const wRank = wr ? escapeHtml(wr.rank) : '';

    let centerHtml;
    if (b.winner === '東') {
      centerHtml = `<span class="win-arrow east-arrow">◀</span><span class="kimarite">${escapeHtml(b.kimarite || '')}</span>`;
    } else if (b.winner === '西') {
      centerHtml = `<span class="kimarite">${escapeHtml(b.kimarite || '')}</span><span class="win-arrow west-arrow">▶</span>`;
    } else {
      centerHtml = `<span class="bout-vs">対</span>`;
    }

    return `<div class="torikumi-row${b.winner ? ' decided' : ' pending'}">
      <div class="torikumi-east">
        <a href="#/rikishi/${b.east}" class="tk-name">${eName}</a>
        <span class="tk-rank">${eRank}</span>
      </div>
      <div class="torikumi-center">${centerHtml}</div>
      <div class="torikumi-west">
        <span class="tk-rank">${wRank}</span>
        <a href="#/rikishi/${b.west}" class="tk-name">${wName}</a>
      </div>
    </div>`;
  }).join('');

  // Hoshitori table
  const dayHeaders = knownDays.map(d => `<th class="hs-day">${d.day}日</th>`).join('');
  const tableRows = sortedIds.map(id => {
    const r = RIKISHI.find(x => x.id === id);
    const name = r ? escapeHtml(r.name) : escapeHtml(id);
    const rank = r ? escapeHtml(r.rank + r.side) : '';
    const rec = hoshi[id];
    const stars = knownDays.map(d => {
      const entry = rec && rec.dayBouts[d.day];
      if (!entry) return '<td class="star-cell kyujo">休</td>';
      if (entry.pending) return '<td class="star-cell pending">？</td>';
      return entry.won
        ? '<td class="star-cell win">○</td>'
        : '<td class="star-cell loss">●</td>';
    }).join('');
    const wl = rec ? `${rec.wins}勝${rec.losses}敗` : '―';
    return `<tr>
      <td class="hs-name-cell"><a href="#/rikishi/${id}">${name}</a><span class="hs-rank">${rank}</span></td>
      ${stars}
      <td class="hs-record">${wl}</td>
    </tr>`;
  }).join('');

  const bashoInfo = (typeof BANZUKE_SCHEDULE !== 'undefined') ? BANZUKE_SCHEDULE.find(b => b.id === currentBashoId) : null;
  const bashoName = bashoInfo ? escapeHtml(bashoInfo.name) : escapeHtml(currentBashoId);
  const todayBadge = isToday ? '<span class="today-badge">本日</span>' : '';
  const dateStr = dayData.date ? formatDateShort(dayData.date) : '';

  $app.innerHTML = `
    <h1 class="page-title">取り組み・星取</h1>
    <p class="lead">${bashoName}</p>
    <div class="day-nav">
      <button class="btn-day-nav" onclick="torikumiPrevDay()" ${hasPrev ? '' : 'disabled'}>◀ 前日</button>
      <span class="day-label">${currentDay}日目 <span class="day-date">${dateStr}</span>${todayBadge}</span>
      <button class="btn-day-nav" onclick="torikumiNextDay()" ${hasNext ? '' : 'disabled'}>翌日 ▶</button>
      <button class="btn-refresh" onclick="refreshDataJs()">データ更新</button>
    </div>
    <h2 class="section-title">本日の取り組み</h2>
    ${dayData.bouts.length ? `<div class="torikumi-list">${boutRows}</div>` : '<p class="lead">取り組みデータが未登録です。</p>'}
    ${knownDays.length ? `
    <h2 class="section-title">星取表</h2>
    <div class="hoshitori-wrap">
      <table class="hoshitori-table">
        <thead>
          <tr>
            <th class="hs-name-h">力士</th>
            ${dayHeaders}
            <th class="hs-record-h">成績</th>
          </tr>
        </thead>
        <tbody>${tableRows}</tbody>
      </table>
    </div>` : ''}
  `;
}

function torikumiPrevDay() {
  if (typeof BASHO_TORIKUMI === 'undefined' || !BASHO_TORIKUMI.length) return;
  const bashoIds = [...new Set(BASHO_TORIKUMI.map(d => d.bashoId))];
  const days = BASHO_TORIKUMI.filter(d => d.bashoId === bashoIds[bashoIds.length - 1]).sort((a, b) => a.day - b.day);
  const today = new Date().toISOString().slice(0, 10);
  const todayEntry = days.find(d => d.date === today);
  const cur = _torikumiDay !== null ? _torikumiDay : (todayEntry ? todayEntry.day : days[days.length - 1].day);
  const prev = [...days].reverse().find(d => d.day < cur);
  if (prev) { _torikumiDay = prev.day; renderTorikumi(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
}

function torikumiNextDay() {
  if (typeof BASHO_TORIKUMI === 'undefined' || !BASHO_TORIKUMI.length) return;
  const bashoIds = [...new Set(BASHO_TORIKUMI.map(d => d.bashoId))];
  const days = BASHO_TORIKUMI.filter(d => d.bashoId === bashoIds[bashoIds.length - 1]).sort((a, b) => a.day - b.day);
  const today = new Date().toISOString().slice(0, 10);
  const todayEntry = days.find(d => d.date === today);
  const cur = _torikumiDay !== null ? _torikumiDay : (todayEntry ? todayEntry.day : days[days.length - 1].day);
  const next = days.find(d => d.day > cur);
  if (next) { _torikumiDay = next.day; renderTorikumi(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
}

function refreshDataJs() {
  fetch('data.js?_=' + Date.now())
    .then(() => location.reload())
    .catch(() => location.reload());
}

function render404() {
  $app.innerHTML = `
    <div class="empty-state">
      <h1>お探しのページは見つかりませんでした</h1>
      <p><a href="#/">トップに戻る</a></p>
    </div>
  `;
}

// ========== Router ==========
const routes = [
  { match: /^#?\/?$/, render: renderBanzuke, nav: 'home' },
  { match: /^#\/rikishi\/(.+)$/, render: (m) => renderRikishiDetail(m[1]), nav: 'rikishi' },
  { match: /^#\/rikishi\/?$/, render: renderRikishiList, nav: 'rikishi' },
  { match: /^#\/stables\/?$/, render: renderStableList, nav: 'stables' },
  { match: /^#\/stable\/(.+)$/, render: (m) => renderStableDetail(m[1]), nav: 'stables' },
  { match: /^#\/tournaments\/?$/, render: renderTournaments, nav: 'tournaments' },
  { match: /^#\/jungyo\/?$/, render: renderJungyo, nav: 'jungyo' },
  { match: /^#\/torikumi\/?$/, render: renderTorikumi, nav: 'torikumi' },
  { match: /^#\/about\/?$/, render: renderAbout, nav: 'about' },
  { match: /^#\/edit\/?$/, render: renderEdit, nav: 'edit' },
];

function route() {
  const hash = location.hash || '#/';
  for (const r of routes) {
    const m = hash.match(r.match);
    if (m) {
      r.render(m);
      updateNav(r.nav);
      window.scrollTo({ top: 0, behavior: 'instant' });
      return;
    }
  }
  render404();
}

function updateNav(key) {
  $nav.querySelectorAll('a').forEach(a => {
    a.classList.toggle('active', a.dataset.nav === key);
  });
}

function setFooterMeta() {
  const el = document.getElementById('footer-updated');
  if (el && typeof SITE_META !== 'undefined' && SITE_META.lastUpdated) {
    el.textContent = `データ最終更新日: ${SITE_META.lastUpdated}（${SITE_META.dataAsOf}）`;
  }
}

// 番付更新通知（前回訪問時より lastUpdated が変わっていたらバナーを表示）
function checkForUpdate() {
  const KEY = 'sumo_banzuke_seen';
  const current = (SITE_META && SITE_META.lastUpdated) || '';
  if (!current) return;
  const seen = localStorage.getItem(KEY);
  if (seen && seen !== current) {
    const banner = document.createElement('div');
    banner.className = 'update-banner';
    const msg = document.createElement('span');
    msg.textContent = `番付データが更新されました（${current} / ${SITE_META.dataAsOf}）`;
    const btn = document.createElement('button');
    btn.textContent = '閉じる';
    btn.addEventListener('click', () => banner.remove());
    banner.appendChild(msg);
    banner.appendChild(btn);
    const app = document.getElementById('app');
    app.parentNode.insertBefore(banner, app);
  }
  localStorage.setItem(KEY, current);
}

window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', () => {
  setFooterMeta();
  checkForUpdate();
  route();
});

// SPA ルーター + レンダラ

const $app = document.getElementById('app');
const $nav = document.getElementById('site-nav');

function escapeHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
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
    <div class="banzuke-section">
  `;

  for (const sy of sanyaku) {
    const east = RIKISHI.filter(r => r.rank === sy.rank && r.side === '東');
    const west = RIKISHI.filter(r => r.rank === sy.rank && r.side === '西');
    if (!east.length && !west.length) continue;
    html += banzukeRow(east, sy.rank, west, sy.top);
  }

  // 前頭
  for (let i = 1; i <= 17; i++) {
    const rank = `前頭${i}`;
    const east = RIKISHI.filter(r => r.rank === rank && r.side === '東');
    const west = RIKISHI.filter(r => r.rank === rank && r.side === '西');
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
            ${winner ? `<a href="#/rikishi/${winner.id}">${escapeHtml(winner.name)}</a>` : '?'} （${escapeHtml(latest.yushoMakuuchi.record)}）
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
      <div class="name"><a href="#/rikishi/${rikishi.id}">${escapeHtml(rikishi.name)}</a></div>
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
  const ranks = [...new Set(RIKISHI.map(r => r.rank))];
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
    return `
      <div class="card">
        <h3><a href="#/rikishi/${r.id}">${escapeHtml(r.name)}</a></h3>
        <div class="meta">${escapeHtml(r.nameKana || '')}</div>
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
      <div class="name-large">${escapeHtml(r.name)}</div>
      <div class="name-kana">${escapeHtml(r.nameKana || '')} ／ ${escapeHtml(r.nameRomaji || '')}</div>
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
              <a href="#/rikishi/${m.id}">${escapeHtml(m.name)}</a>
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
            <a href="#/rikishi/${r.id}">${escapeHtml(r.name)}</a>
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
              ${winner ? `<a href="#/rikishi/${winner.id}">${escapeHtml(winner.name)}</a>` : '?'}
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

// ========== About ==========
function renderAbout() {
  const ichimons = [...new Set(STABLES.map(s => s.ichimon).filter(Boolean))];
  $app.innerHTML = `
    <h1 class="page-title">このサイトについて</h1>
    <p class="lead">日本の大相撲をひととおり眺められる、シンプルな閲覧サイトです。</p>

    <h2 class="section-title">収録内容</h2>
    <ul>
      <li>現在の番付（幕内）</li>
      <li>幕内力士（${RIKISHI.length}名）のプロフィール・通算成績・受賞歴</li>
      <li>相撲部屋（${STABLES.length}部屋）の概要と所属力士、一門</li>
      <li>直近の本場所結果と三賞受賞者</li>
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
    <p>収録時点: ${escapeHtml(SITE_META.dataAsOf)}</p>
  `;
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
  { match: /^#\/about\/?$/, render: renderAbout, nav: 'about' },
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

window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', route);

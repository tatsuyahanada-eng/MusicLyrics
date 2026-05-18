'use strict';

/* ============================================================
   Music Lyrics — lyrics.js  v3
   YouTube IFrame Player + YouTube Data API v3 + LRC sync
   ============================================================ */

const LYRICS_OVH  = 'https://api.lyrics.ovh/v1';
const LRCLIB_API  = 'https://lrclib.net/api/search';
const ITUNES_API  = 'https://itunes.apple.com/search';
const YT_SEARCH   = 'https://www.googleapis.com/youtube/v3/search';

const SONGS_PER_PAGE    = 15;
const MAX_VISIBLE_TOKENS = 18;
const TOKEN_LIFESPAN     = 7000;
const GRID_COLS          = 3;
const GRID_ROWS          = 5;

const SPEED_OPTIONS = { slow: 4000, normal: 2500, fast: 1500, veryfast: 800 };

/* font size bands — [min, max, weight, opacity] */
const FONT_BANDS = [
  [14, 20, 400, 0.55],
  [22, 32, 600, 0.75],
  [34, 48, 700, 0.90],
  [52, 78, 800, 1.00],
];
const BAND_WEIGHTS = [3, 4, 3, 1]; // relative probability per band (small most common)

/* ---- App state ---- */
const state = {
  lyrics: [],       // plain string array
  lrcLines: [],     // [{time, text}] sorted by time (LRC sync)
  useLrc: false,
  currentIndex: 0,
  isPlaying: false,
  intervalId: null,
  speed: 2500,

  ytReady: false,
  ytPlayer: null,
  ytDuration: 0,
  ytProgressId: null,
  ytQueue: [],      // [{videoId, title, channel, thumb}]
  ytQueueIdx: 0,
  ytPaused: true,

  currentArtist: '',
  currentTitle: '',
};

const songList = { songs: [], page: 0 };
let searchMode = 'song';

/* ---- Cell occupation map for overlap prevention ---- */
const cellMap = new Map(); // key: "col,row" → expiry timestamp

/* ---- DOM refs ---- */
let elArtist, elTitle, elTitleWrap, elFetchBtn, elSearchHint, elStatus,
    elStage, elPlayPauseBtn, elTimer, elDuration, elSpeedSelect,
    elVolumeSlider, elProgressBar, elSeekBack, elSeekFwd, elNextSongBtn,
    elSongList, elSongListInfo, elSongCards, elPageInfo, elPrevPageBtn, elNextPageBtn,
    elCloseSongList, elYtResults, elYtResultsInfo, elYtResultCards, elCloseYtResults,
    elPlayerSection, elApiKeyInput, elSaveApiKey, elClearApiKey,
    elToggleApiKey, elApiKeyStatus, elCurrentOrigin;

/* ============================================================
   Bootstrap
   ============================================================ */
function init() {
  elArtist         = document.getElementById('artistInput');
  elTitle          = document.getElementById('titleInput');
  elTitleWrap      = document.getElementById('titleWrap');
  elFetchBtn       = document.getElementById('fetchBtn');
  elSearchHint     = document.getElementById('searchHint');
  elStatus         = document.getElementById('status');
  elStage          = document.getElementById('stage');
  elPlayPauseBtn   = document.getElementById('playPauseBtn');
  elTimer          = document.getElementById('timer');
  elDuration       = document.getElementById('duration');
  elSpeedSelect    = document.getElementById('speedSelect');
  elVolumeSlider   = document.getElementById('volumeSlider');
  elProgressBar    = document.getElementById('progressBar');
  elSeekBack       = document.getElementById('seekBackBtn');
  elSeekFwd        = document.getElementById('seekFwdBtn');
  elNextSongBtn    = document.getElementById('nextSongBtn');
  elSongList       = document.getElementById('songList');
  elSongListInfo   = document.getElementById('songListInfo');
  elSongCards      = document.getElementById('songCards');
  elPageInfo       = document.getElementById('pageInfo');
  elPrevPageBtn    = document.getElementById('prevPageBtn');
  elNextPageBtn    = document.getElementById('nextPageBtn');
  elCloseSongList  = document.getElementById('closeSongList');
  elYtResults      = document.getElementById('ytResults');
  elYtResultsInfo  = document.getElementById('ytResultsInfo');
  elYtResultCards  = document.getElementById('ytResultCards');
  elCloseYtResults = document.getElementById('closeYtResults');
  elPlayerSection  = document.getElementById('playerSection');
  elApiKeyInput    = document.getElementById('apiKeyInput');
  elSaveApiKey     = document.getElementById('saveApiKey');
  elClearApiKey    = document.getElementById('clearApiKey');
  elToggleApiKey   = document.getElementById('toggleApiKey');
  elApiKeyStatus   = document.getElementById('apiKeyStatus');
  elCurrentOrigin  = document.getElementById('currentOrigin');

  if (elCurrentOrigin) elCurrentOrigin.textContent = location.origin || location.href;

  const saved = localStorage.getItem('yt_api_key');
  if (saved) {
    elApiKeyInput.value = saved;
    const verified = localStorage.getItem('yt_api_key_verified') === '1';
    setApiKeyStatus(verified ? 'verified' : 'saved', saved);
  } else {
    setApiKeyStatus('empty');
  }

  elSaveApiKey.addEventListener('click', saveAndValidateApiKey);
  elApiKeyInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); saveAndValidateApiKey(); }
  });

  elToggleApiKey.addEventListener('click', () => {
    const showing = elApiKeyInput.type === 'text';
    elApiKeyInput.type = showing ? 'password' : 'text';
    elToggleApiKey.textContent = showing ? '👁' : '🙈';
  });

  elClearApiKey.addEventListener('click', () => {
    localStorage.removeItem('yt_api_key');
    localStorage.removeItem('yt_api_key_verified');
    elApiKeyInput.value = '';
    setApiKeyStatus('empty');
    setStatus('APIキーを削除しました。', '');
  });

  document.querySelectorAll('.ly-tab').forEach(tab =>
    tab.addEventListener('click', () => handleModeSwitch(tab.dataset.mode))
  );

  document.getElementById('searchForm').addEventListener('submit', handleFetch);
  elPlayPauseBtn.addEventListener('click', togglePlayPause);
  elSpeedSelect.addEventListener('change', () => { state.speed = SPEED_OPTIONS[elSpeedSelect.value] || 2500; });
  elVolumeSlider.addEventListener('input', () => {
    if (state.ytPlayer && state.ytReady) state.ytPlayer.setVolume(Number(elVolumeSlider.value));
  });
  elProgressBar.addEventListener('input', () => {
    if (state.ytPlayer && state.ytReady && state.ytDuration > 0) {
      state.ytPlayer.seekTo(state.ytDuration * Number(elProgressBar.value) / 1000, true);
    }
  });
  elSeekBack.addEventListener('click', () => { if (state.ytPlayer && state.ytReady) state.ytPlayer.seekTo(Math.max(0, state.ytPlayer.getCurrentTime() - 10), true); });
  elSeekFwd.addEventListener('click',  () => { if (state.ytPlayer && state.ytReady) state.ytPlayer.seekTo(Math.min(state.ytDuration, state.ytPlayer.getCurrentTime() + 10), true); });
  elNextSongBtn.addEventListener('click', playNextInQueue);
  elPrevPageBtn.addEventListener('click', () => goToPage(songList.page - 1));
  elNextPageBtn.addEventListener('click', () => goToPage(songList.page + 1));
  elCloseSongList.addEventListener('click',  hideSongList);
  elCloseYtResults.addEventListener('click', hideYtResults);

  loadYouTubeApi();
}

/* ============================================================
   YouTube IFrame API
   ============================================================ */
function loadYouTubeApi() {
  if (window.YT && window.YT.Player) { onYouTubeIframeAPIReady(); return; }
  const tag = document.createElement('script');
  tag.src = 'https://www.youtube.com/iframe_api';
  document.head.appendChild(tag);
}

window.onYouTubeIframeAPIReady = function () {
  state.ytPlayer = new YT.Player('ytPlayer', {
    width: '100%',
    height: '100%',
    playerVars: { autoplay: 0, controls: 0, rel: 0, modestbranding: 1 },
    events: {
      onReady:       onPlayerReady,
      onStateChange: onPlayerStateChange,
      onError:       onPlayerError,
    },
  });
};

function onPlayerReady() {
  state.ytReady = true;
  state.ytPlayer.setVolume(Number(elVolumeSlider.value));
}

function onPlayerStateChange(e) {
  const S = YT.PlayerState;
  if (e.data === S.PLAYING) {
    state.ytDuration = state.ytPlayer.getDuration() || 0;
    updateDurationDisplay();
    state.ytPaused = false;
    elPlayPauseBtn.textContent = '⏸';
    elPlayPauseBtn.setAttribute('aria-label', '一時停止');
    elPlayPauseBtn.classList.add('playing');
    startProgressPoll();
    if (!state.isPlaying && state.lyrics.length) startLyricsTimer();
  } else if (e.data === S.PAUSED) {
    state.ytPaused = true;
    elPlayPauseBtn.textContent = '▶';
    elPlayPauseBtn.setAttribute('aria-label', '再生');
    elPlayPauseBtn.classList.remove('playing');
    stopProgressPoll();
    stopLyricsTimer();
  } else if (e.data === S.ENDED) {
    stopProgressPoll();
    stopLyricsTimer();
    elPlayPauseBtn.textContent = '▶';
    elPlayPauseBtn.classList.remove('playing');
    playNextInQueue();
  }
}

function onPlayerError(e) {
  if (e.data === 101 || e.data === 150) {
    setStatus('この動画は埋め込み再生できません。次の曲に切り替えます。', 'error');
    playNextInQueue();
  }
}

/* ============================================================
   Progress polling
   ============================================================ */
function startProgressPoll() {
  if (state.ytProgressId) return;
  state.ytProgressId = setInterval(pollProgress, 250);
}

function stopProgressPoll() {
  clearInterval(state.ytProgressId);
  state.ytProgressId = null;
}

function pollProgress() {
  if (!state.ytPlayer || !state.ytReady) return;
  const cur = state.ytPlayer.getCurrentTime() || 0;
  const dur = state.ytDuration || 1;
  elTimer.textContent = formatTime(cur);
  elProgressBar.value = String(Math.round((cur / dur) * 1000));

  if (state.useLrc && state.lrcLines.length) syncLrc(cur);
}

/* ============================================================
   LRC Sync
   ============================================================ */
function syncLrc(currentSec) {
  const lines = state.lrcLines;
  let idx = 0;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].time <= currentSec) { idx = i; break; }
  }
  if (idx !== state.currentIndex) {
    state.currentIndex = idx;
    const text = lines[idx].text;
    if (text) displayLine(text);
  }
}

/* ============================================================
   Lyrics Timer (non-LRC fallback)
   ============================================================ */
function startLyricsTimer() {
  if (state.intervalId) return;
  state.isPlaying = true;
  state.intervalId = setInterval(() => {
    if (state.currentIndex >= state.lyrics.length) state.currentIndex = 0;
    displayLine(state.lyrics[state.currentIndex++]);
  }, state.speed);
}

function stopLyricsTimer() {
  state.isPlaying = false;
  clearInterval(state.intervalId);
  state.intervalId = null;
}

/* ============================================================
   Play / Pause toggle
   ============================================================ */
function togglePlayPause() {
  if (!state.ytReady || !state.ytPlayer) return;
  const S = YT.PlayerState;
  const ps = state.ytPlayer.getPlayerState();

  if (ps === S.PLAYING) {
    state.ytPlayer.pauseVideo();
  } else if (ps === S.PAUSED || ps === S.CUED || ps === S.UNSTARTED || ps === -1) {
    state.ytPlayer.playVideo();
  } else {
    /* fallback: lyrics-only mode */
    if (state.isPlaying) stopLyricsTimer(); else startLyricsTimer();
    elPlayPauseBtn.textContent = state.isPlaying ? '⏸' : '▶';
    elPlayPauseBtn.classList.toggle('playing', state.isPlaying);
  }
}

/* ============================================================
   YouTube Queue
   ============================================================ */
function loadYtVideo(idx) {
  if (!state.ytQueue.length) return;
  idx = ((idx % state.ytQueue.length) + state.ytQueue.length) % state.ytQueue.length;
  state.ytQueueIdx = idx;
  const item = state.ytQueue[idx];
  clearStage();
  state.currentIndex = 0;
  stopLyricsTimer();
  stopProgressPoll();
  elPlayerSection.hidden = false;
  if (state.ytReady) {
    state.ytPlayer.loadVideoById(item.videoId);
    state.ytPlayer.setVolume(Number(elVolumeSlider.value));
  }
  setStatus(`再生中: ${escapeHTML(item.title)}`, 'success');
}

function playNextInQueue() {
  if (!state.ytQueue.length) return;
  loadYtVideo(state.ytQueueIdx + 1);
}

/* ============================================================
   YouTube Data API search
   ============================================================ */
const API_KEY_RE = /^AIza[0-9A-Za-z_-]{35}$/;

function setApiKeyStatus(stateName, key) {
  if (!elApiKeyStatus) return;
  elApiKeyStatus.dataset.state = stateName;
  const tail = key ? ` (…${key.slice(-4)})` : '';
  const labels = {
    empty:    '✗ 未設定',
    saved:    '● 保存済み（未検証）' + tail,
    verified: '✓ 検証済み' + tail,
    invalid:  '⚠ 無効なキー' + tail,
    checking: '… 検証中',
  };
  elApiKeyStatus.textContent = labels[stateName] || stateName;
}

async function saveAndValidateApiKey() {
  const k = elApiKeyInput.value.trim();
  if (!k) {
    setStatus('APIキーを入力してください。', 'error');
    return;
  }
  if (!API_KEY_RE.test(k)) {
    setApiKeyStatus('invalid', k);
    setStatus('キーの形式が正しくありません。"AIza" で始まる39文字のキーを貼り付けてください。', 'error');
    return;
  }

  localStorage.setItem('yt_api_key', k);
  localStorage.removeItem('yt_api_key_verified');
  setApiKeyStatus('checking');
  setStatus('APIキーを検証中...', 'loading');
  elSaveApiKey.disabled = true;

  try {
    await testApiKey(k);
    localStorage.setItem('yt_api_key_verified', '1');
    setApiKeyStatus('verified', k);
    setStatus('APIキーは有効です。検索できます。', 'success');
  } catch (err) {
    setApiKeyStatus('invalid', k);
    setStatus(`APIキーの検証に失敗: ${err.message}`, 'error');
  } finally {
    elSaveApiKey.disabled = false;
  }
}

async function testApiKey(key) {
  const params = new URLSearchParams({
    part: 'id', q: 'test', type: 'video', maxResults: '1', key,
  });
  const res = await fetchWithTimeout(`${YT_SEARCH}?${params}`, 10000);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(interpretYouTubeError(res.status, body));
  }
}

function interpretYouTubeError(status, body) {
  const err   = body.error || {};
  const items = err.errors || [];
  const reason = items[0]?.reason || '';
  const msg    = err.message || `HTTP ${status}`;

  switch (reason) {
    case 'keyInvalid':
      return 'キーが無効です。Google Cloud Console で正しいAPIキーを取得してください。';
    case 'ipRefererBlocked':
    case 'referrerNotAllowed':
      return `リファラ制限でブロックされました。Cloud Console でこのURL (${location.origin}) を許可するか、制限を解除してください。`;
    case 'accessNotConfigured':
    case 'forbidden':
      return 'YouTube Data API v3 が有効化されていません。Google Cloud Console で API を有効にしてください。';
    case 'quotaExceeded':
    case 'dailyLimitExceeded':
      return '本日の API クォータを使い切りました。明日まで待つか、Google Cloud Console でクォータを増やしてください。';
    case 'rateLimitExceeded':
    case 'userRateLimitExceeded':
      return 'リクエストが多すぎます。少し時間を置いて再試行してください。';
    default:
      return msg;
  }
}

async function searchYouTube(query) {
  const key = localStorage.getItem('yt_api_key');
  if (!key) {
    const e = new Error('YouTubeのAPIキーが未設定です。上部の欄でキーを保存してください。');
    e.code = 'NO_KEY';
    throw e;
  }
  const params = new URLSearchParams({
    part: 'snippet', q: query, type: 'video',
    maxResults: '10', key,
  });
  const res = await fetchWithTimeout(`${YT_SEARCH}?${params}`, 12000);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    if (res.status === 400 || res.status === 403) {
      localStorage.removeItem('yt_api_key_verified');
      setApiKeyStatus('invalid', key);
    }
    throw new Error(interpretYouTubeError(res.status, body));
  }
  const data = await res.json();
  return (data.items || []).map(it => ({
    videoId: it.id.videoId,
    title:   it.snippet.title,
    channel: it.snippet.channelTitle,
    thumb:   it.snippet.thumbnails?.default?.url || '',
  }));
}

/* ============================================================
   Mode switch
   ============================================================ */
function handleModeSwitch(mode) {
  searchMode = mode;
  document.querySelectorAll('.ly-tab').forEach(tab => {
    const a = tab.dataset.mode === mode;
    tab.classList.toggle('active', a);
    tab.setAttribute('aria-selected', String(a));
  });
  if (mode === 'artist') {
    elTitleWrap.hidden = true;
    elFetchBtn.textContent = 'アーティストを検索';
    elSearchHint.textContent = 'アーティスト名を入力して曲リストを表示。曲をタップして再生。';
  } else {
    elTitleWrap.hidden = false;
    elFetchBtn.textContent = '歌詞を取得';
    elSearchHint.textContent = 'YouTube で楽曲を検索し、歌詞と一緒に楽しめます。';
  }
  setStatus('', '');
  hideSongList();
  hideYtResults();
}

/* ============================================================
   Form submit
   ============================================================ */
async function handleFetch(e) {
  e.preventDefault();
  const artist = elArtist.value.trim();
  if (!artist) return;

  if (searchMode === 'artist') {
    await handleArtistSearch(artist);
  } else {
    const title = elTitle.value.trim();
    if (!title) { setStatus('曲名を入力してください。', 'error'); return; }
    await handleSongSearch(artist, title);
  }
}

/* ============================================================
   Artist search (iTunes)
   ============================================================ */
async function handleArtistSearch(artist) {
  setStatus(`「${escapeHTML(artist)}」の楽曲を検索中...`, 'loading');
  elFetchBtn.disabled = true;
  hideSongList();
  try {
    const songs = await fetchSongsByArtist(artist);
    if (!songs.length) { setStatus('楽曲が見つかりませんでした。', 'error'); return; }
    songList.songs = songs;
    songList.page  = 0;
    setStatus('', '');
    showSongList();
  } catch (err) {
    setStatus(err.message || '検索に失敗しました。', 'error');
  } finally {
    elFetchBtn.disabled = false;
  }
}

async function fetchSongsByArtist(artist) {
  const url = `${ITUNES_API}?term=${encodeURIComponent(artist)}&entity=song&limit=100&country=jp`;
  const res  = await fetchWithTimeout(url, 10000);
  if (!res.ok) throw new Error(`iTunes API エラー (HTTP ${res.status})`);
  const data = await res.json();
  return (data.results || []).filter(r => r.trackName).map(r => ({
    artist:     r.artistName     || artist,
    title:      r.trackName      || '',
    album:      r.collectionName || '',
    durationMs: r.trackTimeMillis || 0,
  }));
}

/* ============================================================
   Song search (lyrics + YouTube)
   ============================================================ */
async function handleSongSearch(artist, title) {
  state.currentArtist = artist;
  state.currentTitle  = title;

  setStatus(`「${escapeHTML(title)}」を検索中...`, 'loading');
  elFetchBtn.disabled = true;
  hideYtResults();

  try {
    const [lyricsResult, ytItems] = await Promise.allSettled([
      fetchLyricsWithFallback(artist, title),
      searchYouTube(`${artist} ${title}`),
    ]);

    /* Lyrics */
    if (lyricsResult.status === 'fulfilled') {
      loadLyrics(lyricsResult.value);
    } else {
      state.lyrics = [];
      state.lrcLines = [];
    }

    /* YouTube results */
    const ytErr = ytItems.status === 'rejected' ? ytItems.reason : null;
    const ytList = ytItems.status === 'fulfilled' ? ytItems.value : null;

    if (ytList && ytList.length) {
      state.ytQueue    = ytList;
      state.ytQueueIdx = 0;
      showYtResults(ytList);
      enableTransportControls(true);
      setStatus(
        lyricsResult.status === 'fulfilled'
          ? `「${escapeHTML(title)}」の歌詞を取得しました。動画を選んで再生してください。`
          : `歌詞は見つかりませんでした。YouTubeの結果から動画を選んでください。`,
        lyricsResult.status === 'fulfilled' ? 'success' : 'error'
      );
    } else if (lyricsResult.status === 'fulfilled' && state.lyrics.length) {
      clearStage();
      state.currentIndex = 0;
      elPlayPauseBtn.disabled = false;
      const reason = ytErr ? `（YouTube: ${ytErr.message}）` : '（YouTube結果なし）';
      setStatus(`「${escapeHTML(title)}」の歌詞を取得しました${reason}。▶で開始。`, ytErr ? 'error' : 'success');
    } else if (ytErr) {
      setStatus(`YouTube検索エラー: ${ytErr.message}`, 'error');
    } else {
      setStatus('歌詞もYouTubeも見つかりませんでした。曲名を変えて試してください。', 'error');
    }
  } catch (err) {
    setStatus(err.message || '取得に失敗しました。', 'error');
  } finally {
    elFetchBtn.disabled = false;
  }
}

function loadLyrics(raw) {
  const lrc = parseLrc(raw);
  if (lrc.length) {
    state.lrcLines     = lrc;
    state.lyrics       = lrc.map(l => l.text).filter(Boolean);
    state.useLrc       = true;
  } else {
    state.lyrics       = parsePlain(raw);
    state.lrcLines     = [];
    state.useLrc       = false;
  }
  state.currentIndex = 0;
  clearStage();
}

/* ============================================================
   Song list UI
   ============================================================ */
function showSongList() {
  elSongList.hidden = false;
  renderSongListPage();
  elSongList.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function hideSongList() { elSongList.hidden = true; }

function renderSongListPage() {
  const { songs, page } = songList;
  const total      = songs.length;
  const totalPages = Math.ceil(total / SONGS_PER_PAGE);
  const start      = page * SONGS_PER_PAGE;
  const pageItems  = songs.slice(start, start + SONGS_PER_PAGE);

  elSongListInfo.textContent = `${total} 件`;
  elPageInfo.textContent     = `${page + 1} / ${totalPages} ページ`;
  elPrevPageBtn.disabled     = page === 0;
  elNextPageBtn.disabled     = page >= totalPages - 1;

  elSongCards.innerHTML = '';
  pageItems.forEach(song => {
    const card = document.createElement('button');
    card.className   = 'ly-song-card';
    card.type        = 'button';
    const dur        = formatDuration(song.durationMs);
    const parts      = [song.artist, song.album, dur].filter(Boolean);
    card.innerHTML   =
      `<span class="ly-song-title">${escapeHTML(song.title)}</span>` +
      `<span class="ly-song-meta">${escapeHTML(parts.join(' · '))}</span>`;
    card.addEventListener('click', () => { hideSongList(); handleSongSearch(song.artist, song.title); });
    elSongCards.appendChild(card);
  });
}

function goToPage(page) {
  const totalPages = Math.ceil(songList.songs.length / SONGS_PER_PAGE);
  if (page < 0 || page >= totalPages) return;
  songList.page = page;
  renderSongListPage();
  elSongList.querySelector('.ly-song-list-inner').scrollTop = 0;
}

/* ============================================================
   YouTube Results UI
   ============================================================ */
function showYtResults(items) {
  elYtResultsInfo.textContent = `YouTube 検索結果 ${items.length} 件`;
  elYtResultCards.innerHTML   = '';

  items.forEach((item, idx) => {
    const card = document.createElement('button');
    card.className = 'ly-yt-card';
    card.type      = 'button';
    card.innerHTML =
      `<img class="ly-yt-thumb" src="${escapeHTML(item.thumb)}" alt="" loading="lazy">` +
      `<div class="ly-yt-card-info">` +
        `<span class="ly-yt-card-title">${escapeHTML(item.title)}</span>` +
        `<span class="ly-yt-card-channel">${escapeHTML(item.channel)}</span>` +
      `</div>`;
    card.addEventListener('click', () => { hideYtResults(); loadYtVideo(idx); });
    elYtResultCards.appendChild(card);
  });

  elYtResults.hidden = false;
  elYtResults.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function hideYtResults() { elYtResults.hidden = true; }

/* ============================================================
   Lyric display — grid-based overlap prevention
   ============================================================ */
function displayLine(text) {
  if (!text || !text.trim()) return;

  const tokens = elStage.querySelectorAll('.lyric-token');
  if (tokens.length >= MAX_VISIBLE_TOKENS) tokens.forEach(el => el.remove());

  const el = document.createElement('div');
  el.className   = 'lyric-token';
  el.textContent = text;

  /* Pick font band by weighted random */
  const band = pickBand();
  const size = randomInt(band[0], band[1]);
  el.style.fontSize   = `${size}px`;
  el.style.fontWeight = String(band[2]);
  el.style.opacity    = '0';

  /* Temp off-screen to measure */
  el.style.left = '-9999px';
  el.style.top  = '-9999px';
  elStage.appendChild(el);

  const sw = elStage.clientWidth  || 320;
  const sh = elStage.clientHeight || 200;
  const ew = el.offsetWidth;
  const eh = el.offsetHeight;

  const PAD = 10;
  const cellW = sw / GRID_COLS;
  const cellH = sh / GRID_ROWS;

  /* Try each grid cell in shuffled order */
  const cells = shuffledCells();
  let placed = false;

  const now = Date.now();

  for (const [col, row] of cells) {
    const key = `${col},${row}`;
    if (cellMap.has(key) && cellMap.get(key) > now) continue; /* occupied */

    const cellX = col * cellW;
    const cellY = row * cellH;
    const x = clamp(cellX + randomInt(PAD, Math.max(PAD, cellW - ew - PAD)), PAD, sw - ew - PAD);
    const y = clamp(cellY + randomInt(PAD, Math.max(PAD, cellH - eh - PAD)), PAD, sh - eh - PAD);

    el.style.left = `${x}px`;
    el.style.top  = `${y}px`;
    cellMap.set(key, now + TOKEN_LIFESPAN);
    placed = true;
    break;
  }

  if (!placed) {
    /* All cells busy — place at random, clear oldest cell */
    const oldest = [...cellMap.entries()].sort((a, b) => a[1] - b[1])[0];
    if (oldest) cellMap.delete(oldest[0]);
    el.style.left = `${randomInt(PAD, Math.max(PAD, sw - ew - PAD))}px`;
    el.style.top  = `${randomInt(PAD, Math.max(PAD, sh - eh - PAD))}px`;
  }

  requestAnimationFrame(() => el.classList.add('visible'));

  setTimeout(() => {
    el.classList.remove('visible');
    el.classList.add('fading');
    setTimeout(() => { if (el.parentNode) el.remove(); }, 900);
  }, TOKEN_LIFESPAN);
}

function shuffledCells() {
  const cells = [];
  for (let r = 0; r < GRID_ROWS; r++) {
    for (let c = 0; c < GRID_COLS; c++) cells.push([c, r]);
  }
  for (let i = cells.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [cells[i], cells[j]] = [cells[j], cells[i]];
  }
  return cells;
}

function pickBand() {
  const total = BAND_WEIGHTS.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  for (let i = 0; i < BAND_WEIGHTS.length; i++) {
    r -= BAND_WEIGHTS[i];
    if (r <= 0) return FONT_BANDS[i];
  }
  return FONT_BANDS[FONT_BANDS.length - 1];
}

/* ============================================================
   Lyrics API
   ============================================================ */
async function fetchLyricsWithFallback(artist, title) {
  try { return await fetchLyricsOvh(artist, title); } catch (_) {}
  try { return await fetchLyricsLrclib(artist, title); } catch (_2) {}
  throw new Error('歌詞が見つかりませんでした。');
}

async function fetchLyricsOvh(artist, title) {
  const url = `${LYRICS_OVH}/${encodeURIComponent(artist)}/${encodeURIComponent(title)}`;
  const res  = await fetchWithTimeout(url, 10000);
  if (res.status === 404) throw new Error('not found');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  if (!data.lyrics) throw new Error('empty');
  return data.lyrics;
}

async function fetchLyricsLrclib(artist, title) {
  const params = new URLSearchParams({ artist_name: artist, track_name: title });
  const res    = await fetchWithTimeout(`${LRCLIB_API}?${params}`, 10000);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  if (!Array.isArray(data) || !data.length) throw new Error('not found');
  const hit = data.find(r => r.syncedLyrics || r.plainLyrics);
  if (!hit) throw new Error('no lyrics');
  return hit.syncedLyrics || hit.plainLyrics;
}

async function fetchWithTimeout(url, ms) {
  const ctrl = new AbortController();
  const tid  = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    clearTimeout(tid);
    return res;
  } catch (err) {
    clearTimeout(tid);
    if (err.name === 'AbortError') throw new Error('タイムアウトしました。');
    throw err;
  }
}

/* ============================================================
   Lyric parsers
   ============================================================ */
function parseLrc(text) {
  const lines = [];
  const re    = /^\[(\d+):(\d+\.\d+)\]\s*(.*)$/;
  text.split('\n').forEach(line => {
    const m = line.match(re);
    if (m) {
      const time = parseInt(m[1]) * 60 + parseFloat(m[2]);
      const txt  = m[3].trim();
      if (txt) lines.push({ time, text: txt });
    }
  });
  return lines.sort((a, b) => a.time - b.time);
}

function parsePlain(text) {
  return text
    .split('\n')
    .map(l => l.trim())
    .filter(l => l.length > 0 && !l.startsWith('['));
}

/* ============================================================
   UI helpers
   ============================================================ */
function enableTransportControls(on) {
  elPlayPauseBtn.disabled = !on;
  elSeekBack.disabled     = !on;
  elSeekFwd.disabled      = !on;
  elNextSongBtn.disabled  = !on;
}

function clearStage() {
  elStage.querySelectorAll('.lyric-token').forEach(el => el.remove());
  cellMap.clear();
}

function updateDurationDisplay() {
  elDuration.textContent = state.ytDuration > 0 ? `/ ${formatTime(state.ytDuration)}` : '/ --:--';
}

function setStatus(msg, cls) {
  elStatus.textContent = msg;
  elStatus.className   = 'ly-status' + (cls ? ` ${cls}` : '');
}

/* ============================================================
   Utilities
   ============================================================ */
function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

function formatDuration(ms) {
  if (!ms) return '';
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function escapeHTML(str) {
  return String(str).replace(/[&<>"']/g, ch =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch])
  );
}

/* ---- Bootstrap ---- */
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

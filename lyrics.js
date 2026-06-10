'use strict';

/* ============================================================
   Music Lyrics — lyrics.js  v3
   YouTube IFrame Player + YouTube Data API v3 + LRC sync
   ============================================================ */

const LYRICS_OVH  = 'https://api.lyrics.ovh/v1';
const LRCLIB_API  = 'https://lrclib.net/api/search';
const ITUNES_API  = 'https://itunes.apple.com/search';
const YT_SEARCH   = 'https://www.googleapis.com/youtube/v3/search';

const SONGS_PER_PAGE  = 15;
const PLAIN_HISTORY   = 2;    /* how many past lines to keep in plain mode */
const STAGE_SLOT_CLASSES = [
  'ly-line-far-past',
  'ly-line-past',
  'ly-line-current',
  'ly-line-next',
  'ly-line-far-next',
];

const SPEED_OPTIONS = { slow: 4000, normal: 2500, fast: 1500, veryfast: 800 };

/* ---- App state ---- */
const state = {
  lyrics: [],       // plain string array
  lrcLines: [],     // [{time, text}] sorted by time (LRC sync)
  useLrc: false,
  currentIndex: 0,
  isPlaying: false,
  intervalId: null,
  startTimeoutId: null,
  speed: 2500,
  lyricsOffset: 0,  // seconds; +N = lyrics shown N seconds later

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
let searchMode = 'artist';

/* ---- Lyric Speaker style stacked stage ---- */
const STAGE_SLOTS = [];  /* 5 div slots: far-past, past, current, next, far-next */
let plainHistory = [];   /* last few lines shown in plain (non-LRC) mode */

/* ---- DOM refs ---- */
let elArtist, elTitle, elTitleWrap, elFetchBtn, elSearchHint, elStatus,
    elStage, elPlayPauseBtn, elTimer, elDuration,
    elVolumeSlider, elProgressBar, elSeekBack, elSeekFwd, elNextSongBtn, elPrevSongBtn,
    elSongList, elSongListInfo, elSongCards, elPageInfo, elPrevPageBtn, elNextPageBtn,
    elCloseSongList, elYtResults, elYtResultsInfo, elYtResultCards, elCloseYtResults,
    elPlayerSection, elApiKeyInput, elSaveApiKey, elClearApiKey,
    elToggleApiKey, elApiKeyStatus, elCurrentOrigin,
    elApiSetup, elToggleApiSetup,
    elLyricsOffsetBar, elLyricsOffsetDisplay,
    elLyricsOffsetBarFs, elLyricsOffsetDisplayFs,
    elLyricsStartBtn, elLyricsResetBtn, elStyleToggleBtn,
    elRandomPlayBtn, elOpenInYoutubeBtn, elFullscreenBtn,
    elExitKaraokeBtn, elArtistRandomBtn,
    elNowPlaying, elLyricStack, elScatterLayer, elStageExitBtn, elLyricsFsBtn,
    elFx, elFxThemeBtn, elColorToggleBtn;

let lyricStyle = 'stack';   /* 'stack' | 'scatter' */
let fxTheme = 'rings';
let colorTheme = 'dark';    /* 'dark' | 'light' */

/* ============================================================
   API key access — a site-wide key set in config.js takes
   priority over a per-user key in localStorage.
   ============================================================ */
function getBuiltinKey() {
  try {
    return (window.MUSIC_LYRICS_CONFIG && window.MUSIC_LYRICS_CONFIG.YT_API_KEY || '').trim();
  } catch (_) { return ''; }
}

function getApiKey() {
  return getBuiltinKey() || localStorage.getItem('yt_api_key') || '';
}

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
  elNowPlaying     = document.getElementById('nowPlaying');
  elLyricStack     = document.getElementById('lyricStack');
  elScatterLayer   = document.getElementById('scatterLayer');
  elFx             = document.getElementById('lyricFx');
  elStageExitBtn   = document.getElementById('stageExitBtn');
  elPlayPauseBtn   = document.getElementById('playPauseBtn');
  elTimer          = document.getElementById('timer');
  elDuration       = document.getElementById('duration');
  elVolumeSlider   = document.getElementById('volumeSlider');
  elProgressBar    = document.getElementById('progressBar');
  elSeekBack       = document.getElementById('seekBackBtn');
  elSeekFwd        = document.getElementById('seekFwdBtn');
  elNextSongBtn    = document.getElementById('nextSongBtn');
  elPrevSongBtn    = document.getElementById('prevSongBtn');
  elLyricsOffsetBar     = document.getElementById('lyricsOffsetBar');
  elLyricsOffsetDisplay = document.getElementById('lyricsOffsetDisplay');
  elLyricsOffsetBarFs     = document.getElementById('lyricsOffsetBarFs');
  elLyricsOffsetDisplayFs = document.getElementById('lyricsOffsetDisplayFs');
  elStyleToggleBtn   = document.getElementById('styleToggleBtn');
  elFxThemeBtn       = document.getElementById('fxThemeBtn');
  elColorToggleBtn   = document.getElementById('colorToggleBtn');
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
  elApiSetup       = document.getElementById('apiSetup');
  elToggleApiSetup = document.getElementById('toggleApiSetup');
  elLyricsStartBtn = document.getElementById('lyricsStartBtn');
  elLyricsResetBtn = document.getElementById('lyricsResetBtn');
  elRandomPlayBtn  = document.getElementById('randomPlayBtn');
  elOpenInYoutubeBtn = document.getElementById('openInYoutubeBtn');
  elFullscreenBtn  = document.getElementById('fullscreenBtn');
  elLyricsFsBtn    = document.getElementById('lyricsFsBtn');
  elExitKaraokeBtn = document.getElementById('exitKaraokeBtn');
  elArtistRandomBtn = document.getElementById('artistRandomBtn');

  elLyricsStartBtn.addEventListener('click', lyricsStartHere);
  elLyricsResetBtn.addEventListener('click', resetLyricsStart);
  elRandomPlayBtn.addEventListener('click', handleRandomPlay);
  elArtistRandomBtn.addEventListener('click', toggleArtistRandomPlay);
  elOpenInYoutubeBtn.addEventListener('click', openInYouTube);
  elFullscreenBtn.addEventListener('click', enterKaraokeMode);
  elLyricsFsBtn.addEventListener('click', enterLyricsFullscreen);
  elExitKaraokeBtn.addEventListener('click', exitKaraokeMode);
  elStageExitBtn.addEventListener('click', exitLyricsFullscreen);

  /* Esc to leave the maximized view even when not in real fullscreen */
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (document.body.classList.contains('lyrics-fs')) exitLyricsFullscreen();
    else if (document.body.classList.contains('karaoke-mode')) exitKaraokeMode();
  });

  /* Keep fullscreen body classes & auto-hiding controls in sync */
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement) {
      document.body.classList.remove('karaoke-mode');
      document.body.classList.remove('lyrics-fs');
      teardownFsControls();
    } else {
      setupFsControls();
    }
  });

  updateLyricsStartUI();

  /* Restore saved lyric display style (stack / scatter) */
  lyricStyle = localStorage.getItem('lyric_style') === 'scatter' ? 'scatter' : 'stack';
  applyLyricStyle();

  /* Restore saved background FX theme */
  const savedFx = localStorage.getItem('fx_theme');
  fxTheme = FX_THEMES.includes(savedFx) ? savedFx : 'rings';
  buildFx();

  /* Restore saved colour theme */
  colorTheme = localStorage.getItem('color_theme') === 'light' ? 'light' : 'dark';
  applyColorTheme();

  if (elCurrentOrigin) elCurrentOrigin.textContent = location.origin || location.href;

  /* A site-wide key in config.js takes over: hide the whole
     API-key panel so end users never see or enter a key. */
  if (getBuiltinKey()) {
    if (elApiSetup) elApiSetup.hidden = true;
  } else {
    const saved = localStorage.getItem('yt_api_key');
    if (saved) {
      elApiKeyInput.value = saved;
      const verified = localStorage.getItem('yt_api_key_verified') === '1';
      setApiKeyStatus(verified ? 'verified' : 'saved', saved);
      if (verified) setApiSetupCollapsed(true);
    } else {
      setApiKeyStatus('empty');
    }
  }

  elToggleApiSetup.addEventListener('click', () => {
    setApiSetupCollapsed(!elApiSetup.classList.contains('collapsed'));
  });

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
    setApiSetupCollapsed(false);
    setStatus('APIキーを削除しました。', '');
  });

  document.querySelectorAll('.ly-tab').forEach(tab =>
    tab.addEventListener('click', () => handleModeSwitch(tab.dataset.mode))
  );

  document.getElementById('searchForm').addEventListener('submit', handleFetch);
  elPlayPauseBtn.addEventListener('click', togglePlayPause);
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
  elPrevSongBtn.addEventListener('click', playPrevInQueue);

  /* Lyrics offset sliders — drag LEFT to play lyrics later,
     RIGHT to play earlier (±10 s). One lives under the video, a
     second copy appears in fullscreen. Both share the logic. */
  [elLyricsOffsetBar, elLyricsOffsetBarFs].forEach(bar => {
    if (!bar) return;
    bar.addEventListener('input', () => {
      state.lyricsOffset = -Number(bar.value) / 10;
      updateLyricsOffsetSliderDisplay();
      if (state.useLrc && state.ytPlayer && state.ytReady) {
        state.currentIndex = -1;
        syncLrc(state.ytPlayer.getCurrentTime() || 0);
      }
    });
    bar.addEventListener('change', () => {
      saveOffsetForCurrentSong();
      updateLyricsStartUI();
    });
  });

  /* Lyric display style toggle (stack <-> scatter) */
  elStyleToggleBtn.addEventListener('click', toggleLyricStyle);

  /* Background FX theme cycle */
  elFxThemeBtn.addEventListener('click', cycleFxTheme);

  /* Colour theme toggle (dark <-> light lyrics) */
  elColorToggleBtn.addEventListener('click', toggleColorTheme);

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
    playerVars: { autoplay: 0, controls: 0, rel: 0, modestbranding: 1, fs: 1, playsinline: 1 },
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
  const adjusted = currentSec - state.lyricsOffset;
  if (adjusted < 0) { clearStage(); return; }
  const lines = state.lrcLines;
  let idx = -1;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].time <= adjusted) { idx = i; break; }
  }
  if (idx >= 0 && idx !== state.currentIndex) {
    state.currentIndex = idx;
    renderLrcView();
  }
}

/* ============================================================
   Lyrics START (snap-to-now offset, per-song)
   ============================================================ */
function getOffsetKey() {
  const a = (state.currentArtist || '').toLowerCase().trim();
  const t = (state.currentTitle  || '').toLowerCase().trim();
  if (!a || !t) return null;
  return `lyrics_offset:${a}|${t}`;
}

function loadOffsetForCurrentSong() {
  const key = getOffsetKey();
  state.lyricsOffset = 0;
  if (key) {
    const saved = localStorage.getItem(key);
    if (saved) state.lyricsOffset = parseFloat(saved) || 0;
  }
  updateLyricsStartUI();
  updateLyricsOffsetSliderDisplay();
}

function saveOffsetForCurrentSong() {
  const key = getOffsetKey();
  if (!key) return;
  if (Math.abs(state.lyricsOffset) < 0.05) localStorage.removeItem(key);
  else localStorage.setItem(key, state.lyricsOffset.toFixed(2));
}

/**
 * Mark the current playback moment as the lyrics start point.
 * - LRC mode: offset so that the first LRC line appears at the
 *   current player time.
 * - Plain mode: offset = current time (timer cycles from line 0
 *   starting now).
 */
function lyricsStartHere() {
  if (!state.ytPlayer || !state.ytReady) {
    setStatus('動画を再生してから「歌詞START」を押してください。', 'error');
    return;
  }
  if (!state.lyrics.length) {
    /* No lyrics for this track — show in English on the stage + status */
    setStatus('No Lyrics', 'error');
    showStageMessage('No Lyrics');
    return;
  }
  const cur = state.ytPlayer.getCurrentTime() || 0;

  /* Wipe whatever is on screen so the new starting point is unambiguous */
  clearStage();

  if (state.useLrc && state.lrcLines.length) {
    state.lyricsOffset = Math.round((cur - state.lrcLines[0].time) * 100) / 100;
    state.currentIndex = -1;
    syncLrc(cur);
  } else {
    state.lyricsOffset = Math.round(cur * 100) / 100;
    state.currentIndex = 0;
    stopLyricsTimer();
    try {
      if (state.ytPlayer.getPlayerState() === YT.PlayerState.PLAYING) startLyricsTimer();
    } catch (_) {}
  }

  saveOffsetForCurrentSong();
  updateLyricsStartUI();
  updateLyricsOffsetSliderDisplay();
  flashLyricsStartBtn();
}

function resetLyricsStart() {
  state.lyricsOffset = 0;
  saveOffsetForCurrentSong();
  state.currentIndex = state.useLrc ? -1 : 0;
  if (state.ytPlayer && state.ytReady) {
    if (state.useLrc) {
      syncLrc(state.ytPlayer.getCurrentTime() || 0);
    } else {
      stopLyricsTimer();
      try {
        if (state.ytPlayer.getPlayerState() === YT.PlayerState.PLAYING) startLyricsTimer();
      } catch (_) {}
    }
  }
  updateLyricsStartUI();
  updateLyricsOffsetSliderDisplay();
  setStatus('歌詞タイミングをリセットしました。', '');
}

function updateLyricsStartUI() {
  if (!elLyricsStartBtn || !elLyricsResetBtn) return;
  const isSet = Math.abs(state.lyricsOffset) >= 0.05;
  elLyricsStartBtn.classList.toggle('active', isSet);
  elLyricsResetBtn.hidden = !isSet;
}

function flashLyricsStartBtn() {
  if (!elLyricsStartBtn) return;
  elLyricsStartBtn.classList.add('flash');
  setTimeout(() => elLyricsStartBtn.classList.remove('flash'), 700);
}

/* ============================================================
   Lyrics Timer (non-LRC fallback)
   ============================================================ */
function startLyricsTimer() {
  /* LRC mode is driven by pollProgress / syncLrc; running the
     fixed-interval plain timer alongside it would double up the
     same lines on screen. Bail out here. */
  if (state.useLrc) return;
  if (state.intervalId || state.startTimeoutId) return;
  if (!state.lyrics.length) return;
  state.isPlaying = true;

  const begin = () => {
    state.startTimeoutId = null;
    state.intervalId = setInterval(() => {
      if (state.currentIndex >= state.lyrics.length) state.currentIndex = 0;
      displayLine(state.lyrics[state.currentIndex++]);
    }, state.speed);
  };

  /* For plain mode, treat a positive offset as an intro-skip delay
     before the first lyric appears. */
  let delayMs = 0;
  if (state.ytPlayer && state.ytReady) {
    const cur = state.ytPlayer.getCurrentTime() || 0;
    delayMs = Math.max(0, (state.lyricsOffset - cur) * 1000);
  } else {
    delayMs = Math.max(0, state.lyricsOffset * 1000);
  }

  if (delayMs > 0) state.startTimeoutId = setTimeout(begin, delayMs);
  else begin();
}

function stopLyricsTimer() {
  state.isPlaying = false;
  if (state.intervalId)     { clearInterval(state.intervalId);     state.intervalId     = null; }
  if (state.startTimeoutId) { clearTimeout(state.startTimeoutId);  state.startTimeoutId = null; }
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
  document.body.classList.add('song-active'); /* enables ambient ripples */
  if (state.ytReady) {
    state.ytPlayer.loadVideoById(item.videoId);
    state.ytPlayer.setVolume(Number(elVolumeSlider.value));
  }
  setStatus(`再生中: ${escapeHTML(item.title)}`, 'success');
}

function playNextInQueue() {
  if (shuffleActive) {
    playNextInShuffle();
    return;
  }
  if (!state.ytQueue.length) return;
  loadYtVideo(state.ytQueueIdx + 1);
}

function playPrevInQueue() {
  if (shuffleActive) {
    playPrevInShuffle();
    return;
  }
  if (!state.ytQueue.length) return;
  loadYtVideo(state.ytQueueIdx - 1);
}

/* ============================================================
   Shuffle play — used by both the artist "ランダム再生" and the
   chart "おまかせ再生". Walks a pool of {artist,title} with full
   back/forward history.
   ============================================================ */
let shuffleActive  = false;
let shuffleKind    = null;   /* 'artist' | 'chart' */
let shuffleSource  = [];     /* full pool */
let shuffleQueue   = [];     /* shuffled, not-yet-played */
let shuffleHistory = [];     /* played songs (oldest first) */
let shuffleFuture  = [];     /* songs backed away from (next-to-pop first) */

function startShufflePlay(songs, kind, statusMsg) {
  if (!songs || !songs.length) {
    setStatus('再生できる曲がありません。', 'error');
    return false;
  }
  shuffleSource  = songs.slice();
  shuffleQueue   = shuffleArray(songs.slice());
  shuffleHistory = [];
  shuffleFuture  = [];
  shuffleActive  = true;
  shuffleKind    = kind;
  updateShuffleUI();
  if (statusMsg) setStatus(statusMsg, 'success');
  playNextInShuffle(true);
  return true;
}

function stopShufflePlay() {
  shuffleActive  = false;
  shuffleKind    = null;
  shuffleSource  = [];
  shuffleQueue   = [];
  shuffleHistory = [];
  shuffleFuture  = [];
  updateShuffleUI();
}

function playNextInShuffle(skipHistory = false) {
  if (!shuffleActive) return;
  /* Push current song to history so 前の曲 can return to it.
     skipHistory is set on the very first pick so the song that
     was playing before shuffle isn't treated as shuffle history. */
  if (!skipHistory && state.currentArtist && state.currentTitle) {
    shuffleHistory.push({ artist: state.currentArtist, title: state.currentTitle });
    if (shuffleHistory.length > 80) shuffleHistory.shift();
  }
  let next;
  if (shuffleFuture.length) {
    next = shuffleFuture.pop();
  } else {
    if (!shuffleQueue.length) {
      if (!shuffleSource.length) { stopShufflePlay(); return; }
      shuffleQueue = shuffleArray(shuffleSource.slice());
    }
    next = shuffleQueue.shift();
  }
  if (!next) { stopShufflePlay(); return; }
  handleSongSearch(next.artist, next.title, { autoplay: true });
}

function playPrevInShuffle() {
  if (!shuffleActive || !shuffleHistory.length) {
    setStatus('戻れる曲がありません。', 'error');
    return;
  }
  if (state.currentArtist && state.currentTitle) {
    shuffleFuture.push({ artist: state.currentArtist, title: state.currentTitle });
  }
  const prev = shuffleHistory.pop();
  handleSongSearch(prev.artist, prev.title, { autoplay: true });
}

/* ---- Artist random play (top 30 of the iTunes list) ---- */
function toggleArtistRandomPlay() {
  if (shuffleActive && shuffleKind === 'artist') {
    stopShufflePlay();
    setStatus('アーティストのランダム再生を停止しました。', '');
  } else {
    startArtistRandomPlay();
  }
}

function startArtistRandomPlay() {
  if (!songList.songs.length) {
    setStatus('アーティストの曲リストがありません。先に検索してください。', 'error');
    return;
  }
  /* Use the whole fetched (deduped) catalogue, not just the top
     few, so the shuffle has real variety instead of always
     surfacing the same popular tracks. */
  const pool = songList.songs.slice();
  startShufflePlay(
    pool, 'artist',
    `🎲 ${escapeHTML(songList.songs[0]?.artist || '')} の${pool.length}曲からランダム再生中`
  );
}

function updateShuffleUI() {
  if (elArtistRandomBtn) {
    const isArtist = shuffleActive && shuffleKind === 'artist';
    elArtistRandomBtn.classList.toggle('active', isArtist);
    elArtistRandomBtn.textContent = isArtist
      ? '⏹ ランダム再生を停止'
      : '🎲 このアーティストでランダム再生';
  }
  if (elRandomPlayBtn) {
    const isChart = shuffleActive && shuffleKind === 'chart';
    elRandomPlayBtn.classList.toggle('active', isChart);
    elRandomPlayBtn.textContent = isChart
      ? '⏹ おまかせ再生を停止'
      : '🎲 おまかせ再生（人気ランキング）';
  }
  if (elNextSongBtn) {
    elNextSongBtn.textContent = shuffleActive ? '🎲 次の曲 ▶▶' : '次の曲 ▶▶';
  }
}

function shuffleArray(arr) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

/* ============================================================
   Open current video in new tab / toggle fullscreen
   ============================================================ */
function openInYouTube() {
  if (!state.ytQueue.length) {
    setStatus('再生中の動画がありません。', 'error');
    return;
  }
  const item = state.ytQueue[state.ytQueueIdx];
  if (!item) return;
  const t = state.ytPlayer && state.ytReady
    ? Math.floor(state.ytPlayer.getCurrentTime() || 0)
    : 0;
  const url = `https://www.youtube.com/watch?v=${encodeURIComponent(item.videoId)}${t > 1 ? `&t=${t}s` : ''}`;
  /* Pause embedded so audio doesn't double up with the new tab */
  if (state.ytPlayer && state.ytReady) {
    try { state.ytPlayer.pauseVideo(); } catch (_) {}
  }
  window.open(url, '_blank', 'noopener,noreferrer');
}

/* Best-effort native fullscreen. Works on desktop/Android; iOS
   Safari ignores it for non-video elements — that's fine because
   the maximized look is driven by CSS body classes, not the
   Fullscreen API. */
function tryRequestFullscreen(el) {
  const req = el && (el.requestFullscreen || el.webkitRequestFullscreen || el.msRequestFullscreen);
  if (!req) return;
  try { Promise.resolve(req.call(el)).catch(() => {}); } catch (_) {}
}

function tryExitFullscreen() {
  if (document.fullscreenElement && document.exitFullscreen) {
    document.exitFullscreen().catch(() => {});
  }
}

/**
 * Karaoke mode: video on top (50vh), lyrics below. The maximized
 * layout is a CSS overlay (body.karaoke-mode), so it works even
 * where the Fullscreen API is unavailable; we still request real
 * fullscreen as an enhancement.
 */
function enterKaraokeMode() {
  if (document.body.classList.contains('karaoke-mode')) return;
  document.body.classList.remove('lyrics-fs');
  document.body.classList.add('karaoke-mode');
  setupFsControls();
  tryRequestFullscreen(document.documentElement);
}

function exitKaraokeMode() {
  document.body.classList.remove('karaoke-mode');
  teardownFsControls();
  tryExitFullscreen();
}

/**
 * Lyrics-only mode: show just the title + lyrics, covering the
 * viewport via a CSS overlay (body.lyrics-fs). The video iframe
 * stays in the DOM so audio keeps playing.
 */
function enterLyricsFullscreen() {
  if (!elStage) return;
  if (document.body.classList.contains('lyrics-fs')) return;
  document.body.classList.remove('karaoke-mode');
  document.body.classList.add('lyrics-fs');
  setupFsControls();
  tryRequestFullscreen(elStage);
}

function exitLyricsFullscreen() {
  document.body.classList.remove('lyrics-fs');
  teardownFsControls();
  tryExitFullscreen();
}

/* ---- Auto-hiding fullscreen controls (shrink button) ---- */
let fsHideTimer = null;
const FS_ACTIVITY_EVENTS = ['mousemove', 'pointerdown', 'touchstart', 'keydown'];

function setupFsControls() {
  /* idempotent: drop any existing listeners before re-adding */
  FS_ACTIVITY_EVENTS.forEach(ev => document.removeEventListener(ev, pokeFsControls));
  pokeFsControls();
  FS_ACTIVITY_EVENTS.forEach(ev =>
    document.addEventListener(ev, pokeFsControls, { passive: true })
  );
}

function teardownFsControls() {
  FS_ACTIVITY_EVENTS.forEach(ev => document.removeEventListener(ev, pokeFsControls));
  clearTimeout(fsHideTimer);
  document.body.classList.remove('fs-controls-visible');
}

/* Show the shrink button, then fade it out after a few seconds
   of no mouse/touch activity. */
function pokeFsControls() {
  document.body.classList.add('fs-controls-visible');
  clearTimeout(fsHideTimer);
  fsHideTimer = setTimeout(() => {
    document.body.classList.remove('fs-controls-visible');
  }, 2800);
}

/* ============================================================
   おまかせ再生 — iTunes(Apple Music)Japan の人気チャート
   (トップ曲)から 曲名+アーティストを取得してシャッフル再生。
   ※オリコンは公開APIが無いため、CORS対応の itunes.apple.com の
     RSS フィードをランキングソースとして利用（検索APIと同ドメイン）。
   ============================================================ */
const TOP_CHART_URL = 'https://itunes.apple.com/jp/rss/topsongs/limit=100/json';
let topChartCache = null;

/* Fallback list of well-known JP songs, used only if the chart
   feed can't be fetched, so おまかせ never hard-fails. */
const FALLBACK_CHART = [
  { artist: 'YOASOBI', title: '夜に駆ける' },
  { artist: '米津玄師', title: 'Lemon' },
  { artist: 'Official髭男dism', title: 'Pretender' },
  { artist: 'あいみょん', title: 'マリーゴールド' },
  { artist: 'King Gnu', title: '白日' },
  { artist: 'LiSA', title: '紅蓮華' },
  { artist: 'Mrs. GREEN APPLE', title: '青と夏' },
  { artist: 'back number', title: '高嶺の花子さん' },
  { artist: 'SUPER BEAVER', title: '美しい日' },
  { artist: 'Vaundy', title: '怪獣の花唄' },
  { artist: 'Ado', title: 'うっせぇわ' },
  { artist: '優里', title: 'ドライフラワー' },
  { artist: 'Aimer', title: '残響散歌' },
  { artist: '緑黄色社会', title: 'Mela!' },
  { artist: 'スピッツ', title: '空も飛べるはず' },
  { artist: 'Mr.Children', title: '終わりなき旅' },
  { artist: 'B\'z', title: 'ultra soul' },
  { artist: 'サザンオールスターズ', title: 'TSUNAMI' },
  { artist: '宇多田ヒカル', title: 'First Love' },
  { artist: '中島みゆき', title: '糸' },
  { artist: 'いきものがかり', title: 'ありがとう' },
  { artist: 'RADWIMPS', title: '前前前世' },
  { artist: 'ヨルシカ', title: '花に亡霊' },
  { artist: 'Saucy Dog', title: 'シンデレラボーイ' },
  { artist: '藤井 風', title: '死ぬのがいいわ' },
];

async function fetchTopChartSongs() {
  const res = await fetchWithTimeout(TOP_CHART_URL, 12000);
  if (!res.ok) throw new Error(`ランキング取得エラー (HTTP ${res.status})`);
  const data = await res.json();
  let entries = (data && data.feed && data.feed.entry) || [];
  if (!Array.isArray(entries)) entries = [entries];
  return entries
    .map(e => ({
      artist: (e && e['im:artist'] && e['im:artist'].label || '').trim(),
      title:  (e && e['im:name']   && e['im:name'].label   || '').trim(),
    }))
    .filter(s => s.artist && s.title);
}

async function handleRandomPlay() {
  /* Toggle off if a chart shuffle is already running */
  if (shuffleActive && shuffleKind === 'chart') {
    stopShufflePlay();
    setStatus('おまかせ再生を停止しました。', '');
    return;
  }
  if (!getApiKey()) {
    setStatus('YouTube APIキーを先に設定してください。', 'error');
    setApiSetupCollapsed(false);
    return;
  }

  stopShufflePlay();
  setStatus('🎲 人気ランキングを取得しています...', 'loading');
  hideSongList();
  hideYtResults();
  elRandomPlayBtn.disabled = true;

  try {
    if (!topChartCache || !topChartCache.length) {
      try {
        topChartCache = await fetchTopChartSongs();
      } catch (e) {
        /* Network/CORS issue — fall back to the curated list */
        console.warn('Chart fetch failed, using fallback list:', e);
        topChartCache = FALLBACK_CHART.slice();
      }
    }
    if (!topChartCache.length) topChartCache = FALLBACK_CHART.slice();

    startShufflePlay(
      topChartCache, 'chart',
      `🎲 人気曲${topChartCache.length}曲からおまかせ再生中`
    );
  } catch (err) {
    setStatus(`おまかせ再生に失敗: ${err.message}`, 'error');
  } finally {
    elRandomPlayBtn.disabled = false;
  }
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

function setApiSetupCollapsed(collapsed) {
  if (!elApiSetup || !elToggleApiSetup) return;
  elApiSetup.classList.toggle('collapsed', collapsed);
  elToggleApiSetup.setAttribute('aria-expanded', String(!collapsed));
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
    setApiSetupCollapsed(true);
  } catch (err) {
    setApiKeyStatus('invalid', k);
    setStatus(`APIキーの検証に失敗: ${err.message}`, 'error');
    setApiSetupCollapsed(false);
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
  }

  /* Message-based fallback patterns */
  if (/API keys are not supported/i.test(msg)) {
    return 'APIキーがサービスアカウントにバインドされています。Cloud Console →「認証情報」→ 該当キーを編集 →「サービスアカウントを介して API 呼び出しを認証する」のチェックを外して保存してください。';
  }
  if (/API key not valid/i.test(msg)) {
    return 'APIキーが無効です。コピーミスがないか確認し、Cloud Console で再発行してください。';
  }
  if (/has not been used|disabled|consumer/i.test(msg)) {
    return 'YouTube Data API v3 が有効化されていません。Cloud Console の「ライブラリ」で有効にしてください（数分かかる場合あり）。';
  }

  return msg;
}

async function searchYouTube(query) {
  const key = getApiKey();
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
    if ((res.status === 400 || res.status === 403) && !getBuiltinKey()) {
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
  stopShufflePlay();
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
  stopShufflePlay();
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
  stopShufflePlay();
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

/* Strip spacing/punctuation/case so "SUPER BEAVER",
   "superbeaver", "Super-Beaver" all collapse to "superbeaver" */
function normalizeArtistName(s) {
  return (s || '')
    .toLowerCase()
    .replace(/[\s.\-_'"`’()&,!?・·]+/g, '')
    .trim();
}

async function fetchSongsByArtist(artist) {
  const url = `${ITUNES_API}?term=${encodeURIComponent(artist)}&entity=song&limit=100&country=jp`;
  const res  = await fetchWithTimeout(url, 10000);
  if (!res.ok) throw new Error(`iTunes API エラー (HTTP ${res.status})`);
  const data = await res.json();

  const raw = (data.results || []).filter(r => r.trackName && r.artistName);
  if (!raw.length) return [];

  /* Try to keep only songs whose artist actually matches the query
     (helps random play stay on-artist), but fall back to the full
     list if the filter is too aggressive */
  const target = normalizeArtistName(artist);
  let matching = raw;
  if (target) {
    const filtered = raw.filter(r => {
      const a = normalizeArtistName(r.artistName);
      return a && (a.includes(target) || target.includes(a));
    });
    if (filtered.length) matching = filtered;
  }

  const mapped = matching.map(r => ({
    artist:     r.artistName,
    title:      r.trackName,
    album:      r.collectionName || '',
    durationMs: r.trackTimeMillis || 0,
  }));

  /* Dedupe by normalised title — iTunes returns the same song
     multiple times across different albums / remasters */
  const seen = new Set();
  return mapped.filter(s => {
    const key = (s.title || '').toLowerCase().replace(/\s+/g, ' ').trim();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/* ============================================================
   Song search (lyrics + YouTube)
   ============================================================ */
/* In-memory caches so back/forth navigation and prefetches don't
   re-hit the YouTube/lyrics APIs. */
const ytSearchCache = new Map();
const lyricsCache   = new Map();   /* value: lyrics-string OR null = known-miss */
const FETCH_CACHE_MAX = 80;

function cachePut(map, key, val) {
  if (map.size >= FETCH_CACHE_MAX) map.delete(map.keys().next().value);
  map.set(key, val);
}

async function searchYouTubeCached(query) {
  const cached = ytSearchCache.get(query);
  if (cached) return cached;
  const result = await searchYouTube(query);
  cachePut(ytSearchCache, query, result);
  return result;
}

async function fetchLyricsCached(artist, title) {
  const key = `${artist}|${title}`;
  if (lyricsCache.has(key)) return lyricsCache.get(key);
  /* Don't cache failures — a transient network glitch shouldn't
     permanently mark a song as "No Lyrics". */
  const raw = await fetchLyricsWithFallback(artist, title);
  cachePut(lyricsCache, key, raw);
  return raw;
}

async function handleSongSearch(artist, title, opts = {}) {
  const autoplay = opts.autoplay !== false; /* default true */
  state.currentArtist = artist;
  state.currentTitle  = title;
  loadOffsetForCurrentSong();
  setNowPlayingTitle(artist, title);
  hideStageMessage();   /* clear "No Lyrics" from the previous song */

  setStatus(`「${escapeHTML(title)}」を検索中...`, 'loading');
  elFetchBtn.disabled = true;
  hideYtResults();

  /* Reset lyrics state — populated when the lyrics fetch resolves
     (which may be AFTER the video already starts). */
  state.lyrics = [];
  state.lrcLines = [];
  state.useLrc = false;
  state.currentIndex = -1;

  /* Kick off both fetches in parallel, but DON'T await both before
     starting playback — load the video as soon as YouTube returns
     so "next song" feels responsive. */
  const ytPromise     = searchYouTubeCached(`${artist} ${title}`);
  const lyricsPromise = fetchLyricsCached(artist, title);

  /* Apply lyrics whenever they arrive (often after the video) */
  lyricsPromise
    .then(raw => {
      if (state.currentArtist !== artist || state.currentTitle !== title) return; /* user moved on */
      loadLyrics(raw);
      /* Plain-mode timer needs a kick if the player is already PLAYING */
      if (state.ytPlayer && state.ytReady && !state.useLrc && state.lyrics.length) {
        try {
          if (state.ytPlayer.getPlayerState() === YT.PlayerState.PLAYING) startLyricsTimer();
        } catch (_) {}
      }
    })
    .catch(() => {
      if (state.currentArtist !== artist || state.currentTitle !== title) return;
      state.lyrics = [];
      state.lrcLines = [];
      /* Wait a beat before declaring the song lyric-less. If the
         user advances to another song within this window we skip
         the message entirely; if lyrics genuinely don't exist the
         message appears as a steady "No Lyrics", not a flash. */
      setTimeout(() => {
        if (state.currentArtist !== artist || state.currentTitle !== title) return;
        if (state.lyrics.length) return; /* lyrics arrived via another path */
        showStageMessage('No Lyrics');
      }, 900);
    });

  try {
    const ytList = await ytPromise;
    if (state.currentArtist !== artist || state.currentTitle !== title) return; /* user moved on */

    elFetchBtn.disabled = false;

    if (ytList && ytList.length) {
      state.ytQueue    = ytList;
      state.ytQueueIdx = 0;
      showYtResults(ytList, !autoplay);
      enableTransportControls(true);
      if (autoplay) loadYtVideo(0);
      setStatus(`▶ ${escapeHTML(title)}`, 'success');
      return;
    }

    /* Empty YT result — fall back to lyrics-only mode if lyrics arrive */
    const lyrics = await lyricsPromise.catch(() => null);
    if (lyrics && state.lyrics.length) {
      clearStage();
      state.currentIndex = 0;
      elPlayPauseBtn.disabled = false;
      setStatus(`「${escapeHTML(title)}」の歌詞のみ取得（YouTube結果なし）。▶で開始。`, 'success');
    } else {
      setStatus('歌詞もYouTubeも見つかりませんでした。曲名を変えて試してください。', 'error');
    }
  } catch (err) {
    elFetchBtn.disabled = false;
    const lyrics = await lyricsPromise.catch(() => null);
    if (lyrics && state.lyrics.length) {
      clearStage();
      state.currentIndex = 0;
      elPlayPauseBtn.disabled = false;
      setStatus(`「${escapeHTML(title)}」の歌詞を取得しました（YouTube: ${err.message}）。▶で開始。`, 'error');
    } else {
      setStatus(`YouTube検索エラー: ${err.message}`, 'error');
    }
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
  hideStageMessage();
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
    card.addEventListener('click', () => {
      stopShufflePlay();
      hideSongList();
      handleSongSearch(song.artist, song.title);
    });
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
function showYtResults(items, scroll = true) {
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
    card.addEventListener('click', () => {
      hideYtResults();
      loadYtVideo(idx);
    });
    elYtResultCards.appendChild(card);
  });

  elYtResults.hidden = false;
  if (scroll) elYtResults.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function hideYtResults() { elYtResults.hidden = true; }

/* ============================================================
   Lyric display — rect collision + vertical band rotation
   ============================================================ */
function ensureStageSlots() {
  if (STAGE_SLOTS.length === 5 && elLyricStack && elLyricStack.contains(STAGE_SLOTS[0])) return;
  STAGE_SLOTS.length = 0;
  elLyricStack.innerHTML = '';
  STAGE_SLOT_CLASSES.forEach(cls => {
    const el = document.createElement('div');
    el.className = `ly-line ${cls}`;
    elLyricStack.appendChild(el);
    STAGE_SLOTS.push(el);
  });
}

/** Set the small now-playing title above the lyrics */
function setNowPlayingTitle(artist, title) {
  if (!elNowPlaying) return;
  const t = (title || '').trim();
  const a = (artist || '').trim();
  if (t) {
    elNowPlaying.textContent = a ? `♪ ${t} — ${a}` : `♪ ${t}`;
    elNowPlaying.classList.add('visible');
  } else {
    elNowPlaying.textContent = '';
    elNowPlaying.classList.remove('visible');
  }
}

/* Directional entrance variants cycled in fullscreen for a more
   "Lyric Speaker" feel (up / zoom / left / down / right). */
const ENTER_DIRS = ['dir-up', 'dir-zoom', 'dir-left', 'dir-down', 'dir-right'];
let enterDirIdx = 0;

/** Update the 5 vertical slots. animateCurrent triggers the
 *  fade-in animation on the centre line whenever its text
 *  actually changes. */
function renderLyricStage(prevLines, current, nextLines, animateCurrent = true) {
  ensureStageSlots();
  const texts = [
    (prevLines[1] || '').trim(),   /* far past (older) */
    (prevLines[0] || '').trim(),   /* past (recent) */
    (current     || '').trim(),    /* current */
    (nextLines[0] || '').trim(),   /* next */
    (nextLines[1] || '').trim(),   /* far next */
  ];
  for (let i = 0; i < 5; i++) {
    const slot = STAGE_SLOTS[i];
    if (slot.textContent !== texts[i]) {
      slot.textContent = texts[i];
      if (i === 2 && animateCurrent && texts[i]) {
        slot.classList.remove('enter', ...ENTER_DIRS);
        /* force reflow so the animation restarts */
        void slot.offsetWidth;
        slot.classList.add('enter');
        /* In fullscreen, vary the entrance direction each line */
        if (document.fullscreenElement) {
          slot.classList.add(ENTER_DIRS[enterDirIdx % ENTER_DIRS.length]);
          enterDirIdx++;
        }
      }
    }
  }
}

/** Show a one-off message (e.g. "No Lyrics") as a centered overlay
 *  on the stage. Visible in both stack and scatter modes. */
function showStageMessage(msg) {
  if (!elStage) return;
  let overlay = elStage.querySelector('.ly-stage-msg');
  if (!overlay) {
    overlay = document.createElement('div');
    overlay.className = 'ly-stage-msg';
    elStage.appendChild(overlay);
  }
  overlay.textContent = msg;
  overlay.classList.remove('enter');
  void overlay.offsetWidth;
  overlay.classList.add('enter');
}

function hideStageMessage() {
  if (!elStage) return;
  const overlay = elStage.querySelector('.ly-stage-msg');
  if (overlay) overlay.remove();
}

/** Re-paint the LRC view based on state.currentIndex */
function renderLrcView() {
  const idx = state.currentIndex;
  if (idx < 0 || !state.lrcLines.length) { return; }
  const lines = state.lrcLines;
  const text = lines[idx].text;
  if (lyricStyle === 'scatter') {
    spawnScatterToken(text);
    return;
  }
  const textArr = lines.map(l => l.text);
  renderLyricStage(
    [textArr[idx - 1], textArr[idx - 2]],
    textArr[idx],
    [textArr[idx + 1], textArr[idx + 2]]
  );
}

/** Plain-mode (no LRC timestamps): push a new line and re-render
 *  the stack using the last few shown lines as history. */
function displayLine(text) {
  if (!text || !text.trim()) return;
  const trimmed = text.trim();
  if (plainHistory[0] === trimmed) return; /* skip repeat */
  plainHistory.unshift(trimmed);
  if (plainHistory.length > PLAIN_HISTORY + 1) plainHistory.length = PLAIN_HISTORY + 1;
  if (lyricStyle === 'scatter') {
    spawnScatterToken(trimmed);
    return;
  }
  renderLyricStage(
    [plainHistory[1], plainHistory[2]],
    plainHistory[0],
    []
  );
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
  if (elPrevSongBtn) elPrevSongBtn.disabled = !on;
}

function updateLyricsOffsetSliderDisplay() {
  const v = state.lyricsOffset || 0;
  /* slider is inverted: left = later (+), right = earlier (-) */
  const sliderVal = Math.max(-100, Math.min(100, Math.round(-v * 10)));
  [elLyricsOffsetBar, elLyricsOffsetBarFs].forEach(bar => {
    if (bar && Number(bar.value) !== sliderVal) bar.value = String(sliderVal);
  });
  const sign = v > 0 ? '+' : (v < 0 ? '−' : '±');
  const txt = `${sign}${Math.abs(v).toFixed(1)}s`;
  if (elLyricsOffsetDisplay)   elLyricsOffsetDisplay.textContent = txt;
  if (elLyricsOffsetDisplayFs) elLyricsOffsetDisplayFs.textContent = txt;
}

/* ============================================================
   Lyric display style (stack <-> scatter)
   ============================================================ */
function toggleLyricStyle() {
  lyricStyle = (lyricStyle === 'scatter') ? 'stack' : 'scatter';
  localStorage.setItem('lyric_style', lyricStyle);
  applyLyricStyle();
  /* Repaint current line in the new style */
  clearStage();
  if (state.useLrc) {
    state.currentIndex = -1;
    if (state.ytPlayer && state.ytReady) syncLrc(state.ytPlayer.getCurrentTime() || 0);
  }
}

function applyLyricStyle() {
  document.body.classList.toggle('lyric-scatter', lyricStyle === 'scatter');
  if (elStyleToggleBtn) {
    const scatter = lyricStyle === 'scatter';
    elStyleToggleBtn.classList.toggle('active', scatter);
    elStyleToggleBtn.textContent = scatter ? '🎨ランダム' : '🎨スタック';
  }
}

/* Colour theme (lyrics on dark vs. light background) */
function toggleColorTheme() {
  colorTheme = (colorTheme === 'light') ? 'dark' : 'light';
  localStorage.setItem('color_theme', colorTheme);
  applyColorTheme();
}

function applyColorTheme() {
  document.body.classList.toggle('theme-light', colorTheme === 'light');
  if (elColorToggleBtn) {
    elColorToggleBtn.textContent = colorTheme === 'light' ? '☀ライト' : '🌙ダーク';
  }
}

/* ============================================================
   Background FX themes
   (rings / stars / streaks / squares / triangles / mix)
   ============================================================ */
const FX_THEMES = ['rings', 'stars', 'streaks', 'squares', 'triangles', 'mix'];
const FX_LABELS = {
  rings:    '✨リング',
  stars:    '✨スター',
  streaks:  '✨流星',
  squares:  '✨スクエア',
  triangles:'✨三角',
  mix:      '✨ミックス',
};
const SQUARE_TINTS = [
  'rgba(167,139,250,0.85)', 'rgba(244,114,182,0.82)',
  'rgba(96,165,250,0.82)', 'rgba(52,211,153,0.78)',
  'rgba(251,191,36,0.78)',
];

function cycleFxTheme() {
  const i = FX_THEMES.indexOf(fxTheme);
  fxTheme = FX_THEMES[(i + 1) % FX_THEMES.length];
  localStorage.setItem('fx_theme', fxTheme);
  buildFx();
}

/* Populate the .ly-fx layer with the elements for the active theme */
function buildFx() {
  if (!elFx) return;
  elFx.innerHTML = '';
  elFx.dataset.theme = fxTheme;
  if (elFxThemeBtn) elFxThemeBtn.textContent = FX_LABELS[fxTheme] || '✨';

  if (fxTheme === 'rings') {
    for (let i = 0; i < 6; i++) elFx.appendChild(mkEl('span', 'ly-ripple'));

  } else if (fxTheme === 'stars') {
    for (let i = 0; i < 30; i++) {
      const p = mkEl('span', 'ly-particle');
      const sz = (2 + Math.random() * 5).toFixed(1);
      p.style.left   = (Math.random() * 100).toFixed(1) + '%';
      p.style.top    = (Math.random() * 100).toFixed(1) + '%';
      p.style.width  = sz + 'px';
      p.style.height = sz + 'px';
      p.style.animationDuration = (2.5 + Math.random() * 4).toFixed(2) + 's';
      p.style.animationDelay    = (Math.random() * 5).toFixed(2) + 's';
      elFx.appendChild(p);
    }

  } else if (fxTheme === 'streaks') {
    for (let i = 0; i < 12; i++) {
      const s = mkEl('span', 'ly-streak');
      s.style.left  = (Math.random() * 90 - 10).toFixed(1) + '%';
      s.style.top   = (Math.random() * 70 - 20).toFixed(1) + '%';
      s.style.width = (18 + Math.random() * 26).toFixed(1) + 'vmin';
      s.style.animationDuration = (2.2 + Math.random() * 2.6).toFixed(2) + 's';
      s.style.animationDelay    = (Math.random() * 4).toFixed(2) + 's';
      elFx.appendChild(s);
    }

  } else if (fxTheme === 'squares') {
    for (let i = 0; i < 8; i++) elFx.appendChild(buildBlinker('square', i));
  } else if (fxTheme === 'triangles') {
    for (let i = 0; i < 8; i++) elFx.appendChild(buildBlinker('triangle', i));
  } else if (fxTheme === 'mix') {
    const shapes = ['ring', 'square', 'triangle'];
    for (let i = 0; i < 9; i++) {
      const s = shapes[Math.floor(Math.random() * shapes.length)];
      elFx.appendChild(buildBlinker(s, i));
    }
  }
}

/* Shared "expand & blink" element builder. Supports square /
   triangle / diamond / ring shapes — all using the same scale +
   rotate keyframe so they pop in, expand and fade with a gap. */
function buildBlinker(kind, idx) {
  const tint = SQUARE_TINTS[idx % SQUARE_TINTS.length];
  const w   = 12 + Math.random() * 18;
  const ar  = (kind === 'square' || kind === 'mix-rect')
    ? 0.5 + Math.random() * 1.3
    : 1;
  const left = (10 + Math.random() * 80).toFixed(1) + '%';
  const top  = (14 + Math.random() * 68).toFixed(1) + '%';
  const rot  = (kind === 'diamond')
    ? (45 + Math.random() * 30 - 15)
    : (Math.random() * 60 - 30);
  const dur  = (6.5 + Math.random() * 2).toFixed(2) + 's';
  const delay = (idx * 0.75).toFixed(2) + 's';
  const shadowTint = tint.replace(/[\d.]+\)$/, '0.3)');

  if (kind === 'triangle') {
    const sv = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    sv.setAttribute('viewBox', '0 0 100 100');
    sv.setAttribute('preserveAspectRatio', 'none');
    sv.classList.add('ly-triangle');
    const pg = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
    pg.setAttribute('points', '50,8 92,90 8,90');
    pg.setAttribute('fill', 'none');
    pg.setAttribute('stroke', tint);
    pg.setAttribute('stroke-width', '4');
    pg.setAttribute('stroke-linejoin', 'round');
    sv.appendChild(pg);
    sv.style.left = left; sv.style.top = top;
    sv.style.width = w.toFixed(1) + 'vmin';
    sv.style.height = w.toFixed(1) + 'vmin';
    sv.style.filter = `drop-shadow(0 0 16px ${shadowTint})`;
    sv.style.setProperty('--rot', rot.toFixed(0) + 'deg');
    sv.style.animationDuration = dur;
    sv.style.animationDelay = delay;
    return sv;
  }

  if (kind === 'ring') {
    const el = mkEl('span', 'ly-square ly-ring');
    el.style.left = left; el.style.top = top;
    el.style.width = w.toFixed(1) + 'vmin';
    el.style.height = w.toFixed(1) + 'vmin';
    el.style.borderColor = tint;
    el.style.boxShadow = `0 0 22px ${shadowTint}`;
    el.style.setProperty('--rot', rot.toFixed(0) + 'deg');
    el.style.animationDuration = dur;
    el.style.animationDelay = delay;
    return el;
  }

  /* square or diamond */
  const el = mkEl('span', 'ly-square');
  el.style.left = left; el.style.top = top;
  el.style.width = w.toFixed(1) + 'vmin';
  el.style.height = (w * ar).toFixed(1) + 'vmin';
  el.style.borderColor = tint;
  el.style.boxShadow = `0 0 22px ${shadowTint}`;
  el.style.setProperty('--rot', rot.toFixed(0) + 'deg');
  el.style.animationDuration = dur;
  el.style.animationDelay = delay;
  return el;
}

function mkEl(tag, className) {
  const el = document.createElement(tag);
  el.className = className;
  return el;
}

/* ---- Scatter tokens: one token per line, with one word
   visually emphasized in a vivid colour & larger size ---- */
const SCATTER_MAX  = 4;
const SCATTER_GAP  = 22;
const EMPH_COLORS  = [
  ['#ec4899', 'rgba(236,72,153,0.65)'],   /* pink */
  ['#fbbf24', 'rgba(251,191,36,0.65)'],   /* amber */
  ['#22d3ee', 'rgba(34,211,238,0.65)'],   /* cyan */
  ['#4ade80', 'rgba(74,222,128,0.6)'],    /* green */
  ['#c084fc', 'rgba(192,132,252,0.65)'],  /* violet */
  ['#f87171', 'rgba(248,113,113,0.6)'],   /* coral */
];

/* Build the token text with one word/chunk wrapped in a vivid
   .ly-emph span. Picks a whitespace-delimited word if available,
   otherwise a short character cluster from the middle of the
   line (for Japanese without spaces). */
function applyEmphasizedText(el, text) {
  el.textContent = '';
  let before = '', emph = '', after = '';

  /* Word-based: pick a random space-separated word */
  const parts = text.split(/(\s+)/);
  const wordIdxs = [];
  for (let i = 0; i < parts.length; i++) if (i % 2 === 0 && parts[i].trim()) wordIdxs.push(i);
  if (wordIdxs.length >= 2) {
    const pickIdx = wordIdxs[Math.floor(Math.random() * wordIdxs.length)];
    before = parts.slice(0, pickIdx).join('');
    emph   = parts[pickIdx];
    after  = parts.slice(pickIdx + 1).join('');
  } else if (text.length >= 5) {
    /* Run of characters with no whitespace: pick a chunk near the
       middle (avoid both ends so the line still reads naturally) */
    const len = text.length;
    const chunkLen = Math.min(4, Math.max(2, Math.floor(len / 4)));
    const lo = Math.floor((len - chunkLen) * 0.2);
    const hi = Math.floor((len - chunkLen) * 0.8);
    const start = randomInt(Math.max(0, lo), Math.max(0, hi));
    before = text.slice(0, start);
    emph   = text.slice(start, start + chunkLen);
    after  = text.slice(start + chunkLen);
  } else {
    /* Short line — just emphasise the whole thing */
    emph = text;
  }

  if (before) el.appendChild(document.createTextNode(before));
  if (emph) {
    const [color, glow] = EMPH_COLORS[Math.floor(Math.random() * EMPH_COLORS.length)];
    const span = document.createElement('span');
    span.className = 'ly-emph';
    span.style.color = color;
    span.style.textShadow = `0 2px 12px rgba(0,0,0,0.6), 0 0 22px ${glow}`;
    span.textContent = emph;
    el.appendChild(span);
  }
  if (after) el.appendChild(document.createTextNode(after));
}

function spawnScatterToken(text) {
  if (!elScatterLayer || !text || !text.trim()) return;
  const trimmed = text.trim();
  const live = [...elScatterLayer.querySelectorAll('.ly-scatter-token:not(.out)')];
  if (live.some(t => t.dataset.text === trimmed)) return; /* dedup */

  if (live.length >= SCATTER_MAX) {
    live.sort((a, b) => Number(a.dataset.born || 0) - Number(b.dataset.born || 0));
    fadeOutScatter(live[0]);
  }

  const el = document.createElement('div');
  el.className     = 'ly-scatter-token';
  applyEmphasizedText(el, trimmed);
  el.dataset.text  = trimmed;
  el.dataset.born  = String(Date.now());

  const sw = elScatterLayer.clientWidth  || 320;
  const sh = elScatterLayer.clientHeight || 200;
  const scale = Math.max(0.62, Math.min(1.3, sw / 680));
  const vertical = sh > 260 && Math.random() < 0.38; /* ~38% vertical */
  if (vertical) el.classList.add('vertical');

  /* Whole-line tokens: short lines get bold-emphasis sizing,
     longer lines lean smaller so they fit on one or two rows. */
  const charLen = trimmed.length;
  const r = Math.random();
  let size;
  if (charLen <= 6)        size = randomInt(Math.round(54 * scale), Math.round(96 * scale));
  else if (charLen <= 12)  size = randomInt(Math.round(42 * scale), Math.round(72 * scale));
  else if (charLen <= 20)  size = randomInt(Math.round(32 * scale), Math.round(54 * scale));
  else if (charLen <= 30)  size = randomInt(Math.round(26 * scale), Math.round(42 * scale));
  else                     size = randomInt(Math.round(20 * scale), Math.round(32 * scale));
  if (r < 0.18) size = Math.round(size * 1.18); /* occasional extra-large */
  el.style.fontSize = `${size}px`;

  const enterMs = 0.32 + Math.random() * 0.55;   /* 0.32 – 0.87 s */
  const driftMs = 5 + Math.random() * 6;          /* 5 – 11 s drift cycle */
  const lifeMs  = 4500 + Math.random() * 4500;    /* 4.5 – 9 s */
  el.style.setProperty('--enter-ms', enterMs.toFixed(2) + 's');
  el.style.setProperty('--drift-ms', driftMs.toFixed(2) + 's');
  /* Slight tilt for horizontal tokens (vertical stays upright) */
  if (!vertical) {
    el.style.setProperty('--tilt', (Math.random() * 16 - 8).toFixed(1) + 'deg');
  }

  el.style.left = '-9999px';
  el.style.top  = '-9999px';
  elScatterLayer.appendChild(el);

  /* Shrink only if it still doesn't fit after wrapping. */
  const maxW = sw - 16;
  const maxH = sh - 46;
  let guard = 16;
  while ((el.offsetHeight > maxH || el.offsetWidth > maxW) && size > 13 && guard-- > 0) {
    size = Math.max(13, Math.floor(size * 0.88));
    el.style.fontSize = `${size}px`;
  }

  const pos = findScatterPosition(el);
  el.style.left = `${pos.x}px`;
  el.style.top  = `${pos.y}px`;

  requestAnimationFrame(() => el.classList.add('in'));
  setTimeout(() => fadeOutScatter(el), lifeMs);
}

function findScatterPosition(el) {
  const sw = elScatterLayer.clientWidth  || 320;
  const sh = elScatterLayer.clientHeight || 200;
  const ew = Math.min(el.offsetWidth,  sw - 16);
  const eh = Math.min(el.offsetHeight, sh - 16);
  const PAD = 10;
  const TOP_AVOID = 38; /* keep clear of the now-playing title */
  const rects = [...elScatterLayer.querySelectorAll('.ly-scatter-token')]
    .filter(t => t !== el)
    .map(t => ({ left: t.offsetLeft, top: t.offsetTop, right: t.offsetLeft + t.offsetWidth, bottom: t.offsetTop + t.offsetHeight }));

  for (let i = 0; i < 60; i++) {
    const x = randomInt(PAD, Math.max(PAD, sw - ew - PAD));
    const y = randomInt(TOP_AVOID, Math.max(TOP_AVOID, sh - eh - PAD));
    const r = { left: x, top: y, right: x + ew, bottom: y + eh };
    const hit = rects.some(o => !(
      r.right + SCATTER_GAP < o.left || r.left - SCATTER_GAP > o.right ||
      r.bottom + SCATTER_GAP < o.top || r.top - SCATTER_GAP > o.bottom
    ));
    if (!hit) return { x, y };
  }
  /* fallback: drop the oldest and place freely */
  const oldest = [...elScatterLayer.querySelectorAll('.ly-scatter-token')]
    .sort((a, b) => Number(a.dataset.born || 0) - Number(b.dataset.born || 0))[0];
  if (oldest && oldest !== el) fadeOutScatter(oldest);
  return {
    x: randomInt(PAD, Math.max(PAD, sw - ew - PAD)),
    y: randomInt(TOP_AVOID, Math.max(TOP_AVOID, sh - eh - PAD)),
  };
}

function fadeOutScatter(el) {
  if (!el || el.classList.contains('out')) return;
  el.classList.remove('in');
  el.classList.add('out');
  setTimeout(() => { if (el.parentNode) el.remove(); }, 650);
}

function clearStage() {
  ensureStageSlots();
  STAGE_SLOTS.forEach(s => {
    s.textContent = '';
    s.classList.remove('enter');
  });
  if (elScatterLayer) elScatterLayer.innerHTML = '';
  hideStageMessage();
  plainHistory = [];
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

/* ---- PWA: register service worker for offline app-shell ---- */
if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('sw.js')
      .catch(err => console.warn('Service worker registration failed:', err));
  });
}

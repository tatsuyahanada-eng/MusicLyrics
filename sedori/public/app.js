/* ─── 状態管理 ─────────────────────────────────────────────────────────────── */
const state = {
  scanning: false,
  codeReader: null,
  currentResult: null,
  history: JSON.parse(localStorage.getItem('sedoriHistory') || '[]'),
};

/* ─── DOM ──────────────────────────────────────────────────────────────────── */
const $  = (id) => document.getElementById(id);
const $$ = (sel) => document.querySelectorAll(sel);

/* ─── タブ切り替え ─────────────────────────────────────────────────────────── */
$$('.tab-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    const tab = btn.dataset.tab;
    $$('.tab-btn').forEach((b) => b.classList.remove('active'));
    $$('.tab-content').forEach((c) => c.classList.remove('active'));
    btn.classList.add('active');
    $(`tab-${tab}`).classList.add('active');
    if (tab === 'history') renderHistory();
    if (tab !== 'camera' && state.scanning) stopScan();
  });
});

/* ─── バーコードスキャン ────────────────────────────────────────────────────── */
async function startScan() {
  try {
    const hints = new Map();
    const formats = [
      ZXing.BarcodeFormat.EAN_13,
      ZXing.BarcodeFormat.EAN_8,
      ZXing.BarcodeFormat.ISBN,
      ZXing.BarcodeFormat.CODE_128,
      ZXing.BarcodeFormat.QR_CODE,
    ];
    hints.set(ZXing.DecodeHintType.POSSIBLE_FORMATS, formats);

    state.codeReader = new ZXing.BrowserMultiFormatReader(hints);
    const videoEl = $('video');

    $('startScanBtn').style.display = 'none';
    $('stopScanBtn').style.display = 'block';
    $('scanStatus').textContent = 'カメラを起動中...';
    state.scanning = true;

    await state.codeReader.decodeFromVideoDevice(null, videoEl, (result, err) => {
      if (result) {
        const code = result.getText();
        if (/^\d{8,14}$/.test(code)) {
          stopScan();
          vibrate();
          $('scanStatus').textContent = `✅ 読み取り成功: ${code}`;
          searchByCode(code);
        }
      }
    });

    $('scanStatus').textContent = 'スキャン中... バーコードを向けてください';
  } catch (err) {
    $('scanStatus').textContent = `カメラエラー: ${err.message}`;
    $('startScanBtn').style.display = 'block';
    $('stopScanBtn').style.display = 'none';
    state.scanning = false;
  }
}

function stopScan() {
  if (state.codeReader) {
    state.codeReader.reset();
    state.codeReader = null;
  }
  state.scanning = false;
  $('startScanBtn').style.display = 'block';
  $('stopScanBtn').style.display = 'none';
  const video = $('video');
  if (video.srcObject) {
    video.srcObject.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
  }
}

function vibrate() {
  if (navigator.vibrate) navigator.vibrate(80);
}

$('startScanBtn').addEventListener('click', startScan);
$('stopScanBtn').addEventListener('click', stopScan);

/* ─── 手動入力 ─────────────────────────────────────────────────────────────── */
$('searchBtn').addEventListener('click', () => {
  const code = $('janInput').value.trim();
  if (!/^\d{8,14}$/.test(code)) {
    alert('8〜14桁の数字を入力してください');
    return;
  }
  searchByCode(code);
});

$('janInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('searchBtn').click();
});

$$('.chip').forEach((chip) => {
  chip.addEventListener('click', () => {
    $('janInput').value = chip.dataset.code;
    $('searchBtn').click();
  });
});

/* ─── 検索処理 ─────────────────────────────────────────────────────────────── */
async function searchByCode(janCode) {
  showLoading(true);
  hideResult();

  try {
    const res = await fetch(`/api/search/${janCode}`);
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();

    state.currentResult = data;
    renderResult(data);
    addToHistory(data);
  } catch (err) {
    alert(`エラー: ${err.message}`);
  } finally {
    showLoading(false);
  }
}

/* ─── 結果表示 ─────────────────────────────────────────────────────────────── */
function renderResult(data) {
  const { janCode, productInfo, amazon } = data;
  const mainItem = amazon[0];
  const hasMock = amazon.some((a) => a.isMock);

  // 商品情報
  $('janDisplay').textContent = `JAN: ${janCode}`;

  const img = $('productImage');
  const imgUrl = productInfo?.imageUrl || mainItem?.imageUrl;
  if (imgUrl) {
    img.src = imgUrl;
    img.style.display = 'block';
  } else {
    img.style.display = 'none';
  }

  $('productTitle').textContent =
    productInfo?.name || mainItem?.title || '商品情報なし';
  $('productBrand').textContent = productInfo?.brand || mainItem?.brand || '';

  const cat = productInfo?.category || '';
  $('productCategory').textContent = categoryLabel(cat);
  $('productCategory').style.display = cat ? 'inline-block' : 'none';

  // Amazon価格
  const priceList = $('priceList');
  priceList.innerHTML = '';

  if (amazon.length > 0) {
    amazon.forEach((item) => {
      const el = document.createElement('div');
      el.className = 'price-item';
      el.innerHTML = `
        <div class="price-item-header">
          <span class="platform-name amazon">Amazon</span>
          ${item.isMock ? '<span class="mock-note">デモ</span>' : `<span class="offer-count">出品数: ${item.offerCount}件</span>`}
        </div>
        <div class="price-row">
          ${item.newPrice ? `<div class="price-entry"><div class="price-label">新品最安値</div><div class="price-value new-price">¥${fmt(item.newPrice)}</div></div>` : ''}
          ${item.usedPrice ? `<div class="price-entry"><div class="price-label">中古最安値</div><div class="price-value used-price">¥${fmt(item.usedPrice)}</div></div>` : ''}
        </div>
        <div style="margin-top:6px">
          <a href="${item.detailUrl}" target="_blank" style="font-size:0.75rem;color:var(--amazon)">Amazonで確認 →</a>
        </div>
      `;
      priceList.appendChild(el);

      // 販売価格を自動入力
      if (item.newPrice && !$('salePriceInput').value) {
        $('salePriceInput').value = item.newPrice;
      }
    });
  } else {
    priceList.innerHTML = '<p class="empty-msg">Amazon価格情報なし</p>';
  }

  // 外部リンク
  const searchName = productInfo?.name || mainItem?.title || janCode;
  $('mercariLink').href = data.mercariSearchUrl;
  $('yahooLink').href = data.yahooSearchUrl;

  // モックバナー
  $('mockBanner').style.display = hasMock ? 'block' : 'none';

  // 結果表示
  $('resultSection').style.display = 'block';
  $('resultSection').scrollIntoView({ behavior: 'smooth' });
}

function hideResult() {
  $('resultSection').style.display = 'none';
  $('profitResult').style.display = 'none';
  $('mockBanner').style.display = 'none';
}

function showLoading(show) {
  $('loadingCard').style.display = show ? 'flex' : 'none';
}

/* ─── 利益計算 ─────────────────────────────────────────────────────────────── */
$('platformSelect').addEventListener('change', () => {
  const isMercari = $('platformSelect').value === 'mercari';
  $('categoryGroup').style.display = isMercari ? 'none' : '';
  $('fbaSizeGroup').style.display = isMercari ? 'none' : '';
  $('shippingInput').parentElement.style.display = isMercari ? 'none' : '';
});

$('calcBtn').addEventListener('click', async () => {
  const buyPrice = $('buyPriceInput').value;
  const salePrice = $('salePriceInput').value;

  if (!buyPrice || !salePrice) {
    alert('仕入れ価格と販売価格を入力してください');
    return;
  }

  try {
    const res = await fetch('/api/calculate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        buyPrice,
        salePrice,
        platform: $('platformSelect').value,
        category: $('categorySelect').value,
        fbaSize: $('fbaSizeSelect').value,
        shippingCost: $('shippingInput').value || 0,
      }),
    });
    const data = await res.json();
    renderProfitResult(data);
  } catch (err) {
    alert('計算エラー: ' + err.message);
  }
});

function renderProfitResult(data) {
  const { profit, roi, margin, fees, buyPrice, salePrice, shippingCost, isProfit } = data;
  const platform = $('platformSelect').value;

  $('profitSummary').className = `profit-summary ${isProfit ? 'profit-pos' : 'profit-neg'}`;
  $('profitSummary').innerHTML = `
    <div class="profit-label">${isProfit ? '✅ 利益あり' : '❌ 赤字'}</div>
    <div class="profit-amount">${isProfit ? '+' : ''}¥${fmt(profit)}</div>
    <div class="profit-meta">
      <div class="profit-meta-item">ROI: <span>${roi}%</span></div>
      <div class="profit-meta-item">利益率: <span>${margin}%</span></div>
    </div>
  `;

  const feeDetails = $('feeDetails');
  feeDetails.innerHTML = '';

  const addRow = (label, amount, isTotal = false) => {
    const row = document.createElement('div');
    row.className = `fee-row${isTotal ? ' total' : ''}`;
    row.innerHTML = `<span>${label}</span><span>¥${fmt(amount)}</span>`;
    feeDetails.appendChild(row);
  };

  addRow('販売価格', salePrice);
  addRow('仕入れ価格', -buyPrice);

  if (platform === 'amazon') {
    addRow('Amazon紹介料', -fees.referralFee);
    addRow('FBA配送代行手数料', -fees.fbaFee);
    if (fees.closingFee) addRow('カテゴリ成約料', -fees.closingFee);
  } else {
    addRow('メルカリ販売手数料(10%)', -fees.sellingFee);
    addRow('決済手数料(2.5%)', -fees.paymentFee);
    addRow('配送料(概算)', -fees.shippingEstimate);
  }

  if (shippingCost > 0) addRow('送料(自己負担)', -shippingCost);
  addRow('合計手数料', -(fees.total), false);
  addRow(`利益: ${isProfit ? '✅' : '❌'}`, profit, true);

  $('profitResult').style.display = 'block';
  $('profitResult').scrollIntoView({ behavior: 'smooth' });
}

/* ─── 履歴 ──────────────────────────────────────────────────────────────────── */
function addToHistory(data) {
  const entry = {
    janCode: data.janCode,
    title: data.productInfo?.name || data.amazon[0]?.title || data.janCode,
    timestamp: Date.now(),
  };
  state.history = [entry, ...state.history.filter((h) => h.janCode !== entry.janCode)].slice(0, 30);
  localStorage.setItem('sedoriHistory', JSON.stringify(state.history));
}

function renderHistory() {
  const list = $('historyList');
  if (state.history.length === 0) {
    list.innerHTML = '<p class="empty-msg">まだ検索履歴がありません</p>';
    $('clearHistoryBtn').style.display = 'none';
    return;
  }
  $('clearHistoryBtn').style.display = 'inline-block';
  list.innerHTML = state.history.map((h) => `
    <div class="history-item" data-code="${h.janCode}">
      <div>
        <div class="title">${escHtml(h.title)}</div>
        <div class="jan">${h.janCode}</div>
      </div>
      <div class="time">${timeAgo(h.timestamp)}</div>
    </div>
  `).join('');

  list.querySelectorAll('.history-item').forEach((el) => {
    el.addEventListener('click', () => {
      // カメラタブに切り替えてから検索
      $$('.tab-btn')[1].click(); // 手入力タブ
      $('janInput').value = el.dataset.code;
      searchByCode(el.dataset.code);
    });
  });
}

$('clearHistoryBtn').addEventListener('click', () => {
  if (confirm('履歴を削除しますか？')) {
    state.history = [];
    localStorage.removeItem('sedoriHistory');
    renderHistory();
  }
});

/* ─── モーダル ──────────────────────────────────────────────────────────────── */
[$('setupLink'), $('setupLink')].forEach(() => {});
$('setupLink').addEventListener('click', (e) => { e.preventDefault(); openModal(); });
$('closeModal').addEventListener('click', closeModal);
$('modalOverlay').addEventListener('click', closeModal);
document.querySelector('.mock-banner a')?.addEventListener('click', (e) => {
  e.preventDefault();
  openModal();
});

function openModal() {
  $('setupModal').style.display = 'block';
  $('modalOverlay').style.display = 'block';
}
function closeModal() {
  $('setupModal').style.display = 'none';
  $('modalOverlay').style.display = 'none';
}

/* ─── ユーティリティ ────────────────────────────────────────────────────────── */
function fmt(n) {
  return Number(n).toLocaleString('ja-JP');
}

function escHtml(str) {
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function categoryLabel(cat) {
  const map = {
    books: '書籍', electronics: '家電', toys: 'おもちゃ',
    clothing: '衣類', home: 'ホーム', food: '食品・飲料',
  };
  return map[cat] || cat;
}

function timeAgo(ts) {
  const diff = Date.now() - ts;
  if (diff < 60000) return 'たった今';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分前`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}時間前`;
  return `${Math.floor(diff / 86400000)}日前`;
}

/* ─── 初期化 ─────────────────────────────────────────────────────────────────── */
window.addEventListener('beforeunload', stopScan);

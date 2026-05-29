require('dotenv').config();
const express = require('express');
const axios = require('axios');
const cors = require('cors');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const PORT = process.env.PORT || 3000;

// ─── Amazon PA-API ────────────────────────────────────────────────────────────
async function searchAmazon(janCode) {
  const hasCredentials =
    process.env.AMAZON_ACCESS_KEY &&
    process.env.AMAZON_ACCESS_KEY !== 'your_access_key_here';

  if (!hasCredentials) {
    return getMockAmazonData(janCode);
  }

  try {
    const { DefaultApi, SearchItemsRequest, PartnerType, Condition } = require('amazon-paapi');
    const defaultClient = DefaultApi.ApiClient.instance;
    defaultClient.accessKey = process.env.AMAZON_ACCESS_KEY;
    defaultClient.secretKey = process.env.AMAZON_SECRET_KEY;
    defaultClient.host = process.env.AMAZON_HOST || 'webservices.amazon.co.jp';
    defaultClient.region = process.env.AMAZON_REGION || 'us-west-2';

    const api = new DefaultApi();
    const searchItemsRequest = new SearchItemsRequest();
    searchItemsRequest.PartnerTag = process.env.AMAZON_PARTNER_TAG;
    searchItemsRequest.PartnerType = PartnerType.Associates;
    searchItemsRequest.Keywords = janCode;
    searchItemsRequest.SearchIndex = 'All';
    searchItemsRequest.ItemCount = 3;
    searchItemsRequest.Condition = Condition.Any;
    searchItemsRequest.Resources = [
      'Images.Primary.Medium',
      'ItemInfo.Title',
      'ItemInfo.ByLineInfo',
      'Offers.Listings.Price',
      'Offers.Listings.Condition',
      'Offers.Summaries.LowestPrice',
      'Offers.Summaries.HighestPrice',
      'Offers.Summaries.OfferCount',
      'BrowseNodeInfo.BrowseNodes',
      'SearchRefinements',
    ];

    const response = await api.searchItems(searchItemsRequest);
    const items = response.SearchResult?.Items || [];

    return items.map((item) => {
      const listing = item.Offers?.Listings?.[0];
      const summary = item.Offers?.Summaries?.[0];
      return {
        asin: item.ASIN,
        title: item.ItemInfo?.Title?.DisplayValue || '不明',
        brand: item.ItemInfo?.ByLineInfo?.Brand?.DisplayValue || '',
        imageUrl: item.Images?.Primary?.Medium?.URL || '',
        newPrice: summary?.LowestPrice?.Amount || null,
        usedPrice: item.Offers?.Summaries?.find((s) => s.Condition?.Value === 'Used')?.LowestPrice?.Amount || null,
        offerCount: summary?.OfferCount || 0,
        detailUrl: `https://www.amazon.co.jp/dp/${item.ASIN}`,
        source: 'amazon',
      };
    });
  } catch (err) {
    console.error('Amazon API error:', err.message);
    return getMockAmazonData(janCode);
  }
}

function getMockAmazonData(janCode) {
  // デモ用モックデータ（PA-API未設定時）
  const mockItems = [
    {
      asin: 'B0DEMO1234',
      title: `[デモ] JAN: ${janCode} - 商品名がここに表示されます`,
      brand: 'ブランド名',
      imageUrl: '',
      newPrice: 3980,
      usedPrice: 1500,
      offerCount: 12,
      detailUrl: `https://www.amazon.co.jp/s?k=${janCode}`,
      source: 'amazon',
      isMock: true,
    },
  ];
  return mockItems;
}

// ─── Open Food Facts / Open Library (無料バーコードDB) ─────────────────────────
async function lookupBarcode(janCode) {
  try {
    // Open Food Facts (食品・日用品)
    const foodRes = await axios.get(
      `https://world.openfoodfacts.org/api/v0/product/${janCode}.json`,
      { timeout: 5000 }
    );
    if (foodRes.data.status === 1) {
      const p = foodRes.data.product;
      return {
        name: p.product_name_ja || p.product_name || null,
        brand: p.brands || null,
        imageUrl: p.image_url || null,
        category: p.categories_tags?.[0]?.replace('en:', '') || null,
        source: 'openfoodfacts',
      };
    }
  } catch (_) {}

  try {
    // Open Library (書籍 ISBN)
    const isbn = janCode.startsWith('978') || janCode.startsWith('979') ? janCode : null;
    if (isbn) {
      const bookRes = await axios.get(
        `https://openlibrary.org/api/books?bibkeys=ISBN:${isbn}&format=json&jscmd=data`,
        { timeout: 5000 }
      );
      const key = `ISBN:${isbn}`;
      if (bookRes.data[key]) {
        const book = bookRes.data[key];
        return {
          name: book.title || null,
          brand: book.authors?.[0]?.name || null,
          imageUrl: book.cover?.medium || null,
          category: 'books',
          source: 'openlibrary',
        };
      }
    }
  } catch (_) {}

  return null;
}

// ─── メルカリ (公式APIなし → スクレイピング代替として検索URLを返す) ──────────────
function getMercariSearchUrl(query) {
  return `https://jp.mercari.com/search?keyword=${encodeURIComponent(query)}&status=on_sale`;
}

// ─── カテゴリ別Amazon手数料テーブル ────────────────────────────────────────────
const AMAZON_FEE_TABLE = {
  books: { referral: 0.15, fbaSmall: 210, fbaMedium: 310 },
  electronics: { referral: 0.08, fbaSmall: 270, fbaMedium: 400 },
  toys: { referral: 0.10, fbaSmall: 230, fbaMedium: 350 },
  clothing: { referral: 0.15, fbaSmall: 280, fbaMedium: 420 },
  home: { referral: 0.10, fbaSmall: 250, fbaMedium: 380 },
  default: { referral: 0.10, fbaSmall: 260, fbaMedium: 390 },
};

function calcAmazonFees(salePrice, category = 'default', fbaSize = 'small') {
  const fees = AMAZON_FEE_TABLE[category] || AMAZON_FEE_TABLE.default;
  const referralFee = Math.ceil(salePrice * fees.referral);
  const fbaFee = fbaSize === 'small' ? fees.fbaSmall : fees.fbaMedium;
  const closingFee = category === 'books' ? 80 : 0;
  const total = referralFee + fbaFee + closingFee;
  return { referralFee, fbaFee, closingFee, total };
}

function calcMercariFeees(salePrice) {
  const sellingFee = Math.ceil(salePrice * 0.10);
  const paymentFee = Math.ceil(salePrice * 0.025);
  const shippingEstimate = 210; // ネコポス想定
  const total = sellingFee + paymentFee + shippingEstimate;
  return { sellingFee, paymentFee, shippingEstimate, total };
}

// ─── API エンドポイント ────────────────────────────────────────────────────────

// バーコード検索
app.get('/api/search/:janCode', async (req, res) => {
  const { janCode } = req.params;
  if (!/^\d{8,14}$/.test(janCode)) {
    return res.status(400).json({ error: '無効なJANコードです' });
  }

  try {
    const [barcodeInfo, amazonItems] = await Promise.all([
      lookupBarcode(janCode),
      searchAmazon(janCode),
    ]);

    const productName = barcodeInfo?.name || amazonItems[0]?.title || janCode;

    res.json({
      janCode,
      productInfo: barcodeInfo,
      amazon: amazonItems,
      mercariSearchUrl: getMercariSearchUrl(productName),
      yahooSearchUrl: `https://auctions.yahoo.co.jp/search/search?p=${encodeURIComponent(productName)}`,
    });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: '検索中にエラーが発生しました' });
  }
});

// 利益計算
app.post('/api/calculate', (req, res) => {
  const { buyPrice, salePrice, platform, category, fbaSize, shippingCost } = req.body;

  if (!buyPrice || !salePrice) {
    return res.status(400).json({ error: '仕入れ価格と販売価格は必須です' });
  }

  const buy = Number(buyPrice);
  const sale = Number(salePrice);
  const shipping = Number(shippingCost || 0);

  let fees, profit, roi;

  if (platform === 'amazon') {
    fees = calcAmazonFees(sale, category || 'default', fbaSize || 'small');
    profit = sale - buy - fees.total - shipping;
  } else {
    fees = calcMercariFeees(sale);
    profit = sale - buy - fees.total - shipping;
  }

  roi = buy > 0 ? Math.round((profit / buy) * 100) : 0;
  const margin = sale > 0 ? Math.round((profit / sale) * 100) : 0;

  res.json({
    buyPrice: buy,
    salePrice: sale,
    fees,
    shippingCost: shipping,
    profit,
    roi,
    margin,
    isProfit: profit > 0,
  });
});

app.listen(PORT, () => {
  console.log(`せどりツール起動中: http://localhost:${PORT}`);
  if (!process.env.AMAZON_ACCESS_KEY || process.env.AMAZON_ACCESS_KEY === 'your_access_key_here') {
    console.log('⚠️  Amazon PA-API未設定 → デモモードで動作します');
    console.log('   .env.example を参考に .env を設定してください');
  }
});

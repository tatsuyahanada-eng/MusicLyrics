<?php
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') { http_response_code(204); exit; }

// ─── ルーティング ──────────────────────────────────────────────────────────────
$action = $_GET['action'] ?? '';

if ($action === 'search' && isset($_GET['jan'])) {
    handleSearch($_GET['jan']);
} elseif ($action === 'calculate') {
    $body = json_decode(file_get_contents('php://input'), true) ?? [];
    handleCalculate($body);
} else {
    jsonError('不正なリクエストです', 400);
}

// ─── バーコード検索 ────────────────────────────────────────────────────────────
function handleSearch($janCode) {
    if (!preg_match('/^\d{8,14}$/', $janCode)) {
        jsonError('無効なJANコードです', 400);
    }

    $productInfo = lookupBarcode($janCode);
    $amazon      = getMockAmazonData($janCode); // PA-API未設定時はモック

    $productName = $productInfo['name'] ?? ($amazon[0]['title'] ?? $janCode);

    echo json_encode([
        'janCode'        => $janCode,
        'productInfo'    => $productInfo,
        'amazon'         => $amazon,
        'mercariSearchUrl' => 'https://jp.mercari.com/search?keyword=' . urlencode($productName) . '&status=on_sale',
        'yahooSearchUrl'   => 'https://auctions.yahoo.co.jp/search/search?p=' . urlencode($productName),
    ], JSON_UNESCAPED_UNICODE);
}

// ─── Open Food Facts & Open Library ───────────────────────────────────────────
function lookupBarcode($janCode) {
    // 食品・日用品
    $url  = "https://world.openfoodfacts.org/api/v0/product/{$janCode}.json";
    $data = httpGet($url);
    if ($data && ($data['status'] ?? 0) === 1) {
        $p = $data['product'];
        return [
            'name'     => $p['product_name_ja'] ?? $p['product_name'] ?? null,
            'brand'    => $p['brands'] ?? null,
            'imageUrl' => $p['image_url'] ?? null,
            'category' => isset($p['categories_tags'][0])
                          ? str_replace('en:', '', $p['categories_tags'][0]) : null,
            'source'   => 'openfoodfacts',
        ];
    }

    // 書籍 (ISBN)
    if (str_starts_with($janCode, '978') || str_starts_with($janCode, '979')) {
        $url  = "https://openlibrary.org/api/books?bibkeys=ISBN:{$janCode}&format=json&jscmd=data";
        $data = httpGet($url);
        $key  = "ISBN:{$janCode}";
        if ($data && isset($data[$key])) {
            $book = $data[$key];
            return [
                'name'     => $book['title'] ?? null,
                'brand'    => $book['authors'][0]['name'] ?? null,
                'imageUrl' => $book['cover']['medium'] ?? null,
                'category' => 'books',
                'source'   => 'openlibrary',
            ];
        }
    }

    return null;
}

function httpGet($url) {
    $ctx = stream_context_create([
        'http' => [
            'timeout'        => 6,
            'ignore_errors'  => true,
            'user_agent'     => 'SedoriChecker/1.0',
        ],
        'ssl'  => ['verify_peer' => false],
    ]);
    $body = @file_get_contents($url, false, $ctx);
    return $body ? json_decode($body, true) : null;
}

// ─── Amazon モックデータ（PA-API未設定時） ─────────────────────────────────────
function getMockAmazonData($janCode) {
    return [[
        'asin'       => 'B0DEMO1234',
        'title'      => "[デモ] JAN: {$janCode} - 商品名がここに表示されます",
        'brand'      => 'ブランド名',
        'imageUrl'   => '',
        'newPrice'   => 3980,
        'usedPrice'  => 1500,
        'offerCount' => 12,
        'detailUrl'  => "https://www.amazon.co.jp/s?k={$janCode}",
        'source'     => 'amazon',
        'isMock'     => true,
    ]];
}

// ─── 利益計算 ──────────────────────────────────────────────────────────────────
function handleCalculate($body) {
    $buyPrice    = (float)($body['buyPrice']    ?? 0);
    $salePrice   = (float)($body['salePrice']   ?? 0);
    $platform    = $body['platform']   ?? 'amazon';
    $category    = $body['category']   ?? 'default';
    $fbaSize     = $body['fbaSize']    ?? 'small';
    $shippingCost = isset($body['shippingCost']) && $body['shippingCost'] !== ''
                    ? (float)$body['shippingCost'] : null;

    if ($buyPrice <= 0 || $salePrice <= 0) {
        jsonError('仕入れ価格と販売価格は必須です', 400);
    }

    if ($platform === 'amazon') {
        $fees   = calcAmazonFees($salePrice, $category, $fbaSize);
        $profit = $salePrice - $buyPrice - $fees['total'] - ($shippingCost ?? 0);
    } else {
        $fees   = calcMercariFees($salePrice, $shippingCost);
        $profit = $salePrice - $buyPrice - $fees['total'];
    }

    $roi    = $buyPrice > 0  ? round($profit / $buyPrice  * 100) : 0;
    $margin = $salePrice > 0 ? round($profit / $salePrice * 100) : 0;

    echo json_encode([
        'buyPrice'     => $buyPrice,
        'salePrice'    => $salePrice,
        'fees'         => $fees,
        'shippingCost' => $shippingCost ?? 0,
        'profit'       => $profit,
        'roi'          => $roi,
        'margin'       => $margin,
        'isProfit'     => $profit > 0,
    ], JSON_UNESCAPED_UNICODE);
}

// ─── Amazon手数料テーブル ──────────────────────────────────────────────────────
function calcAmazonFees($salePrice, $category, $fbaSize) {
    $table = [
        'books'       => ['referral' => 0.15, 'fbaSmall' => 210, 'fbaMedium' => 310],
        'electronics' => ['referral' => 0.08, 'fbaSmall' => 270, 'fbaMedium' => 400],
        'toys'        => ['referral' => 0.10, 'fbaSmall' => 230, 'fbaMedium' => 350],
        'clothing'    => ['referral' => 0.15, 'fbaSmall' => 280, 'fbaMedium' => 420],
        'home'        => ['referral' => 0.10, 'fbaSmall' => 250, 'fbaMedium' => 380],
        'default'     => ['referral' => 0.10, 'fbaSmall' => 260, 'fbaMedium' => 390],
    ];
    $f = $table[$category] ?? $table['default'];
    $referralFee = (int)ceil($salePrice * $f['referral']);
    $fbaFee      = $fbaSize === 'small' ? $f['fbaSmall'] : $f['fbaMedium'];
    $closingFee  = $category === 'books' ? 80 : 0;
    return [
        'referralFee' => $referralFee,
        'fbaFee'      => $fbaFee,
        'closingFee'  => $closingFee,
        'total'       => $referralFee + $fbaFee + $closingFee,
    ];
}

// ─── メルカリ手数料 ────────────────────────────────────────────────────────────
function calcMercariFees($salePrice, $shippingCost) {
    $sellingFee       = (int)ceil($salePrice * 0.10);
    $paymentFee       = (int)ceil($salePrice * 0.025);
    $shippingEstimate = $shippingCost !== null ? (int)$shippingCost : 210;
    return [
        'sellingFee'       => $sellingFee,
        'paymentFee'       => $paymentFee,
        'shippingEstimate' => $shippingEstimate,
        'total'            => $sellingFee + $paymentFee + $shippingEstimate,
    ];
}

// ─── エラーレスポンス ──────────────────────────────────────────────────────────
function jsonError($msg, $code = 400) {
    http_response_code($code);
    echo json_encode(['error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

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
    $productName = $productInfo['name'] ?? null;
    $amazon      = getMockAmazonData($janCode, $productName); // PA-API未設定時はモック
    $productName = $productName ?? $janCode;

    echo json_encode([
        'janCode'        => $janCode,
        'productInfo'    => $productInfo,
        'amazon'         => $amazon,
        'mercariSearchUrl' => 'https://jp.mercari.com/search?keyword=' . urlencode($productName) . '&status=on_sale',
        'yahooSearchUrl'   => 'https://auctions.yahoo.co.jp/search/search?p=' . urlencode($productName),
    ], JSON_UNESCAPED_UNICODE);
}

// ─── 商品情報検索（複数DBを順番に試す） ──────────────────────────────────────
function lookupBarcode($janCode) {
    $isIsbn = (substr($janCode, 0, 3) === '978' || substr($janCode, 0, 3) === '979');

    // 書籍・マンガは書籍DB優先
    if ($isIsbn) {
        $result = lookupGoogleBooks($janCode)
               ?? lookupNdl($janCode);
        if ($result) return $result;
    }

    // 食品・日用品: Open Food Facts (日本語対応)
    $result = lookupOpenFoodFacts($janCode);
    if ($result) return $result;

    // 書籍でISBN非対応フォーマットの場合もGoogle Booksで試す
    if (!$isIsbn) {
        $result = lookupGoogleBooks($janCode);
        if ($result) return $result;
    }

    return null;
}

// Google Books API（無料・APIキー不要・日本語書籍に強い）
function lookupGoogleBooks($isbn) {
    $url  = "https://www.googleapis.com/books/v1/volumes?q=isbn:{$isbn}&maxResults=1";
    $data = httpGet($url);
    if (!$data || ($data['totalItems'] ?? 0) === 0) return null;

    $info = $data['items'][0]['volumeInfo'] ?? [];
    $title = $info['title'] ?? null;
    if (!$title) return null;

    // サブタイトルがあれば結合
    if (!empty($info['subtitle'])) {
        $title .= ' ' . $info['subtitle'];
    }

    $imageLinks = $info['imageLinks'] ?? [];
    $imageUrl   = $imageLinks['thumbnail'] ?? $imageLinks['smallThumbnail'] ?? null;
    // httpsに統一
    if ($imageUrl) $imageUrl = str_replace('http://', 'https://', $imageUrl);

    return [
        'name'     => $title,
        'brand'    => implode(', ', $info['authors'] ?? []) ?: null,
        'imageUrl' => $imageUrl,
        'category' => 'books',
        'source'   => 'googlebooks',
    ];
}

// 国立国会図書館サーチAPI（日本語書籍・マンガ・雑誌に強い）
function lookupNdl($isbn) {
    $url  = "https://iss.ndl.go.jp/api/opensearch?isbn={$isbn}&cnt=1";
    $xml  = httpGetRaw($url);
    if (!$xml) return null;

    // libxml エラーを抑制しつつパース
    libxml_use_internal_errors(true);
    $doc = simplexml_load_string($xml);
    libxml_clear_errors();
    if (!$doc) return null;

    $ns   = $doc->getNamespaces(true);
    $ch   = $doc->channel ?? null;
    if (!$ch) return null;

    $item = $ch->item ?? null;
    if (!$item) return null;

    $dcNs    = $ns['dc'] ?? 'http://purl.org/dc/elements/1.1/';
    $dcterms = $ns['dcterms'] ?? 'http://purl.org/dc/terms/';

    $title    = (string)($item->title ?? '');
    $creator  = (string)($item->children($dcNs)->creator ?? '');
    $imageUrl = null;

    // サムネイル取得（カバー画像）
    foreach ($item->children($ns['media'] ?? '') as $med) {
        $imageUrl = (string)($med['url'] ?? '');
        break;
    }

    if (!$title) return null;
    return [
        'name'     => $title,
        'brand'    => $creator ?: null,
        'imageUrl' => $imageUrl ?: null,
        'category' => 'books',
        'source'   => 'ndl',
    ];
}

// Open Food Facts（食品・飲料・日用品）
function lookupOpenFoodFacts($janCode) {
    $url  = "https://world.openfoodfacts.org/api/v0/product/{$janCode}.json";
    $data = httpGet($url);
    if (!$data || ($data['status'] ?? 0) !== 1) return null;

    $p    = $data['product'];
    // 日本語名 → 英語名の順で取得
    $name = $p['product_name_ja'] ?? $p['product_name'] ?? null;
    if (!$name) return null;

    return [
        'name'     => $name,
        'brand'    => $p['brands'] ?? null,
        'imageUrl' => $p['image_url'] ?? null,
        'category' => isset($p['categories_tags'][0])
                      ? str_replace('en:', '', $p['categories_tags'][0]) : null,
        'source'   => 'openfoodfacts',
    ];
}

function httpGet($url) {
    $body = httpGetRaw($url);
    return $body ? json_decode($body, true) : null;
}

function httpGetRaw($url) {
    $ctx = stream_context_create([
        'http' => [
            'timeout'       => 6,
            'ignore_errors' => true,
            'user_agent'    => 'SedoriChecker/1.0',
        ],
        'ssl' => ['verify_peer' => false],
    ]);
    $body = @file_get_contents($url, false, $ctx);
    return $body ?: null;
}

// ─── Amazon モックデータ（PA-API未設定時） ─────────────────────────────────────
function getMockAmazonData($janCode, $productName = null) {
    $title = $productName
        ? "【Amazon価格はデモ】{$productName}"
        : "[デモ] JAN: {$janCode}";
    return [[
        'asin'       => 'B0DEMO1234',
        'title'      => $title,
        'brand'      => '',
        'imageUrl'   => '',
        'newPrice'   => null,
        'usedPrice'  => null,
        'offerCount' => 0,
        'detailUrl'  => "https://www.amazon.co.jp/s?k=" . urlencode($productName ?? $janCode),
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

import 'package:flutter/material.dart';

import '../domain/entities/signal.dart';

const kBrand = Color(0xFF3A5BD9);

// 買い=赤(Ask)、売り=青(Bid)。赤×青は色覚多様性でも区別しやすい配色。
const kBuy = Color(0xFFE53935); // 赤 = 買い (Ask)
const kSell = Color(0xFF1565C0); // 青 = 売り (Bid)
const kNeutral = Color(0xFF7E8AA2);

/// トレンドに対応する色（上昇=赤・下降=青・レンジ=グレー、日本式かつ色覚配慮）。
Color trendColor(Trend t) {
  switch (t) {
    case Trend.up:
      return kBuy; // 上昇 → 赤
    case Trend.down:
      return kSell; // 下降 → 青
    case Trend.range:
      return kNeutral;
  }
}

/// 売買方向に対応する色。買い=赤、売り=青。
Color sideColor(Side s) => s == Side.buy ? kBuy : kSell;

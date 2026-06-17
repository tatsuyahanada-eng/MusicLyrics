import 'package:flutter/material.dart';

import '../domain/entities/signal.dart';

const kBrand = Color(0xFF3A5BD9);
const kUp = Color(0xFF1B9E5A);
const kDown = Color(0xFFE53935);
const kNeutral = Color(0xFF7E8AA2);

/// トレンドに対応する色。
Color trendColor(Trend t) {
  switch (t) {
    case Trend.up:
      return kUp;
    case Trend.down:
      return kDown;
    case Trend.range:
      return kNeutral;
  }
}

/// 売買方向に対応する色。
Color sideColor(Side s) => s == Side.buy ? kUp : kDown;

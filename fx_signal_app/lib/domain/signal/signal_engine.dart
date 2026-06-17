import '../entities/candle.dart';
import '../entities/signal.dart';
import '../indicators/indicators.dart';

/// 指標パラメータ。
class IndicatorConfig {
  final int smaShort;
  final int smaLong;
  final int rsiPeriod;
  final double rsiLow;
  final double rsiHigh;

  const IndicatorConfig({
    this.smaShort = 9,
    this.smaLong = 21,
    this.rsiPeriod = 14,
    this.rsiLow = 30,
    this.rsiHigh = 70,
  });

  Map<String, dynamic> toJson() => {
        'smaShort': smaShort,
        'smaLong': smaLong,
        'rsiPeriod': rsiPeriod,
        'rsiLow': rsiLow,
        'rsiHigh': rsiHigh,
      };

  factory IndicatorConfig.fromJson(Map<String, dynamic> json) =>
      IndicatorConfig(
        smaShort: (json['smaShort'] as num?)?.toInt() ?? 9,
        smaLong: (json['smaLong'] as num?)?.toInt() ?? 21,
        rsiPeriod: (json['rsiPeriod'] as num?)?.toInt() ?? 14,
        rsiLow: (json['rsiLow'] as num?)?.toDouble() ?? 30,
        rsiHigh: (json['rsiHigh'] as num?)?.toDouble() ?? 70,
      );
}

/// 1ペア分の評価結果（画面表示にも使う）。
class Evaluation {
  final Trend trend;
  final double? lastClose;
  final double? smaShort;
  final double? smaLong;
  final double? rsi;
  final double? macdHist;
  final Signal? signal; // null ならシグナルなし

  const Evaluation({
    required this.trend,
    this.lastClose,
    this.smaShort,
    this.smaLong,
    this.rsi,
    this.macdHist,
    this.signal,
  });
}

/// 複数指標の合議で売買シグナルを生成するエンジン。
class SignalEngine {
  final IndicatorConfig config;

  const SignalEngine({this.config = const IndicatorConfig()});

  Evaluation evaluate(String pair, List<Candle> candles) {
    if (candles.length < config.smaLong + 2) {
      return const Evaluation(trend: Trend.range);
    }
    final values = closes(candles);
    final smaS = Indicators.sma(values, config.smaShort);
    final smaL = Indicators.sma(values, config.smaLong);
    final rsi = Indicators.rsi(values, config.rsiPeriod);
    final macd = Indicators.macd(values);

    final last = candles.last;
    final i = values.length - 1;
    final trend = _trend(values[i], smaS[i], smaL[i]);

    // 状態量（その足時点の値）。
    final macdLine = macd.line[i];
    final macdSig = macd.signal[i];
    final rsiNow = rsi[i];
    final macdBull = macdLine != null && macdSig != null && macdLine > macdSig;
    final macdBear = macdLine != null && macdSig != null && macdLine < macdSig;
    final priceAboveLong = smaL[i] != null && values[i] > smaL[i]!;
    final priceBelowLong = smaL[i] != null && values[i] < smaL[i]!;

    // イベント（その足で発生した転換）。1つ以上ないとシグナルにしない。
    final goldenCross = crossedUp(smaS, smaL);
    final macdCrossUp = crossedUp(macd.line, macd.signal);
    final deadCross = crossedDown(smaS, smaL);
    final macdCrossDown = crossedDown(macd.line, macd.signal);

    final buyReasons = <String>[];
    if (goldenCross) buyReasons.add('ゴールデンクロス');
    if (macdCrossUp) buyReasons.add('MACD上抜け');
    if (!macdCrossUp && macdBull) buyReasons.add('MACD強気');
    if (priceAboveLong) buyReasons.add('価格が長期線上');
    if (rsiNow != null && rsiNow > 50) buyReasons.add('RSI>50');

    final sellReasons = <String>[];
    if (deadCross) sellReasons.add('デッドクロス');
    if (macdCrossDown) sellReasons.add('MACD下抜け');
    if (!macdCrossDown && macdBear) sellReasons.add('MACD弱気');
    if (priceBelowLong) sellReasons.add('価格が長期線下');
    if (rsiNow != null && rsiNow < 50) sellReasons.add('RSI<50');

    final hasBuyEvent = goldenCross || macdCrossUp;
    final hasSellEvent = deadCross || macdCrossDown;
    // 強度は最大3（一致した根拠数を3で頭打ち）。
    final buyStrength = buyReasons.length > 3 ? 3 : buyReasons.length;
    final sellStrength = sellReasons.length > 3 ? 3 : sellReasons.length;

    Signal? signal;
    if (hasBuyEvent && buyReasons.length >= 2 && buyStrength >= sellStrength) {
      signal = Signal(
        pair: pair,
        side: Side.buy,
        strength: buyStrength,
        reasons: buyReasons.take(3).toList(),
        price: last.close,
        time: last.datetime,
      );
    } else if (hasSellEvent && sellReasons.length >= 2) {
      signal = Signal(
        pair: pair,
        side: Side.sell,
        strength: sellStrength,
        reasons: sellReasons.take(3).toList(),
        price: last.close,
        time: last.datetime,
      );
    }

    return Evaluation(
      trend: trend,
      lastClose: values[i],
      smaShort: smaS[i],
      smaLong: smaL[i],
      rsi: rsi[i],
      macdHist: macd.histogram[i],
      signal: signal,
    );
  }

  Trend _trend(double close, double? smaShort, double? smaLong) {
    if (smaShort == null || smaLong == null) return Trend.range;
    if (close > smaLong && smaShort > smaLong) return Trend.up;
    if (close < smaLong && smaShort < smaLong) return Trend.down;
    return Trend.range;
  }

  /// 直前シグナルと同じ方向・同じ足なら重複とみなす。
  static bool isDuplicate(Signal? last, Signal current) {
    if (last == null) return false;
    return last.side == current.side &&
        last.time.isAtSameMomentAs(current.time);
  }
}

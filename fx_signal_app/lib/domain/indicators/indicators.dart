import '../entities/candle.dart';

/// テクニカル指標の計算ロジック（端末内で完結・外部依存なし）。
///
/// 入力の価格系列は「古い → 新しい」の順を前提とする。
/// 各系列の戻り値も同じ並びで、計算不能な先頭部分は null で埋める。
class Indicators {
  /// 単純移動平均 (SMA)。
  static List<double?> sma(List<double> values, int period) {
    final out = List<double?>.filled(values.length, null);
    if (period <= 0) return out;
    double sum = 0;
    for (var i = 0; i < values.length; i++) {
      sum += values[i];
      if (i >= period) sum -= values[i - period];
      if (i >= period - 1) out[i] = sum / period;
    }
    return out;
  }

  /// 指数移動平均 (EMA)。最初の値は同期間のSMAでシードする。
  static List<double?> ema(List<double> values, int period) {
    final out = List<double?>.filled(values.length, null);
    if (period <= 0 || values.length < period) return out;
    final k = 2 / (period + 1);
    double seed = 0;
    for (var i = 0; i < period; i++) {
      seed += values[i];
    }
    double prev = seed / period;
    out[period - 1] = prev;
    for (var i = period; i < values.length; i++) {
      prev = (values[i] - prev) * k + prev;
      out[i] = prev;
    }
    return out;
  }

  /// RSI (Wilderの平滑化)。0〜100。
  static List<double?> rsi(List<double> values, int period) {
    final out = List<double?>.filled(values.length, null);
    if (values.length <= period) return out;

    double gainSum = 0, lossSum = 0;
    for (var i = 1; i <= period; i++) {
      final change = values[i] - values[i - 1];
      if (change >= 0) {
        gainSum += change;
      } else {
        lossSum -= change;
      }
    }
    double avgGain = gainSum / period;
    double avgLoss = lossSum / period;
    out[period] = _rsiFrom(avgGain, avgLoss);

    for (var i = period + 1; i < values.length; i++) {
      final change = values[i] - values[i - 1];
      final gain = change > 0 ? change : 0.0;
      final loss = change < 0 ? -change : 0.0;
      avgGain = (avgGain * (period - 1) + gain) / period;
      avgLoss = (avgLoss * (period - 1) + loss) / period;
      out[i] = _rsiFrom(avgGain, avgLoss);
    }
    return out;
  }

  static double _rsiFrom(double avgGain, double avgLoss) {
    if (avgLoss == 0) return 100;
    final rs = avgGain / avgLoss;
    return 100 - (100 / (1 + rs));
  }

  /// MACD。line = EMA(fast) - EMA(slow)、signal = EMA(line, signalPeriod)、
  /// hist = line - signal。
  static MacdResult macd(
    List<double> values, {
    int fast = 12,
    int slow = 26,
    int signal = 9,
  }) {
    final emaFast = ema(values, fast);
    final emaSlow = ema(values, slow);
    final line = List<double?>.filled(values.length, null);
    for (var i = 0; i < values.length; i++) {
      if (emaFast[i] != null && emaSlow[i] != null) {
        line[i] = emaFast[i]! - emaSlow[i]!;
      }
    }
    // signal は line の連続区間に対する EMA。
    final start = line.indexWhere((e) => e != null);
    final signalLine = List<double?>.filled(values.length, null);
    final hist = List<double?>.filled(values.length, null);
    if (start != -1) {
      final dense = <double>[];
      for (var i = start; i < values.length; i++) {
        dense.add(line[i]!);
      }
      final sig = ema(dense, signal);
      for (var i = 0; i < dense.length; i++) {
        final idx = start + i;
        signalLine[idx] = sig[i];
        if (sig[i] != null) hist[idx] = line[idx]! - sig[i]!;
      }
    }
    return MacdResult(line: line, signal: signalLine, histogram: hist);
  }
}

class MacdResult {
  final List<double?> line;
  final List<double?> signal;
  final List<double?> histogram;

  const MacdResult({
    required this.line,
    required this.signal,
    required this.histogram,
  });
}

/// 2本の系列について、直近の足で a が b を「下から上に」抜けたか。
bool crossedUp(List<double?> a, List<double?> b) {
  final n = a.length;
  if (n < 2) return false;
  final a0 = a[n - 2], a1 = a[n - 1], b0 = b[n - 2], b1 = b[n - 1];
  if (a0 == null || a1 == null || b0 == null || b1 == null) return false;
  return a0 <= b0 && a1 > b1;
}

/// 直近の足で a が b を「上から下に」抜けたか。
bool crossedDown(List<double?> a, List<double?> b) {
  final n = a.length;
  if (n < 2) return false;
  final a0 = a[n - 2], a1 = a[n - 1], b0 = b[n - 2], b1 = b[n - 1];
  if (a0 == null || a1 == null || b0 == null || b1 == null) return false;
  return a0 >= b0 && a1 < b1;
}

/// 系列が一定しきい値を直近の足で上抜け／下抜けしたか。
bool crossedAbove(List<double?> series, double level) {
  final n = series.length;
  if (n < 2) return false;
  final s0 = series[n - 2], s1 = series[n - 1];
  if (s0 == null || s1 == null) return false;
  return s0 <= level && s1 > level;
}

bool crossedBelow(List<double?> series, double level) {
  final n = series.length;
  if (n < 2) return false;
  final s0 = series[n - 2], s1 = series[n - 1];
  if (s0 == null || s1 == null) return false;
  return s0 >= level && s1 < level;
}

/// Candle リストから終値だけを取り出すヘルパ。
List<double> closes(List<Candle> candles) =>
    candles.map((c) => c.close).toList(growable: false);

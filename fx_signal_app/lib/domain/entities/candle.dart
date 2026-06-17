/// ローソク足1本分の価格データ。
class Candle {
  final DateTime datetime;
  final double open;
  final double high;
  final double low;
  final double close;

  const Candle({
    required this.datetime,
    required this.open,
    required this.high,
    required this.low,
    required this.close,
  });

  /// Twelve Data の time_series 1要素からの変換。
  factory Candle.fromTwelveData(Map<String, dynamic> json) {
    return Candle(
      datetime: DateTime.parse(json['datetime'] as String),
      open: double.parse(json['open'] as String),
      high: double.parse(json['high'] as String),
      low: double.parse(json['low'] as String),
      close: double.parse(json['close'] as String),
    );
  }
}

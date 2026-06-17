/// 監視対象の通貨ペア設定。
class WatchPair {
  final String symbol; // 例: "USD/JPY"
  final String interval; // 例: "15min"
  final bool enabled;

  const WatchPair({
    required this.symbol,
    this.interval = '15min',
    this.enabled = true,
  });

  WatchPair copyWith({String? symbol, String? interval, bool? enabled}) {
    return WatchPair(
      symbol: symbol ?? this.symbol,
      interval: interval ?? this.interval,
      enabled: enabled ?? this.enabled,
    );
  }

  Map<String, dynamic> toJson() => {
        'symbol': symbol,
        'interval': interval,
        'enabled': enabled,
      };

  factory WatchPair.fromJson(Map<String, dynamic> json) => WatchPair(
        symbol: json['symbol'] as String,
        interval: (json['interval'] as String?) ?? '15min',
        enabled: (json['enabled'] as bool?) ?? true,
      );
}

/// 主要な通貨ペアの候補（設定画面の追加用）。
const kAvailablePairs = <String>[
  'USD/JPY',
  'EUR/JPY',
  'GBP/JPY',
  'AUD/JPY',
  'EUR/USD',
  'GBP/USD',
  'AUD/USD',
];

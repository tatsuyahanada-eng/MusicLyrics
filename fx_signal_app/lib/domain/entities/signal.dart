/// 売買シグナルの方向。
enum Side { buy, sell }

extension SideLabel on Side {
  String get jp => this == Side.buy ? '買い' : '売り';
}

/// 相場のトレンド状態。
enum Trend { up, down, range }

extension TrendLabel on Trend {
  String get jp {
    switch (this) {
      case Trend.up:
        return '上昇';
      case Trend.down:
        return '下降';
      case Trend.range:
        return 'レンジ';
    }
  }

  String get arrow {
    switch (this) {
      case Trend.up:
        return '↑';
      case Trend.down:
        return '↓';
      case Trend.range:
        return '→';
    }
  }
}

/// 生成された売買シグナル（参考情報）。
class Signal {
  final String pair; // 例: "USD/JPY"
  final Side side;
  final int strength; // 1..3（一致した指標数）
  final List<String> reasons; // 例: ["ゴールデンクロス", "MACD上抜け"]
  final double price; // 発生時の終値
  final DateTime time; // シグナルの根拠となった足の確定時刻

  const Signal({
    required this.pair,
    required this.side,
    required this.strength,
    required this.reasons,
    required this.price,
    required this.time,
  });

  String get stars => '★' * strength;

  Map<String, dynamic> toJson() => {
        'pair': pair,
        'side': side.name,
        'strength': strength,
        'reasons': reasons,
        'price': price,
        'time': time.toIso8601String(),
      };

  factory Signal.fromJson(Map<String, dynamic> json) => Signal(
        pair: json['pair'] as String,
        side: Side.values.byName(json['side'] as String),
        strength: json['strength'] as int,
        reasons: (json['reasons'] as List).cast<String>(),
        price: (json['price'] as num).toDouble(),
        time: DateTime.parse(json['time'] as String),
      );
}

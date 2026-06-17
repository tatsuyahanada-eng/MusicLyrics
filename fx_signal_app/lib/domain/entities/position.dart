import 'signal.dart';

enum PositionStatus { open, closed }

/// 手動で記録する建玉（ポジション）。
/// side=buy はロング（買い建て）、side=sell はショート（売り建て）。
class Position {
  final String id;
  final String pair;
  final Side side;
  final double entryPrice;
  final double amount; // 任意の数量（0なら未指定）
  final DateTime openedAt;
  final PositionStatus status;
  final double? closePrice;
  final DateTime? closedAt;

  /// 同じ建玉に対して決済アラートを重複通知しないための直近アラート足時刻。
  final DateTime? lastAlertTime;

  const Position({
    required this.id,
    required this.pair,
    required this.side,
    required this.entryPrice,
    this.amount = 0,
    required this.openedAt,
    this.status = PositionStatus.open,
    this.closePrice,
    this.closedAt,
    this.lastAlertTime,
  });

  bool get isOpen => status == PositionStatus.open;

  /// 現在値での価格差（pips的な値）。ロングは上昇、ショートは下落でプラス。
  double profitAt(double current) =>
      side == Side.buy ? current - entryPrice : entryPrice - current;

  /// 数量を考慮した損益（amount未指定なら価格差をそのまま返す）。
  double pnlAt(double current) =>
      profitAt(current) * (amount > 0 ? amount : 1);

  bool inProfitAt(double current) => profitAt(current) > 0;

  /// 決済済みの確定損益。
  double? get realizedPnl => closePrice == null
      ? null
      : (side == Side.buy
              ? closePrice! - entryPrice
              : entryPrice - closePrice!) *
          (amount > 0 ? amount : 1);

  Position copyWith({
    PositionStatus? status,
    double? closePrice,
    DateTime? closedAt,
    DateTime? lastAlertTime,
  }) {
    return Position(
      id: id,
      pair: pair,
      side: side,
      entryPrice: entryPrice,
      amount: amount,
      openedAt: openedAt,
      status: status ?? this.status,
      closePrice: closePrice ?? this.closePrice,
      closedAt: closedAt ?? this.closedAt,
      lastAlertTime: lastAlertTime ?? this.lastAlertTime,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'pair': pair,
        'side': side.name,
        'entryPrice': entryPrice,
        'amount': amount,
        'openedAt': openedAt.toIso8601String(),
        'status': status.name,
        'closePrice': closePrice,
        'closedAt': closedAt?.toIso8601String(),
        'lastAlertTime': lastAlertTime?.toIso8601String(),
      };

  factory Position.fromJson(Map<String, dynamic> j) => Position(
        id: j['id'] as String,
        pair: j['pair'] as String,
        side: Side.values.byName(j['side'] as String),
        entryPrice: (j['entryPrice'] as num).toDouble(),
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        openedAt: DateTime.parse(j['openedAt'] as String),
        status: PositionStatus.values.byName(j['status'] as String),
        closePrice: (j['closePrice'] as num?)?.toDouble(),
        closedAt: j['closedAt'] == null
            ? null
            : DateTime.parse(j['closedAt'] as String),
        lastAlertTime: j['lastAlertTime'] == null
            ? null
            : DateTime.parse(j['lastAlertTime'] as String),
      );
}

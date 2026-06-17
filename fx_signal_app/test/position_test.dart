import 'package:flutter_test/flutter_test.dart';
import 'package:fx_signal_app/domain/entities/position.dart';
import 'package:fx_signal_app/domain/entities/signal.dart';

Position _pos(Side side, double entry, {double amount = 0}) => Position(
      id: 'x',
      pair: 'USD/JPY',
      side: side,
      entryPrice: entry,
      amount: amount,
      openedAt: DateTime.utc(2026, 1, 1),
    );

void main() {
  group('ロング（買い）建玉', () {
    final p = _pos(Side.buy, 100);
    test('価格上昇で含み益', () {
      expect(p.profitAt(101), closeTo(1.0, 1e-9));
      expect(p.inProfitAt(101), isTrue);
    });
    test('価格下落で含み損', () {
      expect(p.profitAt(99), closeTo(-1.0, 1e-9));
      expect(p.inProfitAt(99), isFalse);
    });
  });

  group('ショート（売り）建玉', () {
    final p = _pos(Side.sell, 100);
    test('価格下落で含み益', () {
      expect(p.profitAt(98), closeTo(2.0, 1e-9));
      expect(p.inProfitAt(98), isTrue);
    });
    test('価格上昇で含み損', () {
      expect(p.inProfitAt(101), isFalse);
    });
  });

  test('数量を考慮した損益', () {
    final p = _pos(Side.buy, 100, amount: 1000);
    expect(p.pnlAt(100.5), closeTo(500.0, 1e-6));
  });

  test('決済の確定損益', () {
    final p = _pos(Side.buy, 100).copyWith(closePrice: 102);
    expect(p.realizedPnl, closeTo(2.0, 1e-9));
  });
}

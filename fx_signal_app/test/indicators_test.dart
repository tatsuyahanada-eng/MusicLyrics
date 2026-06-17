import 'package:flutter_test/flutter_test.dart';
import 'package:fx_signal_app/domain/indicators/indicators.dart';

void main() {
  group('SMA', () {
    test('単純移動平均が正しい', () {
      final v = <double>[1, 2, 3, 4, 5];
      final r = Indicators.sma(v, 3);
      expect(r[0], isNull);
      expect(r[1], isNull);
      expect(r[2], closeTo(2.0, 1e-9)); // (1+2+3)/3
      expect(r[3], closeTo(3.0, 1e-9));
      expect(r[4], closeTo(4.0, 1e-9));
    });
  });

  group('RSI', () {
    test('一貫して上昇する系列では100に近づく', () {
      final v = List<double>.generate(30, (i) => i.toDouble());
      final r = Indicators.rsi(v, 14);
      expect(r.last, closeTo(100.0, 1e-6));
    });

    test('一貫して下落する系列では0に近づく', () {
      final v = List<double>.generate(30, (i) => (30 - i).toDouble());
      final r = Indicators.rsi(v, 14);
      expect(r.last, closeTo(0.0, 1e-6));
    });
  });

  group('クロス検出', () {
    test('crossedUp は下から上抜けを検出', () {
      expect(crossedUp([1.0, 3.0], [2.0, 2.0]), isTrue);
      expect(crossedUp([3.0, 1.0], [2.0, 2.0]), isFalse);
    });

    test('crossedDown は上から下抜けを検出', () {
      expect(crossedDown([3.0, 1.0], [2.0, 2.0]), isTrue);
      expect(crossedDown([1.0, 3.0], [2.0, 2.0]), isFalse);
    });

    test('null を含む場合は false', () {
      expect(crossedUp([null, 3.0], [2.0, 2.0]), isFalse);
    });
  });

  group('MACD', () {
    test('十分な長さでヒストグラムが算出される', () {
      final v = List<double>.generate(60, (i) => 100 + i * 0.5);
      final m = Indicators.macd(v);
      expect(m.line.last, isNotNull);
      expect(m.signal.last, isNotNull);
      expect(m.histogram.last, isNotNull);
    });
  });
}

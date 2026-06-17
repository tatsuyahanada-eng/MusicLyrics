import 'package:flutter_test/flutter_test.dart';
import 'package:fx_signal_app/domain/entities/candle.dart';
import 'package:fx_signal_app/domain/entities/signal.dart';
import 'package:fx_signal_app/domain/signal/signal_engine.dart';

List<Candle> _candles(List<double> closes) {
  final base = DateTime.utc(2026, 1, 1);
  return [
    for (var i = 0; i < closes.length; i++)
      Candle(
        datetime: base.add(Duration(minutes: 15 * i)),
        open: closes[i],
        high: closes[i],
        low: closes[i],
        close: closes[i],
      ),
  ];
}

void main() {
  const engine = SignalEngine();

  test('データ不足ならシグナルなし・レンジ', () {
    final e = engine.evaluate('USD/JPY', _candles([1, 2, 3]));
    expect(e.signal, isNull);
    expect(e.trend, Trend.range);
  });

  test('下降から上昇への転換で買いシグナルが検出される', () {
    // 前半下降 → 後半急上昇でゴールデンクロス＋MACD上抜けを誘発。
    // エンジンは「最後の足」でのクロスを判定するため、プレフィックスを
    // 1本ずつ伸ばしながら評価し、転換局面で買いシグナルが出ることを確認する。
    final closes = <double>[
      for (var i = 0; i < 30; i++) 120.0 - i * 0.3, // 下降
      for (var i = 0; i < 15; i++) 111.0 + i * 1.2, // 反発上昇
    ];
    final buys = <Signal>[];
    for (var end = 24; end <= closes.length; end++) {
      final e = engine.evaluate('USD/JPY', _candles(closes.sublist(0, end)));
      if (e.signal != null && e.signal!.side == Side.buy) {
        buys.add(e.signal!);
      }
    }
    expect(buys, isNotEmpty);
    expect(buys.first.strength, greaterThanOrEqualTo(2));
  });

  test('上昇から下降への転換で売りシグナルが検出される', () {
    final closes = <double>[
      for (var i = 0; i < 30; i++) 100.0 + i * 0.3, // 上昇
      for (var i = 0; i < 15; i++) 109.0 - i * 1.2, // 反落
    ];
    final sells = <Signal>[];
    for (var end = 24; end <= closes.length; end++) {
      final e = engine.evaluate('USD/JPY', _candles(closes.sublist(0, end)));
      if (e.signal != null && e.signal!.side == Side.sell) {
        sells.add(e.signal!);
      }
    }
    expect(sells, isNotEmpty);
  });

  test('重複判定: 同方向・同時刻は重複', () {
    final t = DateTime.utc(2026, 1, 1, 9, 0);
    final a = Signal(
        pair: 'USD/JPY',
        side: Side.buy,
        strength: 2,
        reasons: const [],
        price: 1,
        time: t);
    final b = Signal(
        pair: 'USD/JPY',
        side: Side.buy,
        strength: 3,
        reasons: const [],
        price: 2,
        time: t);
    expect(SignalEngine.isDuplicate(a, b), isTrue);
  });
}

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../application/providers.dart';
import '../../domain/entities/candle.dart';
import '../../domain/entities/signal.dart';
import '../ui_colors.dart';

class PairDetailScreen extends ConsumerWidget {
  final String symbol;
  const PairDetailScreen({super.key, required this.symbol});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(pairViewProvider(symbol));
    return Scaffold(
      appBar: AppBar(title: Text(symbol)),
      body: view.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('取得エラー: $e')),
        data: (v) {
          final e = v.evaluation;
          final color = trendColor(e.trend);
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _card(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: color.withValues(alpha: 0.12),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text('${e.trend.arrow} ${e.trend.jp}',
                              style: TextStyle(
                                  color: color, fontWeight: FontWeight.bold)),
                        ),
                        const Spacer(),
                        Text(
                          e.lastClose?.toStringAsFixed(3) ?? '-',
                          style: const TextStyle(
                              fontSize: 34, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _chart(v.candles, color),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              _card(
                child: Column(
                  children: [
                    _StatRow(
                        label: 'SMA短期',
                        value: e.smaShort?.toStringAsFixed(3) ?? '-'),
                    _StatRow(
                        label: 'SMA長期',
                        value: e.smaLong?.toStringAsFixed(3) ?? '-'),
                    _StatRow(
                        label: 'RSI', value: e.rsi?.toStringAsFixed(1) ?? '-'),
                    _StatRow(
                        label: 'MACDヒスト',
                        value: e.macdHist?.toStringAsFixed(4) ?? '-'),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              _SignalSection(signal: e.signal),
            ],
          );
        },
      ),
    );
  }

  Widget _card({required Widget child}) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: child,
    );
  }

  Widget _chart(List<Candle> candles, Color color) {
    final spots = <FlSpot>[];
    for (var i = 0; i < candles.length; i++) {
      spots.add(FlSpot(i.toDouble(), candles[i].close));
    }
    return SizedBox(
      height: 220,
      child: LineChart(
        LineChartData(
          titlesData: const FlTitlesData(
            topTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
            rightTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
            bottomTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
          ),
          lineBarsData: [
            LineChartBarData(
              spots: spots,
              isCurved: true,
              dotData: const FlDotData(show: false),
              barWidth: 2.5,
              color: color,
              belowBarData: BarAreaData(
                show: true,
                color: color.withValues(alpha: 0.12),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatRow extends StatelessWidget {
  final String label;
  final String value;
  const _StatRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(color: Colors.grey)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}

class _SignalSection extends StatelessWidget {
  final Signal? signal;
  const _SignalSection({required this.signal});

  @override
  Widget build(BuildContext context) {
    if (signal == null) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: const Color(0xFFEFF1F6),
          borderRadius: BorderRadius.circular(16),
        ),
        child: const Text('現在シグナルは出ていません（待機中）',
            style: TextStyle(fontSize: 16, color: kNeutral)),
      );
    }
    final isBuy = signal!.side == Side.buy;
    final color = sideColor(signal!.side);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [color, color.withValues(alpha: 0.8)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.4),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(isBuy ? Icons.trending_up : Icons.trending_down,
                  color: Colors.white, size: 28),
              const SizedBox(width: 8),
              Text(
                '${signal!.side.jp} (${signal!.side.bidAsk}) ${signal!.stars}',
                style: const TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _line('根拠', signal!.reasons.join(', ')),
          _line('価格', signal!.price.toStringAsFixed(3)),
          _line('時刻', '${signal!.time.toLocal()}'),
        ],
      ),
    );
  }

  Widget _line(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Text(
        '$label: $value',
        style: const TextStyle(color: Colors.white, fontSize: 14),
      ),
    );
  }
}

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../application/providers.dart';
import '../../domain/entities/candle.dart';
import '../../domain/entities/signal.dart';

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
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _chart(v.candles),
              const SizedBox(height: 16),
              _StatRow(label: 'トレンド', value: '${e.trend.arrow} ${e.trend.jp}'),
              _StatRow(
                  label: '現在値', value: e.lastClose?.toStringAsFixed(3) ?? '-'),
              _StatRow(
                  label: 'SMA短期', value: e.smaShort?.toStringAsFixed(3) ?? '-'),
              _StatRow(
                  label: 'SMA長期', value: e.smaLong?.toStringAsFixed(3) ?? '-'),
              _StatRow(label: 'RSI', value: e.rsi?.toStringAsFixed(1) ?? '-'),
              _StatRow(
                  label: 'MACDヒスト',
                  value: e.macdHist?.toStringAsFixed(4) ?? '-'),
              const Divider(height: 32),
              _SignalSection(signal: e.signal),
            ],
          );
        },
      ),
    );
  }

  Widget _chart(List<Candle> candles) {
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
              isCurved: false,
              dotData: const FlDotData(show: false),
              barWidth: 2,
              color: Colors.blue,
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
      return const Text('現在シグナルは出ていません（待機中）',
          style: TextStyle(fontSize: 16));
    }
    final isBuy = signal!.side == Side.buy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${signal!.side.jp}シグナル ${signal!.stars}',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
            color: isBuy ? Colors.green : Colors.red,
          ),
        ),
        const SizedBox(height: 8),
        Text('根拠: ${signal!.reasons.join(', ')}'),
        Text('価格: ${signal!.price.toStringAsFixed(3)}'),
        Text('時刻: ${signal!.time.toLocal()}'),
      ],
    );
  }
}

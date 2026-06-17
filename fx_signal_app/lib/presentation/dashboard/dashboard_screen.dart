import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../application/providers.dart';
import '../../data/settings/settings_repository.dart';
import '../../domain/entities/signal.dart';
import '../pair_detail/pair_detail_screen.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(settingsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('FXシグナル'),
        actions: [
          IconButton(
            tooltip: '今すぐ更新',
            icon: const Icon(Icons.refresh),
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              messenger.showSnackBar(
                const SnackBar(content: Text('更新中...')),
              );
              await ref.read(manualRefreshProvider)();
              messenger.showSnackBar(
                const SnackBar(content: Text('更新しました')),
              );
            },
          ),
        ],
      ),
      body: settings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('設定の読み込みに失敗: $e')),
        data: (s) => _Body(settings: s),
      ),
    );
  }
}

class _Body extends StatelessWidget {
  final AppSettings settings;
  const _Body({required this.settings});

  @override
  Widget build(BuildContext context) {
    if (settings.apiKey.isEmpty) {
      return const _ApiKeyHint();
    }
    final pairs = settings.pairs.where((p) => p.enabled).toList();
    if (pairs.isEmpty) {
      return const Center(child: Text('設定画面で監視する通貨ペアを追加してください'));
    }
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        const _Disclaimer(),
        const SizedBox(height: 8),
        for (final p in pairs) _PairCard(symbol: p.symbol),
      ],
    );
  }
}

class _PairCard extends ConsumerWidget {
  final String symbol;
  const _PairCard({required this.symbol});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(pairViewProvider(symbol));
    return Card(
      child: ListTile(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => PairDetailScreen(symbol: symbol),
          ),
        ),
        title: Text(symbol,
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
        subtitle: view.when(
          loading: () => const Text('読み込み中...'),
          error: (e, _) => Text('取得エラー: $e',
              style: const TextStyle(color: Colors.redAccent)),
          data: (v) {
            final e = v.evaluation;
            final price = e.lastClose?.toStringAsFixed(3) ?? '-';
            final rsi = e.rsi?.toStringAsFixed(0) ?? '-';
            return Text(
              '${e.trend.arrow} ${e.trend.jp}  価格 $price  RSI $rsi',
            );
          },
        ),
        trailing: view.maybeWhen(
          data: (v) => _SignalBadge(signal: v.evaluation.signal),
          orElse: () => const SizedBox.shrink(),
        ),
      ),
    );
  }
}

class _SignalBadge extends StatelessWidget {
  final Signal? signal;
  const _SignalBadge({required this.signal});

  @override
  Widget build(BuildContext context) {
    if (signal == null) {
      return const Chip(label: Text('待機'));
    }
    final isBuy = signal!.side == Side.buy;
    return Chip(
      backgroundColor: isBuy ? Colors.green.shade100 : Colors.red.shade100,
      label: Text('${signal!.side.jp} ${signal!.stars}'),
    );
  }
}

class _Disclaimer extends StatelessWidget {
  const _Disclaimer();
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.amber.shade50,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.amber.shade200),
      ),
      child: const Text(
        '⚠️ 本アプリのシグナルは過去データに基づく参考情報です。'
        '将来の値動きや利益を保証するものではなく、投資助言ではありません。'
        '売買の判断はご自身の責任で行ってください。',
        style: TextStyle(fontSize: 12),
      ),
    );
  }
}

class _ApiKeyHint extends StatelessWidget {
  const _ApiKeyHint();
  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(24),
        child: Text(
          'はじめに、設定タブで Twelve Data の無料APIキーを入力してください。\n'
          'twelvedata.com で無料登録するとキーが取得できます。',
          textAlign: TextAlign.center,
        ),
      ),
    );
  }
}

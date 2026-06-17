import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../application/providers.dart';
import '../../data/settings/settings_repository.dart';
import '../../domain/entities/signal.dart';
import '../../domain/signal/signal_engine.dart';
import '../pair_detail/pair_detail_screen.dart';
import '../ui_colors.dart';

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
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFEEF2FB), Color(0xFFF7F8FC)],
        ),
      ),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
        children: [
          const _Disclaimer(),
          const SizedBox(height: 10),
          for (final p in pairs) _PairCard(symbol: p.symbol),
        ],
      ),
    );
  }
}

class _PairCard extends ConsumerWidget {
  final String symbol;
  const _PairCard({required this.symbol});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(pairViewProvider(symbol));

    final accent = view.maybeWhen(
      data: (v) => trendColor(v.evaluation.trend),
      orElse: () => const Color(0xFF7E8AA2),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Material(
        color: Colors.white,
        elevation: 2,
        borderRadius: BorderRadius.circular(16),
        shadowColor: accent.withValues(alpha: 0.3),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => PairDetailScreen(symbol: symbol),
            ),
          ),
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border(left: BorderSide(color: accent, width: 6)),
            ),
            padding: const EdgeInsets.fromLTRB(16, 14, 12, 14),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        symbol,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 19,
                        ),
                      ),
                      const SizedBox(height: 6),
                      view.when(
                        loading: () => const Text('読み込み中...',
                            style: TextStyle(color: Colors.grey)),
                        error: (e, _) => Text(
                          '取得エラー: $e',
                          style: const TextStyle(
                              color: Colors.redAccent, fontSize: 12),
                        ),
                        data: (v) => _Metrics(eval: v.evaluation),
                      ),
                    ],
                  ),
                ),
                view.maybeWhen(
                  data: (v) => _SignalBadge(signal: v.evaluation.signal),
                  orElse: () => const SizedBox.shrink(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Metrics extends StatelessWidget {
  final Evaluation eval;
  const _Metrics({required this.eval});

  @override
  Widget build(BuildContext context) {
    final Trend trend = eval.trend;
    final color = trendColor(trend);
    final price = eval.lastClose?.toStringAsFixed(3) ?? '-';
    final rsi = eval.rsi?.toStringAsFixed(0) ?? '-';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                '${trend.arrow} ${trend.jp}',
                style: TextStyle(color: color, fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              price,
              style: const TextStyle(
                  fontSize: 17, fontWeight: FontWeight.w600),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text('RSI $rsi', style: const TextStyle(color: Colors.grey, fontSize: 12)),
      ],
    );
  }
}

class _SignalBadge extends StatelessWidget {
  final Signal? signal;
  const _SignalBadge({required this.signal});

  @override
  Widget build(BuildContext context) {
    if (signal == null) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: const Color(0xFFEFF1F6),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text('待機',
            style: TextStyle(color: Color(0xFF7E8AA2), fontWeight: FontWeight.bold)),
      );
    }
    final isBuy = signal!.side == Side.buy;
    final color = isBuy ? const Color(0xFF1B9E5A) : const Color(0xFFE53935);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [color, color.withValues(alpha: 0.75)],
        ),
        borderRadius: BorderRadius.circular(12),
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.4),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        children: [
          Icon(isBuy ? Icons.trending_up : Icons.trending_down,
              color: Colors.white, size: 20),
          const SizedBox(height: 2),
          Text(
            signal!.side.jp,
            style: const TextStyle(
                color: Colors.white, fontWeight: FontWeight.bold),
          ),
          Text(signal!.stars,
              style: const TextStyle(color: Colors.white, fontSize: 11)),
        ],
      ),
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
        color: const Color(0xFFFFF6E0),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: const Color(0xFFF3D58A)),
      ),
      child: const Text(
        '⚠️ 本アプリのシグナルは過去データに基づく参考情報です。'
        '将来の値動きや利益を保証するものではなく、投資助言ではありません。'
        '売買の判断はご自身の責任で行ってください。',
        style: TextStyle(fontSize: 12, color: Color(0xFF7A5B00)),
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

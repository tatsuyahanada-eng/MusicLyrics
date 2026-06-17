import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../application/providers.dart';
import '../../domain/entities/signal.dart';
import '../ui_colors.dart';

class HistoryScreen extends ConsumerWidget {
  const HistoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final history = ref.watch(historyProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('シグナル履歴'),
        actions: [
          IconButton(
            tooltip: '履歴を消去',
            icon: const Icon(Icons.delete_outline),
            onPressed: () async {
              await ref.read(historyRepoProvider).clear();
              ref.invalidate(historyProvider);
            },
          ),
        ],
      ),
      body: history.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('エラー: $e')),
        data: (list) {
          if (list.isEmpty) {
            return const Center(child: Text('まだシグナルはありません'));
          }
          return ListView.separated(
            itemCount: list.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (_, i) => _Tile(signal: list[i]),
          );
        },
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  final Signal signal;
  const _Tile({required this.signal});

  @override
  Widget build(BuildContext context) {
    final fmt = DateFormat('MM/dd HH:mm');
    return ListTile(
      leading: CircleAvatar(
        backgroundColor: sideColor(signal.side),
        foregroundColor: Colors.white,
        child: Text(signal.side.jp, style: const TextStyle(fontSize: 12)),
      ),
      title: Text('${signal.pair}  ${signal.stars}'),
      subtitle: Text('${signal.side.bidAsk} ・ ${signal.reasons.join(', ')}'),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(signal.price.toStringAsFixed(3),
              style: const TextStyle(
                  fontSize: 16, fontWeight: FontWeight.bold)),
          Text(fmt.format(signal.time.toLocal()),
              style: const TextStyle(fontSize: 12, color: Colors.grey)),
        ],
      ),
    );
  }
}

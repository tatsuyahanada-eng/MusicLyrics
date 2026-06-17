import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../application/providers.dart';
import '../../domain/entities/position.dart';
import '../../domain/entities/signal.dart';
import '../../domain/entities/watch_pair.dart';
import '../ui_colors.dart';

class PositionsScreen extends ConsumerWidget {
  const PositionsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final all = ref.watch(allPositionsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('ポジション')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showAddDialog(context, ref),
        icon: const Icon(Icons.add),
        label: const Text('建玉を追加'),
      ),
      body: all.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('エラー: $e')),
        data: (list) {
          final open = list.where((p) => p.isOpen).toList();
          final closed = list.where((p) => !p.isOpen).toList();
          return ListView(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
            children: [
              const _Caution(),
              const SizedBox(height: 10),
              const _Header('保有中'),
              if (open.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('保有中の建玉はありません。右下の「建玉を追加」で記録できます。',
                      style: TextStyle(color: kNeutral)),
                ),
              for (final p in open) _OpenCard(pos: p),
              if (closed.isNotEmpty) ...[
                const SizedBox(height: 16),
                const _Header('決済済み'),
                for (final p in closed) _ClosedTile(pos: p),
              ],
            ],
          );
        },
      ),
    );
  }

  Future<void> _showAddDialog(BuildContext context, WidgetRef ref) async {
    final settings = ref.read(settingsProvider).valueOrNull;
    final symbols = <String>{
      ...?settings?.pairs.map((e) => e.symbol),
      ...kAvailablePairs,
    }.toList();

    String pair = symbols.first;
    Side side = Side.buy;
    final priceCtrl = TextEditingController();
    final amountCtrl = TextEditingController();

    // 既知の現在値があれば約定価格に初期表示。
    final current = ref.read(pairViewProvider(pair)).valueOrNull;
    if (current?.evaluation.lastClose != null) {
      priceCtrl.text = current!.evaluation.lastClose!.toStringAsFixed(3);
    }

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('建玉を追加'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: pair,
                  decoration: const InputDecoration(labelText: '通貨ペア'),
                  items: [
                    for (final s in symbols)
                      DropdownMenuItem(value: s, child: Text(s)),
                  ],
                  onChanged: (v) => setState(() => pair = v ?? pair),
                ),
                const SizedBox(height: 12),
                SegmentedButton<Side>(
                  segments: const [
                    ButtonSegment(value: Side.buy, label: Text('買い(ロング)')),
                    ButtonSegment(value: Side.sell, label: Text('売り(ショート)')),
                  ],
                  selected: {side},
                  onSelectionChanged: (s) => setState(() => side = s.first),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: priceCtrl,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(
                    labelText: '約定価格',
                    hintText: '例: 157.200',
                  ),
                ),
                TextField(
                  controller: amountCtrl,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(
                    labelText: '数量（任意・損益計算用）',
                    hintText: '例: 10000',
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('キャンセル')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('追加')),
          ],
        ),
      ),
    );

    if (ok == true) {
      final price = double.tryParse(priceCtrl.text.trim());
      if (price == null) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('約定価格を数値で入力してください')));
        }
        return;
      }
      final pos = Position(
        id: DateTime.now().microsecondsSinceEpoch.toString(),
        pair: pair,
        side: side,
        entryPrice: price,
        amount: double.tryParse(amountCtrl.text.trim()) ?? 0,
        openedAt: DateTime.now(),
      );
      await ref.read(positionRepoProvider).add(pos);
      ref.invalidate(allPositionsProvider);
      ref.invalidate(openPositionsProvider);
    }
  }
}

class _OpenCard extends ConsumerWidget {
  final Position pos;
  const _OpenCard({required this.pos});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(pairViewProvider(pos.pair));
    final current = view.valueOrNull?.evaluation.lastClose;
    final color = sideColor(pos.side);
    final sideLabel = pos.side == Side.buy ? '買い(ロング)' : '売り(ショート)';

    double? pnl;
    if (current != null) pnl = pos.pnlAt(current);
    final pnlColor = pnl == null
        ? kNeutral
        : (pnl >= 0 ? kBuy : kSell);

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(pos.pair,
                    style: const TextStyle(
                        fontWeight: FontWeight.bold, fontSize: 18)),
                const SizedBox(width: 8),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(sideLabel,
                      style:
                          TextStyle(color: color, fontWeight: FontWeight.bold)),
                ),
                const Spacer(),
                TextButton(
                  onPressed: current == null
                      ? null
                      : () => _close(context, ref, current),
                  child: const Text('決済'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                _kv('約定', pos.entryPrice.toStringAsFixed(3)),
                _kv('現在', current?.toStringAsFixed(3) ?? '—'),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    const Text('損益',
                        style: TextStyle(fontSize: 11, color: kNeutral)),
                    Text(
                      pnl == null
                          ? '—'
                          : (pnl >= 0 ? '+' : '') + pnl.toStringAsFixed(3),
                      style: TextStyle(
                          color: pnlColor,
                          fontWeight: FontWeight.bold,
                          fontSize: 18),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(k, style: const TextStyle(fontSize: 11, color: kNeutral)),
          Text(v,
              style:
                  const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
        ],
      );

  Future<void> _close(
      BuildContext context, WidgetRef ref, double current) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('決済しますか？'),
        content: Text('${pos.pair} を現在値 ${current.toStringAsFixed(3)} で決済します。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('キャンセル')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('決済する')),
        ],
      ),
    );
    if (confirm == true) {
      await ref.read(positionRepoProvider).update(
            pos.copyWith(
              status: PositionStatus.closed,
              closePrice: current,
              closedAt: DateTime.now(),
            ),
          );
      ref.invalidate(allPositionsProvider);
      ref.invalidate(openPositionsProvider);
    }
  }
}

class _ClosedTile extends ConsumerWidget {
  final Position pos;
  const _ClosedTile({required this.pos});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pnl = pos.realizedPnl;
    final color = pnl == null ? kNeutral : (pnl >= 0 ? kBuy : kSell);
    final fmt = DateFormat('MM/dd HH:mm');
    final sideLabel = pos.side == Side.buy ? '買い' : '売り';
    return ListTile(
      leading: CircleAvatar(
        backgroundColor: kNeutral.withValues(alpha: 0.2),
        child: Text(sideLabel, style: const TextStyle(fontSize: 12)),
      ),
      title: Text('${pos.pair}  ${pos.entryPrice.toStringAsFixed(3)} → '
          '${pos.closePrice?.toStringAsFixed(3) ?? '-'}'),
      subtitle: Text(pos.closedAt == null ? '' : fmt.format(pos.closedAt!.toLocal())),
      trailing: Text(
        pnl == null ? '' : (pnl >= 0 ? '+' : '') + pnl.toStringAsFixed(3),
        style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 16),
      ),
      onLongPress: () async {
        await ref.read(positionRepoProvider).remove(pos.id);
        ref.invalidate(allPositionsProvider);
      },
    );
  }
}

class _Header extends StatelessWidget {
  final String text;
  const _Header(this.text);
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Text(text,
            style: const TextStyle(
                fontSize: 16, fontWeight: FontWeight.bold, color: kBrand)),
      );
}

class _Caution extends StatelessWidget {
  const _Caution();
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
        '⚠️ 「含み益のときだけ決済アラート」を有効にすると、損失中は決済を促しません。'
        'ただし相場が逆行し続けると含み損が拡大し、強制ロスカット（証拠金不足）の'
        'リスクがあります。損切りなしの運用は自己責任で慎重に。',
        style: TextStyle(fontSize: 12, color: Color(0xFF7A5B00)),
      ),
    );
  }
}

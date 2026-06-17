import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../application/providers.dart';
import '../../background/notifier.dart';
import '../../background/worker.dart';
import '../../data/settings/settings_repository.dart';
import '../../domain/entities/watch_pair.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(settingsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('設定')),
      body: settings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('エラー: $e')),
        data: (s) => _Form(settings: s),
      ),
    );
  }
}

class _Form extends ConsumerStatefulWidget {
  final AppSettings settings;
  const _Form({required this.settings});

  @override
  ConsumerState<_Form> createState() => _FormState();
}

class _FormState extends ConsumerState<_Form> {
  late TextEditingController _apiKey;
  late int _poll;
  late bool _notify;
  late bool _profitOnly;
  late List<WatchPair> _pairs;

  @override
  void initState() {
    super.initState();
    _apiKey = TextEditingController(text: widget.settings.apiKey);
    _poll = widget.settings.pollMinutes;
    _notify = widget.settings.notifyEnabled;
    _profitOnly = widget.settings.profitOnlyClose;
    _pairs = List.of(widget.settings.pairs);
  }

  @override
  void dispose() {
    _apiKey.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final updated = widget.settings.copyWith(
      apiKey: _apiKey.text.trim(),
      pollMinutes: _poll,
      notifyEnabled: _notify,
      profitOnlyClose: _profitOnly,
      pairs: _pairs,
    );
    await ref.read(settingsProvider.notifier).save(updated);

    // 通知設定の反映。
    if (_notify) {
      await LocalNotifier.requestPermission();
      await BackgroundScheduler.schedule(_poll);
    } else {
      await BackgroundScheduler.cancel();
    }
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('保存しました')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final usedCredits = _pairs.where((p) => p.enabled).length *
        (24 * 60 ~/ (_poll < 15 ? 15 : _poll));
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text('Twelve Data APIキー',
            style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 4),
        TextField(
          controller: _apiKey,
          decoration: const InputDecoration(
            hintText: 'twelvedata.com で無料取得したキー',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 24),
        Text('監視周期: $_poll 分', style: const TextStyle(fontWeight: FontWeight.bold)),
        Slider(
          value: _poll.toDouble(),
          min: 15,
          max: 120,
          divisions: 7,
          label: '$_poll分',
          onChanged: (v) => setState(() => _poll = v.round()),
        ),
        Text('推定API使用: 約 $usedCredits credit/日（無料枠 800/日）',
            style: TextStyle(
              fontSize: 12,
              color: usedCredits > 800 ? Colors.red : Colors.grey,
            )),
        const SizedBox(height: 16),
        SwitchListTile(
          title: const Text('通知を有効にする'),
          value: _notify,
          onChanged: (v) => setState(() => _notify = v),
        ),
        SwitchListTile(
          title: const Text('含み益のときだけ決済アラート'),
          subtitle: const Text('損切り回避モード。ONだと損失中は決済を促しません'),
          value: _profitOnly,
          onChanged: (v) => setState(() => _profitOnly = v),
        ),
        const Divider(height: 32),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('監視する通貨ペア',
                style: TextStyle(fontWeight: FontWeight.bold)),
            TextButton.icon(
              onPressed: _addPairDialog,
              icon: const Icon(Icons.add),
              label: const Text('追加'),
            ),
          ],
        ),
        for (var i = 0; i < _pairs.length; i++)
          SwitchListTile(
            title: Text(_pairs[i].symbol),
            subtitle: Text('時間足: ${_pairs[i].interval}'),
            value: _pairs[i].enabled,
            secondary: IconButton(
              icon: const Icon(Icons.delete_outline),
              onPressed: () => setState(() => _pairs.removeAt(i)),
            ),
            onChanged: (v) =>
                setState(() => _pairs[i] = _pairs[i].copyWith(enabled: v)),
          ),
        const SizedBox(height: 24),
        FilledButton(onPressed: _save, child: const Text('保存')),
      ],
    );
  }

  Future<void> _addPairDialog() async {
    final existing = _pairs.map((e) => e.symbol).toSet();
    final candidates =
        kAvailablePairs.where((s) => !existing.contains(s)).toList();
    if (candidates.isEmpty) return;
    final selected = await showDialog<String>(
      context: context,
      builder: (_) => SimpleDialog(
        title: const Text('通貨ペアを追加'),
        children: [
          for (final s in candidates)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(context, s),
              child: Text(s),
            ),
        ],
      ),
    );
    if (selected != null) {
      setState(() => _pairs.add(WatchPair(symbol: selected)));
    }
  }
}

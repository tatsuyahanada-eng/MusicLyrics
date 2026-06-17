import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/history/history_repository.dart';
import '../data/market/twelve_data_api.dart';
import '../data/settings/settings_repository.dart';
import '../domain/entities/candle.dart';
import '../domain/entities/signal.dart';
import '../domain/signal/signal_engine.dart';
import 'monitor_service.dart';

final settingsRepoProvider = Provider((_) => SettingsRepository());
final historyRepoProvider = Provider((_) => HistoryRepository());
final monitorServiceProvider = Provider((_) => MonitorService());

/// アプリ設定（読み込み・更新）。
class SettingsNotifier extends AsyncNotifier<AppSettings> {
  @override
  Future<AppSettings> build() => ref.read(settingsRepoProvider).load();

  Future<void> save(AppSettings settings) async {
    await ref.read(settingsRepoProvider).save(settings);
    state = AsyncData(settings);
  }
}

final settingsProvider =
    AsyncNotifierProvider<SettingsNotifier, AppSettings>(SettingsNotifier.new);

/// シグナル履歴。
final historyProvider = FutureProvider<List<Signal>>((ref) {
  return ref.watch(historyRepoProvider).load();
});

/// 1ペア分のローソク足 + 評価結果（ダッシュボード/詳細で共有）。
class PairView {
  final List<Candle> candles;
  final Evaluation evaluation;
  const PairView(this.candles, this.evaluation);
}

final pairViewProvider =
    FutureProvider.family<PairView, String>((ref, symbol) async {
  final settings = await ref.watch(settingsProvider.future);
  final api = TwelveDataApi(settings.apiKey);
  try {
    final candles = await api.fetchCandles(symbol);
    final engine = SignalEngine(config: settings.indicator);
    return PairView(candles, engine.evaluate(symbol, candles));
  } finally {
    api.close();
  }
});

/// 手動更新トリガ（全ペア評価＋通知＋履歴保存）。
final manualRefreshProvider = Provider((ref) {
  return () async {
    await ref.read(monitorServiceProvider).runCycle(notify: true);
    ref.invalidate(historyProvider);
    final settings = ref.read(settingsProvider).valueOrNull;
    if (settings != null) {
      for (final p in settings.pairs) {
        ref.invalidate(pairViewProvider(p.symbol));
      }
    }
  };
});

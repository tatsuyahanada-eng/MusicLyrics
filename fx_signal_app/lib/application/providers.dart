import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/history/history_repository.dart';
import '../data/market/twelve_data_api.dart';
import '../data/settings/settings_repository.dart';
import '../domain/entities/candle.dart';
import '../domain/entities/signal.dart';
import '../domain/signal/signal_engine.dart';
import '../background/notifier.dart';
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
  // 取得結果はキャッシュし、画面再描画やタブ切替で再取得しない
  // （無料枠の節約。更新は手動の🔄か周期監視のみ）。
  ref.keepAlive();

  final settings = await ref.watch(settingsProvider.future);
  final api = TwelveDataApi(settings.apiKey);
  try {
    final candles = await api.fetchCandles(symbol);
    final engine = SignalEngine(config: settings.indicator);
    final view = PairView(candles, engine.evaluate(symbol, candles));

    // 表示と同時に、新規シグナルなら通知＋履歴保存（前面取得を一本化）。
    final signal = view.evaluation.signal;
    if (signal != null) {
      final historyRepo = ref.read(historyRepoProvider);
      final last = await historyRepo.lastFor(symbol);
      if (!SignalEngine.isDuplicate(last, signal)) {
        await historyRepo.add(signal);
        if (settings.notifyEnabled) {
          await LocalNotifier.showSignal(signal);
        }
        ref.invalidate(historyProvider);
      }
    }
    return view;
  } finally {
    api.close();
  }
});

/// 手動更新トリガ（全ペアを順次再取得。取得時に通知＋履歴も更新される）。
final manualRefreshProvider = Provider((ref) {
  return () async {
    final settings = ref.read(settingsProvider).valueOrNull;
    if (settings == null) return;
    for (final p in settings.pairs.where((e) => e.enabled)) {
      ref.invalidate(pairViewProvider(p.symbol));
      // 次の取得へ進む前に完了を待つ（API側の直列化と合わせて間隔を確保）。
      // 個別の取得エラーは各カードに表示されるためここでは握りつぶす。
      try {
        await ref.read(pairViewProvider(p.symbol).future);
      } catch (_) {}
    }
  };
});

import '../data/history/history_repository.dart';
import '../data/market/twelve_data_api.dart';
import '../data/positions/position_repository.dart';
import '../data/settings/settings_repository.dart';
import '../domain/entities/signal.dart';
import '../domain/signal/signal_engine.dart';
import '../background/notifier.dart';
import 'position_alerts.dart';

/// 全監視ペアを1巡して評価・通知・履歴保存を行う中核処理。
///
/// 前面（手動更新）からもバックグラウンド（WorkManager）からも
/// 同じロジックを使えるよう、Riverpod に依存しない純粋関数として実装する。
class MonitorService {
  final SettingsRepository settingsRepo;
  final HistoryRepository historyRepo;
  final PositionAlertService positionAlerts;

  MonitorService({
    SettingsRepository? settingsRepo,
    HistoryRepository? historyRepo,
    PositionAlertService? positionAlerts,
  })  : settingsRepo = settingsRepo ?? SettingsRepository(),
        historyRepo = historyRepo ?? HistoryRepository(),
        positionAlerts = positionAlerts ??
            PositionAlertService(repo: PositionRepository());

  /// 1巡実行。新規に検出したシグナルのリストを返す。
  /// [notify] が true のときは通知も発火する。
  Future<List<Signal>> runCycle({bool notify = true}) async {
    final settings = await settingsRepo.load();
    if (settings.apiKey.isEmpty) return [];

    final api = TwelveDataApi(settings.apiKey);
    final engine = SignalEngine(config: settings.indicator);
    final newSignals = <Signal>[];

    try {
      for (final pair in settings.pairs.where((p) => p.enabled)) {
        try {
          final candles = await api.fetchCandles(
            pair.symbol,
            interval: pair.interval,
          );
          final eval = engine.evaluate(pair.symbol, candles);

          // 保有ポジションの決済（利確）アラート判定。
          await positionAlerts.check(
            pair.symbol,
            eval,
            profitOnly: settings.profitOnlyClose,
            notifyEnabled: notify && settings.notifyEnabled,
          );

          final signal = eval.signal;
          if (signal == null) continue;

          final last = await historyRepo.lastFor(pair.symbol);
          if (SignalEngine.isDuplicate(last, signal)) continue;

          await historyRepo.add(signal);
          newSignals.add(signal);
          if (notify && settings.notifyEnabled) {
            await LocalNotifier.showSignal(signal);
          }
        } on MarketException {
          // 個別ペアの失敗（レート制限など）はスキップして次へ。
          continue;
        }
      }
    } finally {
      api.close();
    }
    return newSignals;
  }
}

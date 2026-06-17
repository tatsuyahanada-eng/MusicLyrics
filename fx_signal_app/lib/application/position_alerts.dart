import '../background/notifier.dart';
import '../data/positions/position_repository.dart';
import '../domain/signal/signal_engine.dart';

/// 保有ポジションに対する決済（利確）アラートを判定・通知する。
///
/// 反対シグナルが出たら「決済の目安」を通知する。
/// [profitOnly] が true のときは含み益のときだけ通知する（損切り回避）。
class PositionAlertService {
  final PositionRepository repo;
  PositionAlertService({PositionRepository? repo})
      : repo = repo ?? PositionRepository();

  Future<void> check(
    String pair,
    Evaluation eval, {
    required bool profitOnly,
    required bool notifyEnabled,
  }) async {
    final signal = eval.signal;
    final price = eval.lastClose;
    if (signal == null || price == null || !notifyEnabled) return;

    final positions = await repo.load();
    for (final pos in positions) {
      if (!pos.isOpen || pos.pair != pair) continue;
      // 反対方向のシグナル＝決済の目安。
      if (signal.side == pos.side) continue;
      // 含み益のときだけ（損切り回避モード）。
      if (profitOnly && !pos.inProfitAt(price)) continue;
      // 同じ足での重複通知を抑制。
      if (pos.lastAlertTime != null &&
          !signal.time.isAfter(pos.lastAlertTime!)) {
        continue;
      }
      await LocalNotifier.showPositionAlert(pos, price, signal);
      await repo.update(pos.copyWith(lastAlertTime: signal.time));
    }
  }
}

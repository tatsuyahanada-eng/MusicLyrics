import 'package:workmanager/workmanager.dart';

import '../application/monitor_service.dart';
import 'notifier.dart';

const kMonitorTask = 'fx_monitor_task';
const kMonitorTaskUnique = 'fx_monitor_periodic';

/// WorkManager のバックグラウンド・エントリポイント。
/// 別 isolate で実行されるため、必要な初期化はここで行う。
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      await LocalNotifier.init();
      await MonitorService().runCycle(notify: true);
      return true;
    } catch (_) {
      // 失敗しても再スケジュールに任せる。
      return true;
    }
  });
}

/// バックグラウンド監視を初期化・登録する。
class BackgroundScheduler {
  static Future<void> init() async {
    await Workmanager().initialize(callbackDispatcher);
  }

  /// 周期タスクを登録（Android の最小周期は15分）。
  static Future<void> schedule(int minutes) async {
    final period = Duration(minutes: minutes < 15 ? 15 : minutes);
    await Workmanager().registerPeriodicTask(
      kMonitorTaskUnique,
      kMonitorTask,
      frequency: period,
      existingWorkPolicy: ExistingWorkPolicy.replace,
      constraints: Constraints(networkType: NetworkType.connected),
    );
  }

  static Future<void> cancel() async {
    await Workmanager().cancelByUniqueName(kMonitorTaskUnique);
  }
}

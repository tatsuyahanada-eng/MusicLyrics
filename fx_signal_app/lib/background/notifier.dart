import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../domain/entities/signal.dart';

/// ローカル通知のラッパ（サーバー不要・無料）。
class LocalNotifier {
  static final _plugin = FlutterLocalNotificationsPlugin();
  static const _channelId = 'fx_signals';

  static Future<void> init() async {
    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    const settings = InitializationSettings(android: android);
    await _plugin.initialize(settings);

    const channel = AndroidNotificationChannel(
      _channelId,
      'FXシグナル',
      description: '売買シグナルの通知',
      importance: Importance.high,
    );
    await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);
  }

  /// Android 13+ の通知権限をリクエスト。
  static Future<bool?> requestPermission() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    return await android?.requestNotificationsPermission();
  }

  static Future<void> showSignal(Signal s) async {
    const details = NotificationDetails(
      android: AndroidNotificationDetails(
        _channelId,
        'FXシグナル',
        importance: Importance.high,
        priority: Priority.high,
      ),
    );
    final title = '${s.pair} ${s.side.jp}シグナル ${s.stars}';
    final time =
        '${s.time.hour.toString().padLeft(2, '0')}:${s.time.minute.toString().padLeft(2, '0')}';
    final body = '$time / ${s.price.toStringAsFixed(3)}  根拠: ${s.reasons.join(', ')}';
    await _plugin.show(
      s.pair.hashCode & 0x7fffffff,
      title,
      body,
      details,
      payload: s.pair,
    );
  }
}

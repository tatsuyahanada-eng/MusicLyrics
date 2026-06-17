import 'dart:convert';
import 'package:http/http.dart' as http;

import '../../domain/entities/candle.dart';

class MarketException implements Exception {
  final String message;
  MarketException(this.message);
  @override
  String toString() => 'MarketException: $message';
}

/// Twelve Data の無料枠を使った為替データ取得クライアント。
/// APIキーはユーザーが設定画面で入力したものを利用する。
class TwelveDataApi {
  static const _base = 'api.twelvedata.com';

  // --- グローバルなレート制御 ---
  // 無料枠は 8 リクエスト/分。アプリ内の全リクエストを直列化し、
  // 最小間隔を空けることで短時間のバースト（429）を防ぐ。
  static const _minGap = Duration(milliseconds: 2000);
  static Future<void> _gate = Future<void>.value();
  static DateTime _lastStart = DateTime.fromMillisecondsSinceEpoch(0);

  final String apiKey;
  final http.Client _client;

  TwelveDataApi(this.apiKey, {http.Client? client})
      : _client = client ?? http.Client();

  /// 直前のリクエストとの間隔を確保しつつ、呼び出しを直列実行する。
  static Future<T> _serialize<T>(Future<T> Function() task) {
    final result = _gate.then((_) async {
      final since = DateTime.now().difference(_lastStart);
      if (since < _minGap) {
        await Future<void>.delayed(_minGap - since);
      }
      _lastStart = DateTime.now();
      return task();
    });
    // 例外でチェーンが止まらないよう、ゲートはエラーを無視して継続。
    _gate = result.then((_) {}, onError: (_) {});
    return result;
  }

  /// ローソク足を取得する（古い→新しい順に整列して返す）。
  Future<List<Candle>> fetchCandles(
    String symbol, {
    String interval = '15min',
    int outputSize = 100,
  }) {
    if (apiKey.isEmpty) {
      throw MarketException('APIキーが未設定です（設定画面で入力してください）');
    }
    return _serialize(() => _fetch(symbol, interval, outputSize));
  }

  Future<List<Candle>> _fetch(
      String symbol, String interval, int outputSize) async {
    final uri = Uri.https(_base, '/time_series', {
      'symbol': symbol,
      'interval': interval,
      'outputsize': '$outputSize',
      'apikey': apiKey,
      'timezone': 'UTC',
    });

    final res = await _client.get(uri).timeout(const Duration(seconds: 20));
    if (res.statusCode == 429) {
      throw MarketException('レート制限（無料枠 8回/分）。少し待つと自動回復します。');
    }
    if (res.statusCode != 200) {
      throw MarketException('HTTP ${res.statusCode}');
    }

    final body = json.decode(res.body) as Map<String, dynamic>;
    // Twelve Data はエラーも 200 で status: error を返すことがある。
    if (body['status'] == 'error') {
      final code = body['code'];
      final msg = body['message']?.toString() ?? 'APIエラー';
      if (code == 429) {
        throw MarketException('レート制限（無料枠 8回/分）。少し待つと自動回復します。');
      }
      throw MarketException(msg);
    }
    final values = body['values'];
    if (values is! List || values.isEmpty) {
      throw MarketException('データが取得できませんでした');
    }
    final candles = values
        .cast<Map<String, dynamic>>()
        .map(Candle.fromTwelveData)
        .toList();
    // API は新しい→古い順で返すため反転して古い→新しいにする。
    candles.sort((a, b) => a.datetime.compareTo(b.datetime));
    return candles;
  }

  void close() => _client.close();
}

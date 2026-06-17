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

  final String apiKey;
  final http.Client _client;

  TwelveDataApi(this.apiKey, {http.Client? client})
      : _client = client ?? http.Client();

  /// ローソク足を取得する（古い→新しい順に整列して返す）。
  Future<List<Candle>> fetchCandles(
    String symbol, {
    String interval = '15min',
    int outputSize = 100,
  }) async {
    if (apiKey.isEmpty) {
      throw MarketException('APIキーが未設定です（設定画面で入力してください）');
    }
    final uri = Uri.https(_base, '/time_series', {
      'symbol': symbol,
      'interval': interval,
      'outputsize': '$outputSize',
      'apikey': apiKey,
      'timezone': 'UTC',
    });

    final res = await _client.get(uri).timeout(const Duration(seconds: 20));
    if (res.statusCode == 429) {
      throw MarketException('レート制限に達しました（無料枠）。時間をおいて再試行します。');
    }
    if (res.statusCode != 200) {
      throw MarketException('HTTP ${res.statusCode}');
    }

    final body = json.decode(res.body) as Map<String, dynamic>;
    // Twelve Data はエラーも 200 で status: error を返すことがある。
    if (body['status'] == 'error') {
      throw MarketException(body['message']?.toString() ?? 'APIエラー');
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

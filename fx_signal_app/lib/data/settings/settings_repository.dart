import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/entities/watch_pair.dart';
import '../../domain/signal/signal_engine.dart';

/// アプリ設定（APIキー・監視ペア・周期・指標パラメータ）の永続化。
/// すべて端末内（shared_preferences）にのみ保存し、外部には送らない。
class AppSettings {
  final String apiKey;
  final int pollMinutes;
  final bool notifyEnabled;
  final List<WatchPair> pairs;
  final IndicatorConfig indicator;
  final bool profitOnlyClose; // 含み益のときだけ決済アラートを出す（損切り回避）

  const AppSettings({
    this.apiKey = '',
    this.pollMinutes = 15,
    this.notifyEnabled = true,
    this.pairs = const [],
    this.indicator = const IndicatorConfig(),
    this.profitOnlyClose = true,
  });

  AppSettings copyWith({
    String? apiKey,
    int? pollMinutes,
    bool? notifyEnabled,
    List<WatchPair>? pairs,
    IndicatorConfig? indicator,
    bool? profitOnlyClose,
  }) {
    return AppSettings(
      apiKey: apiKey ?? this.apiKey,
      pollMinutes: pollMinutes ?? this.pollMinutes,
      notifyEnabled: notifyEnabled ?? this.notifyEnabled,
      pairs: pairs ?? this.pairs,
      indicator: indicator ?? this.indicator,
      profitOnlyClose: profitOnlyClose ?? this.profitOnlyClose,
    );
  }
}

class SettingsRepository {
  static const _kApiKey = 'api_key';
  static const _kPoll = 'poll_minutes';
  static const _kNotify = 'notify_enabled';
  static const _kPairs = 'watch_pairs';
  static const _kIndicator = 'indicator_config';
  static const _kProfitOnly = 'profit_only_close';

  Future<AppSettings> load() async {
    final p = await SharedPreferences.getInstance();
    final pairsRaw = p.getString(_kPairs);
    List<WatchPair> pairs;
    if (pairsRaw == null) {
      // 初期値: ドル円 + 主要クロス円。
      pairs = const [
        WatchPair(symbol: 'USD/JPY'),
        WatchPair(symbol: 'EUR/JPY'),
        WatchPair(symbol: 'EUR/USD'),
      ];
    } else {
      pairs = (json.decode(pairsRaw) as List)
          .map((e) => WatchPair.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final indRaw = p.getString(_kIndicator);
    final indicator = indRaw == null
        ? const IndicatorConfig()
        : IndicatorConfig.fromJson(json.decode(indRaw) as Map<String, dynamic>);

    return AppSettings(
      apiKey: p.getString(_kApiKey) ?? '',
      pollMinutes: p.getInt(_kPoll) ?? 15,
      notifyEnabled: p.getBool(_kNotify) ?? true,
      pairs: pairs,
      indicator: indicator,
      profitOnlyClose: p.getBool(_kProfitOnly) ?? true,
    );
  }

  Future<void> save(AppSettings s) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_kApiKey, s.apiKey);
    await p.setInt(_kPoll, s.pollMinutes);
    await p.setBool(_kNotify, s.notifyEnabled);
    await p.setString(
      _kPairs,
      json.encode(s.pairs.map((e) => e.toJson()).toList()),
    );
    await p.setString(_kIndicator, json.encode(s.indicator.toJson()));
    await p.setBool(_kProfitOnly, s.profitOnlyClose);
  }
}

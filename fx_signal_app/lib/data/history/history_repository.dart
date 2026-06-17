import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/entities/signal.dart';

/// シグナル履歴を端末内に保存（最新200件まで）。
class HistoryRepository {
  static const _kHistory = 'signal_history';
  static const _max = 200;

  Future<List<Signal>> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_kHistory);
    if (raw == null) return [];
    final list = (json.decode(raw) as List)
        .map((e) => Signal.fromJson(e as Map<String, dynamic>))
        .toList();
    list.sort((a, b) => b.time.compareTo(a.time)); // 新しい順
    return list;
  }

  /// シグナルを追加（重複は呼び出し側で抑制済みの想定）。
  Future<void> add(Signal signal) async {
    final list = await load();
    list.insert(0, signal);
    final trimmed = list.take(_max).toList();
    await _persist(trimmed);
  }

  /// 直近の同一ペアのシグナル（重複判定に使用）。
  Future<Signal?> lastFor(String pair) async {
    final list = await load();
    for (final s in list) {
      if (s.pair == pair) return s;
    }
    return null;
  }

  Future<void> clear() async {
    final p = await SharedPreferences.getInstance();
    await p.remove(_kHistory);
  }

  Future<void> _persist(List<Signal> list) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(
      _kHistory,
      json.encode(list.map((e) => e.toJson()).toList()),
    );
  }
}

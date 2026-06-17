import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/entities/position.dart';

/// 建玉（ポジション）を端末内に保存する。
class PositionRepository {
  static const _key = 'positions';

  Future<List<Position>> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_key);
    if (raw == null) return [];
    final list = (json.decode(raw) as List)
        .map((e) => Position.fromJson(e as Map<String, dynamic>))
        .toList();
    list.sort((a, b) => b.openedAt.compareTo(a.openedAt));
    return list;
  }

  Future<List<Position>> openPositions() async =>
      (await load()).where((e) => e.isOpen).toList();

  Future<void> add(Position pos) async {
    final list = await load();
    list.insert(0, pos);
    await _persist(list);
  }

  Future<void> update(Position pos) async {
    final list = await load();
    final i = list.indexWhere((e) => e.id == pos.id);
    if (i >= 0) {
      list[i] = pos;
      await _persist(list);
    }
  }

  Future<void> remove(String id) async {
    final list = await load()
      ..removeWhere((e) => e.id == id);
    await _persist(list);
  }

  Future<void> _persist(List<Position> list) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_key, json.encode(list.map((e) => e.toJson()).toList()));
  }
}

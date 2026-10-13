import 'dart:convert';
import 'dart:developer' as dev;

import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/model/tutorial.dart';
import '../../domain/repository/tutorial_repository.dart';
import '../datasource/remote_tutorial_datasource.dart';

/// TutorialRepositoryImpl — server first, SharedPreferences cache fallback
/// (v1s-14-6). The catalog is small (a few entries): a JSON string is enough.
class TutorialRepositoryImpl implements TutorialRepository {
  static const cacheKey = 'help_tutorials_cache_v1';

  final RemoteTutorialDataSource _remote;
  final SharedPreferences _prefs;
  final DateTime Function() _now;

  TutorialRepositoryImpl({
    required RemoteTutorialDataSource remote,
    required SharedPreferences prefs,
    DateTime Function()? now,
  })  : _remote = remote,
        _prefs = prefs,
        _now = now ?? DateTime.now;

  @override
  Future<TutorialCatalog> getTutorials() async {
    try {
      final list = await _remote.fetchTutorials();
      final fetchedAt = _now();
      await _prefs.setString(
        cacheKey,
        jsonEncode({
          'fetchedAt': fetchedAt.toIso8601String(),
          'items': list.map((t) => t.toJson()).toList(),
        }),
      );
      return TutorialCatalog(tutorials: list, fetchedAt: fetchedAt);
    } catch (e) {
      final cached = _readCache();
      if (cached == null) rethrow;
      dev.log('TutorialRepository: server unreachable, cached list: $e');
      return cached;
    }
  }

  TutorialCatalog? _readCache() {
    final raw = _prefs.getString(cacheKey);
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final items = (json['items'] as List<dynamic>)
          .map((e) => Tutorial.fromJson(e as Map<String, dynamic>))
          .toList();
      return TutorialCatalog(
        tutorials: items,
        fromCache: true,
        fetchedAt: DateTime.tryParse(json['fetchedAt'] as String? ?? ''),
      );
    } catch (e) {
      dev.log('TutorialRepository: unreadable cache ignored: $e');
      return null;
    }
  }
}

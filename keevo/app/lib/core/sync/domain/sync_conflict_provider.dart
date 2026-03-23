import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../features/auth/presentation/provider/auth_provider.dart';
import 'sync_conflict.dart';

part 'sync_conflict_provider.g.dart';

/// Provider that fetches sync conflicts from GET /api/v1/sync/conflicts.
/// OWNER-only endpoint — returns empty list if unauthorized.
@riverpod
Future<List<SyncConflict>> syncConflicts(SyncConflictsRef ref) async {
  final dio = ref.watch(dioProvider);
  final response = await dio.get(
    '/api/v1/sync/conflicts',
    queryParameters: {'limit': 50, 'offset': 0},
  );
  final list = (response.data['data'] as List).cast<Map<String, dynamic>>();
  return list.map(SyncConflict.fromJson).toList();
}

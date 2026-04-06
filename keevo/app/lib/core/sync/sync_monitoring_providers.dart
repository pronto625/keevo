import 'package:drift/drift.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../di/providers.dart';
import '../storage/app_database.dart';

part 'sync_monitoring_providers.g.dart';

/// Pending sync operations count — reads from local sync_queue.
@riverpod
Future<int> pendingSyncCount(PendingSyncCountRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  final count = await db.customSelect(
    'SELECT COUNT(*) AS c FROM sync_queue WHERE synced = 0',
  ).getSingle();
  return count.read<int>('c');
}

/// Pending sync operations list — for queue tab display.
@riverpod
Future<List<SyncQueueData>> pendingSyncQueue(PendingSyncQueueRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  return (db.select(db.syncQueue)
        ..where((t) => t.synced.equals(false))
        ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
      .get();
}

/// Local sync event history — for history tab display.
@riverpod
Future<List<SyncEvent>> syncHistory(SyncHistoryRef ref) async {
  final logger = ref.watch(syncEventLoggerProvider);
  return logger.getHistory(limit: 20);
}

/// Active devices — from backend GET /api/v1/sync/devices.
@riverpod
Future<List<Map<String, dynamic>>> activeDevices(ActiveDevicesRef ref) async {
  try {
    final dio = ref.watch(dioProvider);
    final response = await dio.get('/api/v1/sync/devices');
    final list = (response.data['data'] as List).cast<Map<String, dynamic>>();
    return list;
  } catch (_) {
    return []; // Offline or error → empty list
  }
}

import 'package:drift/drift.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../di/providers.dart';
import '../storage/app_database.dart';

part 'sync_monitoring_providers.g.dart';

/// Pending sync operations count — reads from local sync_queue.
/// Quarantined operations (v1s-16-15) are not pending.
@riverpod
Future<int> pendingSyncCount(PendingSyncCountRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  final count = await db.customSelect(
    'SELECT COUNT(*) AS c FROM sync_queue '
    'WHERE synced = 0 AND quarantined_at IS NULL',
  ).getSingle();
  return count.read<int>('c');
}

/// Pending sync operations list — for queue tab display.
@riverpod
Future<List<SyncQueueData>> pendingSyncQueue(PendingSyncQueueRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  return (db.select(db.syncQueue)
        ..where((t) => t.synced.equals(false) & t.quarantinedAt.isNull())
        ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
      .get();
}

/// Quarantined ("bloquées") operations (v1s-16-15): money operations
/// refused for good, oldest first.
@riverpod
Future<List<SyncQueueData>> quarantinedSyncQueue(
    QuarantinedSyncQueueRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  return (db.select(db.syncQueue)
        ..where((t) => t.synced.equals(false) & t.quarantinedAt.isNotNull())
        ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
      .get();
}

/// Number of quarantined chains (v1s-16-15): a sale's chained validation /
/// cancellation counts with the sale, as in the "Bloquées" list.
@riverpod
Future<int> quarantinedSyncCount(QuarantinedSyncCountRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  final count = await db.customSelect(
    'SELECT COUNT(*) AS c FROM sync_queue q '
    'WHERE q.synced = 0 AND q.quarantined_at IS NOT NULL '
    "AND NOT (q.operation IN ('VALIDATE_SALE', 'CANCEL_SALE') AND EXISTS ("
    'SELECT 1 FROM sync_queue s WHERE s.synced = 0 '
    "AND s.quarantined_at IS NOT NULL AND s.operation = 'CREATE_SALE' "
    'AND s.entity_id = q.entity_id))',
  ).getSingle();
  return count.read<int>('c');
}

/// Local sync event history — for history tab display.
@riverpod
Future<List<SyncEvent>> syncHistory(SyncHistoryRef ref) async {
  final logger = ref.watch(syncEventLoggerProvider);
  return logger.getHistory(limit: 20);
}

/// J24: a device counts as active when seen within this window (the device
/// id is renewed on every account switch, so older ids pile up).
const kActiveDeviceWindow = Duration(days: 30);

/// J24: keeps the devices seen (push or update) within [kActiveDeviceWindow]
/// — also filters an older server that still lists every device id.
List<Map<String, dynamic>> devicesSeenRecently(
    List<Map<String, dynamic>> devices, DateTime now) {
  final since = now.subtract(kActiveDeviceWindow);
  DateTime? parse(Object? v) =>
      v is String && v.isNotEmpty ? DateTime.tryParse(v) : null;
  return devices.where((d) {
    final seen = [parse(d['lastPushAt']), parse(d['updatedAt'])]
        .whereType<DateTime>();
    return seen.any((t) => t.isAfter(since));
  }).toList();
}

/// Active devices — from backend GET /api/v1/sync/devices.
@riverpod
Future<List<Map<String, dynamic>>> activeDevices(ActiveDevicesRef ref) async {
  try {
    final dio = ref.watch(dioProvider);
    final response = await dio.get('/api/v1/sync/devices');
    final list = (response.data['data'] as List).cast<Map<String, dynamic>>();
    return devicesSeenRecently(list, DateTime.now());
  } catch (_) {
    return []; // Offline or error → empty list
  }
}

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// v1s-16-15: the real queue providers split pending and quarantined rows,
/// and the blocked count counts chains (a sale with its chained validation
/// is one).
void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late ProviderContainer container;

  setUp(() {
    db = AppDatabase.forTesting();
    container = ProviderContainer(
        overrides: [appDatabaseProvider.overrideWithValue(db)]);
  });

  tearDown(() async {
    container.dispose();
    await db.close();
  });

  var clock = DateTime(2026, 10, 1);
  Future<void> op(String id, String operation,
      {String? entityId, bool quarantined = false, bool synced = false}) {
    clock = clock.add(const Duration(seconds: 1));
    return db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: id,
          operation: operation,
          payload: '{}',
          createdAt: clock,
          synced: Value(synced),
          entityId: Value(entityId),
          quarantinedAt: Value(quarantined ? clock : null),
        ));
  }

  test('pending and quarantined rows are counted and listed apart', () async {
    await op('pending-1', 'UPDATE_CLIENT', entityId: 'c-1');
    await op('pending-2', 'CREATE_SALE', entityId: 'sale-9');
    await op('synced', 'UPDATE_CLIENT', entityId: 'c-2', synced: true);
    await op('sale', 'CREATE_SALE', entityId: 'sale-1', quarantined: true);
    await op('validate', 'VALIDATE_SALE', entityId: 'sale-1', quarantined: true);
    await op('adjust', 'STOCK_ADJUST', entityId: 'm-1', quarantined: true);
    // A validation quarantined alone (its sale was applied) is its own chain.
    await op('lone-cancel', 'CANCEL_SALE', entityId: 'sale-2', quarantined: true);

    expect(await container.read(pendingSyncCountProvider.future), 2);
    expect(
        (await container.read(pendingSyncQueueProvider.future)).map((o) => o.id),
        ['pending-1', 'pending-2']);
    expect(
        (await container.read(quarantinedSyncQueueProvider.future))
            .map((o) => o.id),
        ['sale', 'validate', 'adjust', 'lone-cancel']);
    expect(await container.read(quarantinedSyncCountProvider.future), 3);
  });

  // J24 (device test): « 18 appareils actifs » for one phone.
  test('devicesSeenRecently keeps devices seen in the last 30 days', () {
    final now = DateTime.utc(2026, 10, 14);
    final kept = devicesSeenRecently([
      {'deviceId': 'old', 'lastPushAt': '2026-08-01T10:00:00Z',
       'updatedAt': '2026-08-01T10:00:00Z'},
      {'deviceId': 'pushed', 'lastPushAt': '2026-10-10T10:00:00Z',
       'updatedAt': '2026-08-01T10:00:00Z'},
      {'deviceId': 'updated', 'lastPushAt': '', 'updatedAt': '2026-10-13T10:00:00Z'},
      {'deviceId': 'broken', 'lastPushAt': '', 'updatedAt': 'n/a'},
    ], now);

    expect(kept.map((d) => d['deviceId']), ['pushed', 'updated']);
  });
}

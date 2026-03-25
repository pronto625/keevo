import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
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

/// Story 5.5 — Task 7.2: Pending queue provider logic tests.
/// Tests the database queries used by pendingSyncCountProvider / pendingSyncQueueProvider.
void main() {
  late AppDatabase db;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  setUp(() {
    db = AppDatabase.forTesting();
  });

  tearDown(() async {
    await db.close();
  });

  group('Pending sync queue queries', () {
    test('empty queue returns 0 pending count', () async {
      final count = await (db.selectOnly(db.syncQueue)
            ..addColumns([db.syncQueue.id.count()])
            ..where(db.syncQueue.synced.equals(false)))
          .map((row) => row.read(db.syncQueue.id.count()))
          .getSingle();

      expect(count, 0);
    });

    test('pending ops are counted correctly', () async {
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-1',
            operation: 'CREATE_PRODUCT',
            payload: '{"name":"test"}',
            createdAt: DateTime.now(),
          ));
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-2',
            operation: 'UPDATE_STOCK',
            payload: '{"qty":10}',
            createdAt: DateTime.now(),
          ));
      // Synced op — should NOT be counted
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-3',
            operation: 'CREATE_SALE',
            payload: '{}',
            createdAt: DateTime.now(),
            synced: const Value(true),
          ));

      final count = await (db.selectOnly(db.syncQueue)
            ..addColumns([db.syncQueue.id.count()])
            ..where(db.syncQueue.synced.equals(false)))
          .map((row) => row.read(db.syncQueue.id.count()))
          .getSingle();

      expect(count, 2);
    });

    test('pending queue returns ops ordered by createdAt ASC', () async {
      final now = DateTime.now();
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-b',
            operation: 'UPDATE_STOCK',
            payload: '{}',
            createdAt: now.add(const Duration(seconds: 1)),
          ));
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-a',
            operation: 'CREATE_PRODUCT',
            payload: '{}',
            createdAt: now,
          ));

      final ops = await (db.select(db.syncQueue)
            ..where((t) => t.synced.equals(false))
            ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
          .get();

      expect(ops, hasLength(2));
      expect(ops[0].id, 'op-a');
      expect(ops[1].id, 'op-b');
    });

    test('synced ops are excluded from pending queue', () async {
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'op-synced',
            operation: 'CREATE_PRODUCT',
            payload: '{}',
            createdAt: DateTime.now(),
            synced: const Value(true),
          ));

      final ops = await (db.select(db.syncQueue)
            ..where((t) => t.synced.equals(false)))
          .get();

      expect(ops, isEmpty);
    });
  });
}

import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class _MockDio extends Mock implements Dio {}

class _MockRemoteProducts extends Mock implements RemoteProductDataSource {}

/// v1s-16-15 follow-up — a device at schema 27 (with operations already
/// queued) upgrades: `quarantined_at` is added, existing rows stay pending
/// (not quarantined) and the queue still reads and counts them.
void main() {
  setUpAll(() {
    if (!Platform.isLinux) return;
    open.overrideFor(OperatingSystem.linux, () {
      try {
        return DynamicLibrary.open('libsqlite3.so');
      } catch (_) {
        return DynamicLibrary.open('libsqlite3.so.0');
      }
    });
  });

  test('v27 → v28 adds quarantined_at; queued rows stay pending', () async {
    final db = AppDatabase.forTestingWith(NativeDatabase.memory(setup: (raw) {
      // sync_queue exactly as schema 27 created it.
      raw.execute('CREATE TABLE sync_queue (id TEXT NOT NULL, '
          'operation TEXT NOT NULL, payload TEXT NOT NULL, '
          'created_at TEXT NOT NULL, '
          'synced INTEGER NOT NULL DEFAULT 0 CHECK (synced IN (0, 1)), '
          'retry_count INTEGER NOT NULL DEFAULT 0, '
          'last_attempt_at TEXT NULL, entity_id TEXT NULL, '
          'last_error TEXT NULL, PRIMARY KEY (id))');
      // audit_entries as schema 27/28 (the v29 step alters it).
      raw.execute('CREATE TABLE audit_entries (id TEXT NOT NULL, '
          'user_id TEXT NOT NULL, entity_type TEXT NOT NULL, '
          'entity_id TEXT NOT NULL, action TEXT NOT NULL, '
          'value_before TEXT NULL, value_after TEXT NULL, '
          'occurred_at TEXT NOT NULL, PRIMARY KEY (id))');
      raw.execute("INSERT INTO sync_queue (id, operation, payload, created_at, "
          "synced, retry_count, entity_id, last_error) VALUES "
          "('op-sale', 'CREATE_SALE', '{\"storeId\":\"s1\"}', "
          "'2026-10-01T10:00:00.000', 0, 3, 'sale-1', 'INSUFFICIENT_STOCK'), "
          "('op-done', 'UPDATE_CLIENT', '{}', '2026-10-01T09:00:00.000', 1, 0, "
          "'c-1', NULL)");
      // The later v30 step adds sales.mobile_money_ref.
      raw.execute('CREATE TABLE sales (id TEXT NOT NULL PRIMARY KEY)');
      raw.userVersion = 27;
    }));
    addTearDown(db.close);

    final columns = (await db
            .customSelect("SELECT name FROM pragma_table_info('sync_queue')")
            .get())
        .map((r) => r.read<String>('name'))
        .toSet();
    expect(columns, contains('quarantined_at'));

    final rows = await db.select(db.syncQueue).get();
    final sale = rows.singleWhere((r) => r.id == 'op-sale');
    expect(sale.quarantinedAt, isNull);
    expect(sale.retryCount, 3);
    expect(sale.lastError, 'INSUFFICIENT_STOCK');
    expect(sale.createdAt.toUtc(), DateTime.utc(2026, 10, 1, 10));

    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    final sync = RestSyncService(
      database: db,
      remoteProducts: _MockRemoteProducts(),
      dio: _MockDio(),
      secureStorage: const FlutterSecureStorage(),
      prefs: await SharedPreferences.getInstance(),
    );
    expect(await sync.hasPendingOperations(), true);
    expect(await sync.quarantinedOperationCount(), 0);
  });
}

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/auto_closure_notification_checker.dart';
import 'package:shared_preferences/shared_preferences.dart';
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

/// Seeds a minimal DayClosures row into the test DB.
Future<void> _insertClosure(
  AppDatabase db, {
  required String id,
  required String storeId,
  required bool isAutomatic,
}) async {
  await db.customStatement(
    'INSERT INTO day_closures '
    '(id, store_id, actor_id, closed_at, total_sales, total_revenue, '
    'cash_amount, momo_amount, pending_sales_count, pending_sales_total, '
    'is_automatic, synced, created_at) '
    'VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, ?, 1, ?)',
    [
      id,
      storeId,
      isAutomatic ? 'SYSTEM' : 'user-1',
      DateTime(2026, 3, 20, 20, 0).toIso8601String(),
      isAutomatic ? 1 : 0,
      DateTime(2026, 3, 20, 20, 0).toIso8601String(),
    ],
  );
}

/// Seeds a minimal Stores row so storeName resolves correctly.
Future<void> _insertStore(
  AppDatabase db, {
  required String id,
  required String name,
}) async {
  await db.customStatement(
    'INSERT INTO stores '
    '(id, name, tenant_id, type, is_active, created_at, updated_at) '
    'VALUES (?, ?, ?, ?, 1, ?, ?)',
    [
      id,
      name,
      'tenant-test',
      'STORE',
      DateTime(2026, 1, 1).toIso8601String(),
      DateTime(2026, 1, 1).toIso8601String(),
    ],
  );
}

void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  late AppDatabase db;
  late SharedPreferences prefs;
  late AutoClosureNotificationChecker checker;

  setUp(() async {
    db = AppDatabase.forTesting();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    checker = AutoClosureNotificationChecker(db: db, prefs: prefs);
  });

  tearDown(() async {
    await db.close();
  });

  group('getUnnotified', () {
    test('returns empty list when no closures exist', () async {
      final result = await checker.getUnnotified();
      expect(result, isEmpty);
    });

    test('ignores manual (non-automatic) closures', () async {
      await _insertStore(db, id: 'store-1', name: 'Boutique A');
      await _insertClosure(db, id: 'c-1', storeId: 'store-1', isAutomatic: false);

      final result = await checker.getUnnotified();
      expect(result, isEmpty);
    });

    test('returns unnotified automatic closures', () async {
      await _insertStore(db, id: 'store-1', name: 'Boutique A');
      await _insertClosure(db, id: 'c-auto-1', storeId: 'store-1', isAutomatic: true);

      final result = await checker.getUnnotified();
      expect(result, hasLength(1));
      expect(result.first.id, 'c-auto-1');
      expect(result.first.storeName, 'Boutique A');
    });

    test('falls back to storeId when store not found', () async {
      await _insertClosure(db, id: 'c-auto-2', storeId: 'unknown-store', isAutomatic: true);

      final result = await checker.getUnnotified();
      expect(result, hasLength(1));
      expect(result.first.storeName, 'unknown-store');
    });

    test('skips already‑notified closures', () async {
      await _insertStore(db, id: 'store-2', name: 'Boutique B');
      await _insertClosure(db, id: 'c-notified', storeId: 'store-2', isAutomatic: true);

      await prefs.setStringList(kAutoClosureNotifiedIds, ['c-notified']);

      final result = await checker.getUnnotified();
      expect(result, isEmpty);
    });

    test('returns only the new closure when one of two is already notified', () async {
      await _insertStore(db, id: 'store-3', name: 'Boutique C');
      await _insertClosure(db, id: 'c-old', storeId: 'store-3', isAutomatic: true);
      await _insertClosure(db, id: 'c-new', storeId: 'store-3', isAutomatic: true);

      await prefs.setStringList(kAutoClosureNotifiedIds, ['c-old']);

      final result = await checker.getUnnotified();
      expect(result.map((r) => r.id), containsAll(['c-new']));
      expect(result.map((r) => r.id), isNot(contains('c-old')));
    });
  });

  group('markNotified', () {
    test('persists ids to SharedPreferences', () async {
      await checker.markNotified(['id-1', 'id-2']);
      final stored = prefs.getStringList(kAutoClosureNotifiedIds);
      expect(stored, containsAll(['id-1', 'id-2']));
    });

    test('appends to existing notified set without duplicates', () async {
      await prefs.setStringList(kAutoClosureNotifiedIds, ['id-0']);
      await checker.markNotified(['id-1']);
      final stored = prefs.getStringList(kAutoClosureNotifiedIds);
      expect(stored, containsAll(['id-0', 'id-1']));
    });

    test('does nothing when called with empty list', () async {
      await checker.markNotified([]);
      expect(prefs.getStringList(kAutoClosureNotifiedIds), equals(null));
    });

    test('caps stored list at 200 entries', () async {
      final existing = List.generate(200, (i) => 'old-$i');
      await prefs.setStringList(kAutoClosureNotifiedIds, existing);

      await checker.markNotified(['new-entry']);

      final stored = prefs.getStringList(kAutoClosureNotifiedIds)!;
      expect(stored.length, 200);
      expect(stored.last, 'new-entry');
    });
  });
}

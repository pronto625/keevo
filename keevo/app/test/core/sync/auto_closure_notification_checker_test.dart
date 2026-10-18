import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
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

/// Fixed clock for the checker: closures default to 2 h before it.
final _now = DateTime(2026, 10, 18, 9, 0);

/// Seeds a minimal DayClosures row into the test DB.
Future<void> _insertClosure(
  AppDatabase db, {
  required String id,
  required String storeId,
  required bool isAutomatic,
  DateTime? closedAt,
}) async {
  final at = closedAt ?? _now.subtract(const Duration(hours: 2));
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
      at.toUtc().toIso8601String(),
      isAutomatic ? 1 : 0,
      at.toUtc().toIso8601String(),
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
  setUpAll(() async {
    _overrideSqlite3ForLinuxTesting();
    await initializeDateFormatting('fr_FR');
  });

  late AppDatabase db;
  late SharedPreferences prefs;
  late AutoClosureNotificationChecker checker;

  setUp(() async {
    db = AppDatabase.forTesting();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    checker =
        AutoClosureNotificationChecker(db: db, prefs: prefs, clock: () => _now);
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
      await _insertClosure(db,
          id: 'c-1', storeId: 'store-1', isAutomatic: false);

      final result = await checker.getUnnotified();
      expect(result, isEmpty);
    });

    test('returns unnotified automatic closures', () async {
      await _insertStore(db, id: 'store-1', name: 'Boutique A');
      await _insertClosure(db,
          id: 'c-auto-1', storeId: 'store-1', isAutomatic: true);

      final result = await checker.getUnnotified();
      expect(result, hasLength(1));
      expect(result.first.id, 'c-auto-1');
      expect(result.first.storeName, 'Boutique A');
    });

    // NFR35: never the raw id — generic label.
    test('falls back to a generic label when store not found', () async {
      await _insertClosure(db,
          id: 'c-auto-2', storeId: 'unknown-store', isAutomatic: true);

      final result = await checker.getUnnotified();
      expect(result, hasLength(1));
      expect(result.first.storeName, 'Boutique');
    });

    test('skips already‑notified closures', () async {
      await _insertStore(db, id: 'store-2', name: 'Boutique B');
      await _insertClosure(db,
          id: 'c-notified', storeId: 'store-2', isAutomatic: true);

      await prefs.setStringList(kAutoClosureNotifiedIds, ['c-notified']);

      final result = await checker.getUnnotified();
      expect(result, isEmpty);
    });

    test('returns only the new closure when one of two is already notified',
        () async {
      await _insertStore(db, id: 'store-3', name: 'Boutique C');
      await _insertClosure(db,
          id: 'c-old', storeId: 'store-3', isAutomatic: true);
      await _insertClosure(db,
          id: 'c-new', storeId: 'store-3', isAutomatic: true);

      await prefs.setStringList(kAutoClosureNotifiedIds, ['c-old']);

      final result = await checker.getUnnotified();
      expect(result.map((r) => r.id), containsAll(['c-new']));
      expect(result.map((r) => r.id), isNot(contains('c-old')));
    });
  });

  group('markNotified', () {
    setUp(() async {
      for (final id in ['id-0', 'id-1', 'id-2']) {
        await _insertClosure(db, id: id, storeId: 'store-1', isAutomatic: true);
      }
    });

    test('persists ids to SharedPreferences', () async {
      await checker.markNotified(['id-1', 'id-2']);
      final stored = prefs.getStringList(kAutoClosureNotifiedIds);
      expect(stored, containsAll(['id-1', 'id-2']));
    });

    test('appends to existing notified set without duplicates', () async {
      await prefs.setStringList(kAutoClosureNotifiedIds, ['id-0']);
      await checker.markNotified(['id-1', 'id-1']);
      final stored = prefs.getStringList(kAutoClosureNotifiedIds);
      expect(stored, unorderedEquals(['id-0', 'id-1']));
    });

    test('does nothing when called with empty list and nothing stored',
        () async {
      await checker.markNotified([]);
      expect(prefs.getStringList(kAutoClosureNotifiedIds), equals(null));
    });

    test('prunes ids outside the window or unknown locally', () async {
      await _insertClosure(db,
          id: 'old',
          storeId: 'store-1',
          isAutomatic: true,
          closedAt: _now.subtract(const Duration(days: 10)));
      await prefs
          .setStringList(kAutoClosureNotifiedIds, ['old', 'gone', 'id-0']);

      await checker.markNotified([]);

      expect(prefs.getStringList(kAutoClosureNotifiedIds), ['id-0']);
    });

    test('stays bounded however many historic closures exist', () async {
      for (var i = 0; i < 300; i++) {
        await _insertClosure(db,
            id: 'h-$i',
            storeId: 'store-1',
            isAutomatic: true,
            closedAt: _now.subtract(Duration(days: 3 + i)));
      }
      await prefs.setStringList(
          kAutoClosureNotifiedIds, List.generate(300, (i) => 'h-$i'));

      await checker.markNotified(['id-0']);

      expect(prefs.getStringList(kAutoClosureNotifiedIds), ['id-0']);
    });
  });

  // Prod bug: fresh install + first login → one notice per historic
  // automatic closure pulled from the server (5 août, 11 août, …).
  group('notice window (48 h)', () {
    setUp(() async {
      await _insertStore(db, id: 'store-1', name: 'Boutique A');
    });

    test('historic closures pulled on a fresh install are never returned',
        () async {
      for (final d in [5, 11, 30, 60]) {
        await _insertClosure(db,
            id: 'h-$d',
            storeId: 'store-1',
            isAutomatic: true,
            closedAt: _now.subtract(Duration(days: d)));
      }
      expect(await checker.getUnnotified(), isEmpty);
    });

    test('a closure just past 48 h is ignored, one just inside is kept',
        () async {
      await _insertClosure(db,
          id: 'out',
          storeId: 'store-1',
          isAutomatic: true,
          closedAt: _now.subtract(const Duration(hours: 48, minutes: 1)));
      await _insertClosure(db,
          id: 'in',
          storeId: 'store-1',
          isAutomatic: true,
          closedAt: _now.subtract(const Duration(hours: 47)));

      final result = await checker.getUnnotified();
      expect(result.map((c) => c.id), ['in']);
    });

    test('recent closures come back oldest first', () async {
      await _insertClosure(db,
          id: 'late',
          storeId: 'store-1',
          isAutomatic: true,
          closedAt: _now.subtract(const Duration(hours: 1)));
      await _insertClosure(db,
          id: 'early',
          storeId: 'store-1',
          isAutomatic: true,
          closedAt: _now.subtract(const Duration(hours: 30)));

      final result = await checker.getUnnotified();
      expect(result.map((c) => c.id), ['early', 'late']);
    });
  });

  group('autoClosureNoticeMessage', () {
    UnnotifiedClosure c(String store, DateTime at) =>
        UnnotifiedClosure(id: 'x-$store-$at', storeName: store, closedAt: at);

    test('single closure: store and French date', () {
      expect(
        autoClosureNoticeMessage([c('Boutique A', DateTime(2026, 8, 5, 20))]),
        'Clôture automatique\u202f: Boutique A — 5 août 2026',
      );
    });

    test('several closures of one store: one grouped line', () {
      expect(
        autoClosureNoticeMessage([
          c('Boutique A', DateTime(2026, 10, 16, 20)),
          c('Boutique A', DateTime(2026, 10, 17, 20)),
        ]),
        '2 journées clôturées automatiquement — Boutique A',
      );
    });

    test('several stores: one grouped line with the store count', () {
      expect(
        autoClosureNoticeMessage([
          c('Boutique A', DateTime(2026, 10, 17, 20)),
          c('Dépôt B', DateTime(2026, 10, 17, 20)),
          c('Dépôt B', DateTime(2026, 10, 16, 20)),
        ]),
        '3 journées clôturées automatiquement (2 boutiques)',
      );
    });
  });

  // J21 (device test): an EMPLOYEE of another store saw « Clôture
  // automatique : Dépôt Bonaberi ».
  group('J21 — store scoping', () {
    setUp(() async {
      await _insertStore(db, id: 'own', name: 'Boutique Akwa');
      await _insertStore(db, id: 'depot', name: 'Dépôt Bonaberi');
      await _insertClosure(db, id: 'c-own', storeId: 'own', isAutomatic: true);
      await _insertClosure(db,
          id: 'c-depot', storeId: 'depot', isAutomatic: true);
    });

    test('employee: only their own store', () async {
      final result = await checker.getUnnotified(onlyStoreId: 'own');
      expect(result.map((c) => c.storeName), ['Boutique Akwa']);
    });

    test('owner: every store', () async {
      final result = await checker.getUnnotified();
      expect(result.map((c) => c.id).toSet(), {'c-own', 'c-depot'});
    });

    test('unknown store never shows its id', () async {
      await _insertClosure(db,
          id: 'c-x', storeId: 'b1c2-uuid', isAutomatic: true);
      final result = await checker.getUnnotified(onlyStoreId: 'b1c2-uuid');
      expect(result.single.storeName, 'Boutique');
    });
  });
}

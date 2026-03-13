import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
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

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    _overrideSqlite3ForLinuxTesting();
  });

  group('Stores Drift table — schema v7 (Story 3.1 — Task 15)', () {
    late AppDatabase db;

    setUp(() => db = AppDatabase.forTesting());
    tearDown(() async => db.close());

    test('can insert a store with type, address, and phone', () async {
      final now = DateTime(2025, 1, 1, 10, 0);
      await db.into(db.stores).insert(StoresCompanion(
            id: const Value('store-1'),
            name: const Value('Boutique Test'),
            tenantId: const Value('kv_test'),
            type: const Value('STORE'),
            address: const Value('123 Rue Test'),
            phone: const Value('+237600000001'),
            isActive: const Value(true),
            createdAt: Value(now),
            updatedAt: Value(now),
          ));

      final rows = await db.select(db.stores).get();
      expect(rows.length, 1);
      expect(rows.first.type, 'STORE');
      expect(rows.first.address, '123 Rue Test');
      expect(rows.first.phone, '+237600000001');
    });

    test('can insert a WAREHOUSE type store', () async {
      final now = DateTime(2025, 1, 1, 10, 0);
      await db.into(db.stores).insert(StoresCompanion(
            id: const Value('wh-1'),
            name: const Value('Entrepôt'),
            tenantId: const Value('kv_test'),
            type: const Value('WAREHOUSE'),
            createdAt: Value(now),
            updatedAt: Value(now),
          ));

      final rows = await db.select(db.stores).get();
      expect(rows.first.type, 'WAREHOUSE');
    });

    test('address and phone default to null when not supplied', () async {
      final now = DateTime(2025, 1, 1, 10, 0);
      await db.into(db.stores).insert(StoresCompanion(
            id: const Value('store-2'),
            name: const Value('Boutique'),
            tenantId: const Value('kv_test'),
            createdAt: Value(now),
            updatedAt: Value(now),
          ));

      final rows = await db.select(db.stores).get();
      expect(rows.first.address, equals(null));
      expect(rows.first.phone, equals(null));
    });
  });
}



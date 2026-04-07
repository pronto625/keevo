import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull;
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

  group('Drift migration v21 — users.firstName column (Story 7.1)', () {
    late AppDatabase db;

    setUp(() {
      db = AppDatabase.forTesting();
    });

    tearDown(() async => db.close());

    test('schemaVersion is 23', () {
      expect(db.schemaVersion, 23);
    });

    test('users table accepts nullable firstName', () async {
      await db.into(db.users).insert(UsersCompanion.insert(
            id: 'user-test-001',
            phoneNumber: '+237600000001',
            tenantId: 'tenant-001',
            role: 'OWNER',
            firstName: const Value('Amadou'),
            createdAt: DateTime.now(),
          ));

      final row = await (db.select(db.users)
            ..where((u) => u.id.equals('user-test-001')))
          .getSingle();
      expect(row.firstName, 'Amadou');
    });

    test('users.firstName defaults to null when not set', () async {
      await db.into(db.users).insert(UsersCompanion.insert(
            id: 'user-test-002',
            phoneNumber: '+237600000002',
            tenantId: 'tenant-001',
            role: 'EMPLOYEE',
            createdAt: DateTime.now(),
          ));

      final row = await (db.select(db.users)
            ..where((u) => u.id.equals('user-test-002')))
          .getSingle();
      expect(row.firstName, isNull);
    });
  });
}

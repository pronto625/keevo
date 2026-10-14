import 'dart:ffi';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/audit/data/datasource/local_audit_datasource.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:sqlite3/open.dart';

/// v1s-18-7 — a device at schema 28 upgrades to 29: the five audit
/// enrichment columns are added and legacy rows still read back.
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

  test('v28 → v29 adds the audit columns, legacy row readable', () async {
    final db = AppDatabase.forTestingWith(NativeDatabase.memory(setup: (raw) {
      // audit_entries exactly as schema 28 created it, plus the tables the
      // local audit read joins on.
      raw.execute('CREATE TABLE audit_entries (id TEXT NOT NULL, '
          'user_id TEXT NOT NULL, entity_type TEXT NOT NULL, '
          'entity_id TEXT NOT NULL, action TEXT NOT NULL, '
          'value_before TEXT NULL, value_after TEXT NULL, '
          'occurred_at TEXT NOT NULL, PRIMARY KEY (id))');
      raw.execute('CREATE TABLE stores (id TEXT NOT NULL PRIMARY KEY, '
          'name TEXT NOT NULL)');
      raw.execute('CREATE TABLE employees (id TEXT NOT NULL PRIMARY KEY, '
          'user_id TEXT NOT NULL, first_name TEXT NOT NULL, '
          'last_name TEXT NOT NULL)');
      raw.execute("INSERT INTO audit_entries VALUES ('legacy', 'u-1', "
          "'Product', 'p-1', 'PRODUCT_CREATED', NULL, "
          "'{\"productName\":\"Savon\"}', '2026-10-05T10:00:00.000')");
      // The later v30 step adds sales.mobile_money_ref.
      raw.execute('CREATE TABLE sales (id TEXT NOT NULL PRIMARY KEY)');
      raw.userVersion = 28;
    }));
    addTearDown(db.close);

    final columns = (await db
            .customSelect("SELECT name FROM pragma_table_info('audit_entries')")
            .get())
        .map((r) => r.read<String>('name'))
        .toSet();
    expect(columns,
        containsAll(['store_id', 'entity_label', 'device_id', 'source', 'metadata']));

    final ds = LocalAuditDataSource(db);
    final slice = await ds.getAuditSlice(AuditFilter.empty);
    expect(slice.entries.single.id, 'legacy');
    final e = (await ds.getById('legacy'))!;
    expect(e.valueAfter, '{"productName":"Savon"}');
    expect(e.storeId, isNull);
    expect(e.entityLabel, isNull);
    expect(e.source, isNull);
    expect(e.metadata, isNull);
  });
}

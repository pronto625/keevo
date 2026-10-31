import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/audit/data/datasource/local_audit_datasource.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:sqlite3/open.dart';

/// v1s-18-7 — offline history: same filters as REST, offset paging, names
/// resolved from the local stores / employees tables.
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

  late AppDatabase db;
  late LocalAuditDataSource ds;

  setUp(() async {
    db = AppDatabase.forTesting();
    ds = LocalAuditDataSource(db);
    final now = DateTime(2026, 10, 1);
    await db.into(db.stores).insert(StoresCompanion.insert(
        id: 'store-1',
        name: 'Boutique Centre',
        tenantId: 't',
        createdAt: now,
        updatedAt: now));
    await db.into(db.employees).insert(EmployeesCompanion.insert(
        id: 'emp-1',
        userId: 'user-awa',
        firstName: 'Awa',
        lastName: 'Diallo',
        storeId: 'store-1',
        createdAt: now));
  });

  tearDown(() => db.close());

  Future<void> insert(
    String id, {
    required DateTime at,
    String userId = 'user-awa',
    String action = 'PRODUCT_CREATED',
    String entityType = 'Product',
    String entityId = 'p-1',
    String? storeId,
    String? label,
    String? source,
    String? metadata,
  }) =>
      db.customStatement(
        'INSERT INTO audit_entries (id, user_id, entity_type, entity_id, '
        'action, value_before, value_after, occurred_at, store_id, '
        'entity_label, device_id, source, metadata) '
        'VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, ?, ?)',
        [
          id,
          userId,
          entityType,
          entityId,
          action,
          '{}',
          db.typeMapping.mapToSqlVariable(at.toLocal()),
          storeId,
          label,
          source,
          metadata,
        ],
      );

  test('newest first, offset paging with local cursor, names resolved',
      () async {
    for (var i = 0; i < 5; i++) {
      await insert('a$i',
          at: DateTime(2026, 10, 10, 8, i), storeId: 'store-1', label: 'P$i');
    }

    final first = await ds.getAuditSlice(AuditFilter.empty, size: 2);
    expect(first.isOffline, isTrue);
    expect(first.entries.map((e) => e.id), ['a4', 'a3']);
    expect(first.nextCursor, '${kLocalAuditCursorPrefix}2');
    expect(first.entries.first.storeName, 'Boutique Centre');
    expect(first.entries.first.actorName, 'Awa Diallo');
    expect(first.entries.first.entityLabel, 'P4');

    final second = await ds.getAuditSlice(AuditFilter.empty,
        cursor: first.nextCursor, size: 2);
    expect(second.entries.map((e) => e.id), ['a2', 'a1']);
    final third = await ds.getAuditSlice(AuditFilter.empty,
        cursor: second.nextCursor, size: 2);
    expect(third.entries.map((e) => e.id), ['a0']);
    expect(third.nextCursor, isNull);
  });

  test('period, store, user, action, type and label filters', () async {
    await insert('in',
        at: DateTime(2026, 10, 5, 12), storeId: 'store-1', label: 'Pagne wax');
    await insert('before',
        at: DateTime(2026, 10, 1, 12), storeId: 'store-1', label: 'Pagne wax');
    await insert('other-store',
        at: DateTime(2026, 10, 5, 12), storeId: 'store-2', label: 'Pagne wax');
    await insert('other-user',
        at: DateTime(2026, 10, 5, 12),
        storeId: 'store-1',
        userId: 'user-x',
        label: 'Pagne wax');
    await insert('other-action',
        at: DateTime(2026, 10, 5, 12),
        storeId: 'store-1',
        action: 'PRODUCT_UPDATED',
        label: 'Pagne wax');
    await insert('other-label',
        at: DateTime(2026, 10, 5, 12), storeId: 'store-1', label: 'Savon');

    final slice = await ds.getAuditSlice(AuditFilter(
      from: DateTime(2026, 10, 4),
      to: DateTime(2026, 10, 6),
      storeId: 'store-1',
      userId: 'user-awa',
      action: 'PRODUCT_CREATED',
      entityType: 'Product',
      query: 'PAGNE',
    ));
    expect(slice.entries.map((e) => e.id), ['in']);
  });

  test('label search matches % literally', () async {
    await insert('promo', at: DateTime(2026, 10, 5), label: 'Remise 10% pagne');
    await insert('plain', at: DateTime(2026, 10, 6), label: 'Remise 100 FCFA');

    final slice = await ds.getAuditSlice(const AuditFilter(query: '10%'));
    expect(slice.entries.map((e) => e.id), ['promo']);
  });

  test('label search also matches the employee-name fallback', () async {
    await insert('emp', at: DateTime(2026, 10, 5),
        action: 'EMPLOYEE_ROLE_CHANGED', entityType: 'Employee',
        entityId: 'emp-1', userId: 'owner');
    await insert('other', at: DateTime(2026, 10, 6), label: 'Savon');

    final slice = await ds.getAuditSlice(const AuditFilter(query: 'diallo'));
    expect(slice.entries.map((e) => e.id), ['emp']);
  });

  test('Employee target without label gets the employee name', () async {
    await insert('emp',
        at: DateTime(2026, 10, 5),
        action: 'EMPLOYEE_ROLE_CHANGED',
        entityType: 'Employee',
        entityId: 'emp-1',
        userId: 'owner');
    await insert('gone',
        at: DateTime(2026, 10, 6),
        action: 'EMPLOYEE_ROLE_CHANGED',
        entityType: 'Employee',
        entityId: 'emp-x',
        userId: 'owner');

    final emp = await ds.getById('emp');
    expect(emp!.entityLabel, 'Awa Diallo');
    expect(emp.actorName, isNull, reason: 'owner has no employee row');
    expect((await ds.getById('gone'))!.entityLabel, isNull);
    expect(await ds.getById('missing'), isNull);
  });

  test('legacy row (null enrichment) lists and opens', () async {
    await db.customStatement(
      'INSERT INTO audit_entries (id, user_id, entity_type, entity_id, '
      'action, value_after, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      [
        'legacy',
        'user-awa',
        'Product',
        'p',
        'PRODUCT_CREATED',
        '{"productName":"x"}',
        db.typeMapping.mapToSqlVariable(DateTime(2026, 10, 5)),
      ],
    );
    final slice = await ds.getAuditSlice(AuditFilter.empty);
    expect(slice.entries.single.id, 'legacy');
    final e = await ds.getById('legacy');
    expect(e!.source, isNull);
    expect(e.metadata, isNull);
    expect(e.storeName, isNull);
  });

  test('metadata JSON text is kept as is', () async {
    await insert('m',
        at: DateTime(2026, 10, 5),
        source: 'SYNC',
        metadata: '{"justification":"x"}');
    final e = await ds.getById('m');
    expect(e!.metadata, '{"justification":"x"}');
    expect(e.source, 'SYNC');
  });
}

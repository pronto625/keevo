import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/profitability/data/datasource/local_profitability_datasource.dart';
import 'package:keevo/features/profitability/domain/model/seller_leaderboard_model.dart';
import 'package:sqlite3/open.dart';

String _d(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Drift's text format for a local DateTime: `2026-03-17T12:00:00.000 +01:00`.
String _local(DateTime d) {
  final o = d.timeZoneOffset;
  final sign = o.isNegative ? '-' : '+';
  final h = o.inHours.abs().toString().padLeft(2, '0');
  final m = (o.inMinutes.abs() % 60).toString().padLeft(2, '0');
  return '${_d(d)}T12:00:00.000 $sign$h:$m';
}

String _2(int v) => v.toString().padLeft(2, '0');

/// Drift text of a local wall-clock instant, with the device offset
/// (`2026-03-17T00:01:00.000 +01:00`).
String _localAt(DateTime d) {
  final o = d.timeZoneOffset;
  final sign = o.isNegative ? '-' : '+';
  return '${_d(d)}T${_2(d.hour)}:${_2(d.minute)}:00.000 '
      '$sign${_2(o.inHours.abs())}:${_2(o.inMinutes.abs() % 60)}';
}

/// UTC text (`…Z`) of a local instant, as written by rows synced in UTC.
String _utcZ(DateTime local) {
  final u = local.toUtc();
  return '${_d(u)}T${_2(u.hour)}:${_2(u.minute)}:00.000Z';
}

/// v1s-14-2 — offline seller leaderboard computed from Drift (mirror of the
/// backend SQL).
void main() {
  setUpAll(() {
    if (Platform.isLinux) {
      open.overrideFor(OperatingSystem.linux, () {
        try {
          return DynamicLibrary.open('libsqlite3.so');
        } catch (_) {
          return DynamicLibrary.open('libsqlite3.so.0');
        }
      });
    }
  });

  late AppDatabase db;
  setUp(() => db = AppDatabase.forTesting());
  tearDown(() => db.close());

  final today = DateTime.now();
  final day = DateTime(today.year, today.month, today.day);

  Future<void> sale(String id, String seller, String store, int amount,
      {String status = 'COMPLETED', DateTime? at, String? raw}) async {
    final ts = raw ?? _local(at ?? today);
    await db.customStatement(
      'INSERT INTO sales (id, store_id, employee_id, total_amount, '
      'payment_mode, status, occurred_at, created_at) VALUES '
      "(?, ?, ?, ?, 'CASH', ?, ?, ?)",
      [id, store, seller, amount, status, ts, ts],
    );
  }

  Future<void> employee(String userId, String first, String last, String store,
          {String status = 'ACTIVE'}) =>
      db.into(db.employees).insert(EmployeesCompanion.insert(
            id: 'e-$userId-$store',
            userId: userId,
            firstName: first,
            lastName: last,
            storeId: store,
            status: Value(status),
            createdAt: today,
          ));

  Future<void> user(String id, String role) =>
      db.into(db.users).insert(UsersCompanion.insert(
            id: id,
            phoneNumber: '+237600$id',
            tenantId: 't1',
            role: role,
            createdAt: today,
          ));

  Future<Map<String, SellerLeaderboardEntry>> load({String? storeId}) async {
    final rows = await LocalProfitabilityDatasource(db)
        .getSellerLeaderboard(from: day, to: day, storeId: storeId);
    return {for (final r in rows) r.sellerId: r};
  }

  test('only COMPLETED sales count; average basket is an integer division',
      () async {
    await employee('u1', 'Awa', 'Ndiaye', 's1');
    await sale('a', 'u1', 's1', 5000);
    await sale('b', 'u1', 's1', 2000);
    await sale('c', 'u1', 's1', 9000, status: 'CANCELLED');

    final rows = await load();
    expect(rows['u1']!.salesCount, 2);
    expect(rows['u1']!.totalRevenue, 7000);
    expect(rows['u1']!.averageBasket, 3500);
    expect(rows['u1']!.sellerName, 'Awa Ndiaye');
    expect(rows['u1']!.owner, isFalse);
  });

  test('sales outside the period are excluded', () async {
    await employee('u1', 'Awa', 'Ndiaye', 's1');
    await sale('old', 'u1', 's1', 5000,
        at: today.subtract(const Duration(days: 3)));

    final rows = await load();
    expect(rows['u1']!.salesCount, 0);
  });

  test('store filter: only that store sales and active employees', () async {
    await employee('u1', 'Awa', 'Ndiaye', 's1');
    await employee('u2', 'Bello', 'Diallo', 's2');
    await sale('a', 'u1', 's1', 3000);
    await sale('b', 'u1', 's2', 8000);
    await sale('c', 'u2', 's2', 2000);

    final scoped = await load(storeId: 's1');
    expect(scoped.keys, ['u1']);
    expect(scoped['u1']!.totalRevenue, 3000);

    final all = await load();
    expect(all['u1']!.totalRevenue, 11000);
    expect(all['u2']!.totalRevenue, 2000);
  });

  test('idle active employee → 0/0; inactive employee omitted', () async {
    await employee('u3', 'Chantal', 'Mbarga', 's1');
    await employee('u4', 'Denis', 'Fotso', 's1', status: 'INACTIVE');

    final rows = await load(storeId: 's1');
    expect(rows.keys, ['u3']);
    expect(rows['u3']!.salesCount, 0);
    expect(rows['u3']!.totalRevenue, 0);
    expect(rows['u3']!.averageBasket, 0);
  });

  test('owner → « Propriétaire »; unknown seller → « Vendeur inconnu »',
      () async {
    await user('owner-1', 'OWNER');
    await sale('a', 'owner-1', 's1', 6000);
    await sale('b', 'ghost', 's1', 1000);

    final rows = await load();
    expect(rows['owner-1']!.sellerName, 'Propriétaire');
    expect(rows['owner-1']!.owner, isTrue);
    expect(rows['ghost']!.sellerName, 'Vendeur inconnu');
    expect(rows['ghost']!.owner, isFalse);
  });

  test('no sale, no employee → empty', () async {
    expect(await load(), isEmpty);
  });

  test('day boundaries on from/to (local offset and UTC `Z` rows)', () async {
    final from = DateTime(2026, 3, 17);
    final to = DateTime(2026, 3, 18);
    await employee('u1', 'Awa', 'Ndiaye', 's1');
    // Around `from`.
    await sale('f-out', 'u1', 's1', 1,
        raw: _localAt(DateTime(2026, 3, 16, 23, 59)));
    await sale('f-in', 'u1', 's1', 10,
        raw: _localAt(DateTime(2026, 3, 17, 0, 1)));
    // Around `to`.
    await sale('t-in', 'u1', 's1', 100,
        raw: _localAt(DateTime(2026, 3, 18, 23, 59)));
    await sale('t-out', 'u1', 's1', 1000,
        raw: _localAt(DateTime(2026, 3, 19, 0, 1)));
    // UTC-stored rows: 00:30 local on `from` (in) and on to+1 (out).
    await sale('z-in', 'u1', 's1', 10000,
        raw: _utcZ(DateTime(2026, 3, 17, 0, 30)));
    await sale('z-out', 'u1', 's1', 100000,
        raw: _utcZ(DateTime(2026, 3, 19, 0, 30)));

    final rows = await LocalProfitabilityDatasource(db)
        .getSellerLeaderboard(from: from, to: to);

    expect(rows.single.salesCount, 3);
    expect(rows.single.totalRevenue, 10110);
  });
}

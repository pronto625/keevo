import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/pos/data/datasource/local_day_closure_datasource.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart' as domain;
import 'package:sqlite3/open.dart';

domain.DayClosure _closure(String id, DateTime at, {bool automatic = false}) =>
    domain.DayClosure(
      id: id,
      storeId: 'store-1',
      actorId: 'owner',
      closedAt: at,
      summary: const domain.DayClosureSummary(
        totalSales: 1,
        totalRevenue: 1000,
        cashAmount: 1000,
        momoAmount: 0,
        topProductQty: 1,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      ),
      isAutomatic: automatic,
      synced: false,
    );

void main() {
  late AppDatabase db;
  late LocalDayClosureDataSource ds;

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

  setUp(() {
    db = AppDatabase.forTesting();
    ds = LocalDayClosureDataSource(db);
  });

  tearDown(() => db.close());

  test(
      'an unsynced local closure is dropped once the server closure of the '
      'same day is pulled (server answered DUPLICATE)', () async {
    final day = DateTime(2026, 3, 17);
    // Offline manual closure at 21:00, server auto-closure at 20:00.
    await ds.insertClosure(_closure('local', day.add(const Duration(hours: 21))),
        synced: false);
    await ds.insertClosure(
        _closure('server', day.add(const Duration(hours: 20)), automatic: true),
        synced: true);

    final last = await ds.getLastClosure('store-1');

    expect(last!.id, 'server');
    final rows = await db.select(db.dayClosures).get();
    expect(rows.map((r) => r.id), ['server']);
  });

  test('an unsynced closure of another day is kept', () async {
    await ds.insertClosure(_closure('local', DateTime(2026, 3, 18, 21)),
        synced: false);
    await ds.insertClosure(_closure('server', DateTime(2026, 3, 17, 20)),
        synced: true);

    final last = await ds.getLastClosure('store-1');

    expect(last!.id, 'local');
    expect((await db.select(db.dayClosures).get()).length, 2);
  });
}

import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/local_stock_datasource.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';
import 'package:uuid/uuid.dart';

/// The low-stock threshold is product-wide (server: products.minimum_threshold):
/// setting it locally must take effect even in a store where the product has
/// no stock_levels row yet, and survive a pull that rewrites the row.
class _MockSyncService extends Mock implements SyncService {}

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
  late LocalStockDataSource sut;

  setUp(() async {
    db = AppDatabase.forTesting();
    sut = LocalStockDataSource(db);
    await db.into(db.products).insert(ProductsCompanion.insert(
          id: 'p1',
          name: 'Bissap',
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ));
    await db.into(db.stores).insert(StoresCompanion.insert(
          id: 's1',
          name: 'Boutique',
          tenantId: 't1',
          createdAt: DateTime(2026),
          updatedAt: DateTime(2026),
        ));
  });

  tearDown(() => db.close());

  test('updateThreshold on a product with no row stores it on the product',
      () async {
    await sut.updateThreshold('p1', 6);

    expect(await sut.getProductThreshold('p1'), 6);
    expect(await db.select(db.stockLevels).get(), isEmpty,
        reason: 'no phantom stock row is created');

    final availability = await sut.getLocalAvailability('p1');
    expect(availability.entries.single.quantity, 0);
    expect(availability.entries.single.minimumThreshold, 6);
  });

  test('a row created later (stock entry) uses the product threshold',
      () async {
    await sut.updateThreshold('p1', 6);
    await sut.upsertLevel(StockLevelModel(
      id: const Uuid().v4(),
      productId: 'p1',
      storeId: 's1',
      quantity: 4,
      updatedAt: DateTime(2026),
    ));

    final level = await sut.getLevelByStore('p1', 's1');
    expect(level!.minimumThreshold, 6);
    expect(level.isLow, isTrue);
    expect((await sut.getLevels('p1')).single.minimumThreshold, 6);

    final lowIds =
        await LocalProductDataSource(db, _MockSyncService())
            .getLowStockProductIds(storeId: 's1');
    expect(lowIds, contains('p1'));
  });

  test('the product threshold wins over a stale row value after a pull',
      () async {
    await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
          id: 'sl-1',
          productId: 'p1',
          storeId: 's1',
          quantity: 7,
          updatedAt: DateTime(2026),
        ));
    await sut.updateThreshold('p1', 8);
    // A pull rewrites the row with the server's stock_levels value (0).
    await (db.update(db.stockLevels)..where((l) => l.id.equals('sl-1')))
        .write(const StockLevelsCompanion(minimumThreshold: Value(0)));

    final level = await sut.getLevelByStore('p1', 's1');
    expect(level!.minimumThreshold, 8);
    expect(level.isLow, isTrue);
  });
}

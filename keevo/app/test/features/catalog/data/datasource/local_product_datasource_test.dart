import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
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

/// No-op [SyncService] stub — [LocalProductDataSource.search()] only uses
/// [_db], never calls [_syncService], so a stub suffices for AC2 tests.
class _NoOpSyncService implements SyncService {
  final AppDatabase _db;
  _NoOpSyncService(this._db);

  @override
  AppDatabase get db => _db;

  @override
  Future<List<Map<String, dynamic>>> push() async => [];

  @override
  Future<bool> hasPendingOperations() async => false;

  @override
  Future<void> pull() async {}

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  }) async {}
}

void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  late AppDatabase db;
  late LocalProductDataSource sut;

  setUp(() async {
    db = AppDatabase.forTesting();
    final now = DateTime.now();

    // Seed categories
    await db.into(db.categories).insert(CategoriesCompanion.insert(
      id: 'cat-1',
      name: 'Vêtements',
      isActive: const Value(true),
      isCustom: const Value(false),
      createdAt: now,
      updatedAt: now,
    ));
    await db.into(db.categories).insert(CategoriesCompanion.insert(
      id: 'cat-2',
      name: 'Chaussures',
      isActive: const Value(true),
      isCustom: const Value(false),
      createdAt: now,
      updatedAt: now,
    ));

    // Seed products
    await db.into(db.products).insert(ProductsCompanion.insert(
      id: 'prod-1',
      name: 'T-shirt blanc',
      sku: const Value('KEV-000001'),
      categoryId: const Value('cat-1'),
      archived: const Value(false),
      createdAt: now,
      updatedAt: now,
    ));
    await db.into(db.products).insert(ProductsCompanion.insert(
      id: 'prod-2',
      name: 'Jeans bleu',
      sku: const Value('KEV-000002'),
      categoryId: const Value('cat-1'),
      archived: const Value(false),
      createdAt: now,
      updatedAt: now,
    ));
    await db.into(db.products).insert(ProductsCompanion.insert(
      id: 'prod-3',
      name: 'Baskets running',
      sku: const Value('KEV-000003'),
      categoryId: const Value('cat-2'),
      archived: const Value(false),
      createdAt: now,
      updatedAt: now,
    ));
    // Archived product — should never appear in search results
    await db.into(db.products).insert(ProductsCompanion.insert(
      id: 'prod-4',
      name: 'T-shirt rouge archivé',
      sku: const Value('KEV-000004'),
      categoryId: const Value('cat-1'),
      archived: const Value(true),
      createdAt: now,
      updatedAt: now,
    ));

    sut = LocalProductDataSource(db, _NoOpSyncService(db));
  });

  tearDown(() async {
    await db.close();
  });

  group('LocalProductDataSource.search() — category matching (AC2)', () {
    test('matches by category name "Vêtements"', () async {
      final results = await sut.search('Vêtements');
      expect(results.length, 2);
      expect(results.map((p) => p.name),
          containsAll(['T-shirt blanc', 'Jeans bleu']));
    });

    test('matches by category name "Chaussures"', () async {
      final results = await sut.search('Chaussures');
      expect(results.length, 1);
      expect(results.first.name, 'Baskets running');
    });

    test('case-insensitive category match', () async {
      final results = await sut.search('vêtements');
      expect(results.length, 2);
    });

    test('partial category name match', () async {
      final results = await sut.search('Vête');
      expect(results.length, 2);
    });

    test('archived products excluded from category results', () async {
      final results = await sut.search('Vêtements');
      final names = results.map((p) => p.name).toList();
      expect(names, isNot(contains('T-shirt rouge archivé')));
    });

    test('matches by product name (existing behavior)', () async {
      final results = await sut.search('T-shirt');
      expect(results.length, 1);
      expect(results.first.name, 'T-shirt blanc');
    });

    test('matches by SKU (existing behavior)', () async {
      final results = await sut.search('KEV-000002');
      expect(results.length, 1);
      expect(results.first.name, 'Jeans bleu');
    });

    test('empty query returns all active products', () async {
      final results = await sut.search('');
      expect(results.length, 3);
    });

    test('no match returns empty list', () async {
      final results = await sut.search('zxy_nonexistent');
      expect(results, isEmpty);
    });
  });

  group('LocalProductDataSource.getLowStockProductIds() — per-store scoping', () {
    setUp(() async {
      final now = DateTime.now();
      // prod-1: critically low in store-a, plenty in store-b.
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-1',
        productId: 'prod-1',
        storeId: 'store-a',
        quantity: 1,
        minimumThreshold: const Value(5),
        updatedAt: now,
      ));
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-2',
        productId: 'prod-1',
        storeId: 'store-b',
        quantity: 50,
        minimumThreshold: const Value(5),
        updatedAt: now,
      ));
      // prod-2: healthy stock in store-a only.
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
        id: 'sl-3',
        productId: 'prod-2',
        storeId: 'store-a',
        quantity: 20,
        minimumThreshold: const Value(5),
        updatedAt: now,
      ));
    });

    test('storeId scopes to that store\'s own row', () async {
      final storeA = await sut.getLowStockProductIds(storeId: 'store-a');
      expect(storeA, contains('prod-1'));
      expect(storeA, isNot(contains('prod-2')));

      final storeB = await sut.getLowStockProductIds(storeId: 'store-b');
      expect(storeB, isNot(contains('prod-1')));
    });

    test('omitting storeId falls back to tenant-wide (any store) membership', () async {
      final tenantWide = await sut.getLowStockProductIds();
      expect(tenantWide, contains('prod-1'));
      expect(tenantWide, isNot(contains('prod-2')));
    });

    test('a store with no stock_levels row for the product is never low-stock there', () async {
      final storeC = await sut.getLowStockProductIds(storeId: 'store-c');
      expect(storeC, isEmpty);
    });
  });
}

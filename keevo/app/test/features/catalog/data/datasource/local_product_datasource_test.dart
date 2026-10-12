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
  Future<int> quarantinedOperationCount() async => 0;

  @override
  Future<void> pull() async {}

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  }) async {}
}

/// Records queued operations so tests can assert what reaches the sync queue.
class _RecordingSyncService extends _NoOpSyncService {
  _RecordingSyncService(super.db);

  final queued = <({String operation, String? entityId})>[];

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  }) async {
    queued.add((operation: operation, entityId: entityId));
  }
}

/// Fails every queueOperation — proves the local insert rolls back with it.
class _ThrowingSyncService extends _NoOpSyncService {
  _ThrowingSyncService(super.db);

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
    String? entityId,
  }) async {
    throw Exception('queue failure');
  }
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

  group('LocalProductDataSource.search() — literal LIKE wildcards (16.14)', () {
    Future<void> seed(String id, String name, {String? sku}) async {
      final now = DateTime.now();
      await db.into(db.products).insert(ProductsCompanion.insert(
        id: id,
        name: name,
        sku: Value(sku ?? 'SKU-$id'),
        archived: const Value(false),
        createdAt: now,
        updatedAt: now,
      ));
    }

    test('"%" matches literally', () async {
      await seed('w-1', 'Remise 50%');
      await seed('w-2', 'Riz 500g');
      final results = await sut.search('50%');
      expect(results.map((p) => p.name), ['Remise 50%']);
    });

    test('"_" matches literally', () async {
      await seed('w-3', 'a_b');
      await seed('w-4', 'axb');
      final results = await sut.search('a_b');
      expect(results.map((p) => p.name), ['a_b']);
    });

    test('backslash matches literally', () async {
      await seed('w-5', r'C:\x');
      await seed('w-6', 'Cx');
      final results = await sut.search(r'\x');
      expect(results.map((p) => p.name), [r'C:\x']);
    });

    test('SKU "_" matches literally', () async {
      await seed('w-8', 'Article un', sku: 'KEV_01');
      await seed('w-9', 'Article deux', sku: 'KEVX01');
      final results = await sut.search('kev_01');
      expect(results.map((p) => p.sku), ['KEV_01']);
    });

    test('category name "%" matches literally', () async {
      final now = DateTime.now();
      for (final (id, name) in [('cat-pct', '50% off'), ('cat-g', '500g')]) {
        await db.into(db.categories).insert(CategoriesCompanion.insert(
          id: id,
          name: name,
          createdAt: now,
          updatedAt: now,
        ));
      }
      for (final (id, cat) in [('w-10', 'cat-pct'), ('w-11', 'cat-g')]) {
        await db.into(db.products).insert(ProductsCompanion.insert(
          id: id,
          name: 'Produit $id',
          sku: Value('SKU-$id'),
          categoryId: Value(cat),
          createdAt: now,
          updatedAt: now,
        ));
      }
      final results = await sut.search('50%');
      expect(results.map((p) => p.id), ['w-10']);
    });

    test('plain text keeps case-insensitive substring match', () async {
      await seed('w-7', 'Riz parfumé');
      final results = await sut.search('RIZ');
      expect(results.map((p) => p.name), ['Riz parfumé']);
    });

    test('NFR5: search over 500 products returns in under 500 ms', () async {
      final now = DateTime.now();
      await db.batch((b) {
        b.insertAll(db.products, [
          for (var i = 0; i < 500; i++)
            ProductsCompanion.insert(
              id: 'nfr-$i',
              name: 'Produit $i',
              sku: Value('NFR-$i'),
              archived: const Value(false),
              createdAt: now,
              updatedAt: now,
            ),
        ]);
      });
      await sut.search('warmup');
      final sw = Stopwatch()..start();
      final results = await sut.search('produit');
      sw.stop();
      expect(results.length, 500);
      expect(sw.elapsedMilliseconds, lessThan(500));
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
  // The server resolves a product merged on name conflict through the
  // entityId of its CREATE op; without it, stock entries and sales queued
  // with the local id are rejected as PRODUCT_NOT_FOUND.
  group('product creations carry their id as entityId', () {
    late _RecordingSyncService sync;

    setUp(() {
      sync = _RecordingSyncService(db);
      sut = LocalProductDataSource(db, sync);
    });

    test('CREATE_PRODUCT', () async {
      final product = await sut.insert(name: 'Sac à main');

      expect(sync.queued.single.operation, 'CREATE_PRODUCT');
      expect(sync.queued.single.entityId, product.id);
    });

    test('CREATE_DRAFT_PRODUCT', () async {
      final product = await sut.insertDraft(
        name: 'Ceinture',
        priceVente: 1500,
        categoryId: 'cat-1',
      );

      expect(sync.queued.single.operation, 'CREATE_DRAFT_PRODUCT');
      expect(sync.queued.single.entityId, product.id);
    });
  });

  // v1s-13-8: insert + queue entry commit in one Drift transaction.
  group('product creations are atomic with their queue entry', () {
    setUp(() {
      sut = LocalProductDataSource(db, _ThrowingSyncService(db));
    });

    Future<int> productCount() async =>
        (await db.select(db.products).get()).length;

    test('insert(): queue failure leaves no product row', () async {
      final before = await productCount();
      await expectLater(sut.insert(name: 'Orphelin'), throwsA(isA<Exception>()));
      expect(await productCount(), before);
    });

    test('insertDraft(): queue failure leaves no product row', () async {
      final before = await productCount();
      await expectLater(
        sut.insertDraft(name: 'Brouillon', priceVente: 1000, categoryId: 'cat-1'),
        throwsA(isA<Exception>()),
      );
      expect(await productCount(), before);
    });
  });
}

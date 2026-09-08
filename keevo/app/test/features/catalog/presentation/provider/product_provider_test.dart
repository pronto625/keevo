import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/catalog/domain/repository/product_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';

class _MockProductRepository extends Mock implements ProductRepository {}

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

/// No-op [SyncService] stub — these tests only read via [LocalProductDataSource],
/// never via the sync queue.
class _NoOpSyncService implements SyncService {
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

/// Pins [activeStoreIdProvider] to a fixed store without touching SharedPreferences.
class _FixedActiveStoreNotifier extends ActiveStoreNotifier {
  _FixedActiveStoreNotifier(this._storeId);
  final String? _storeId;

  @override
  String? build() => _storeId;
}

void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });
  group('productListForPickerProvider', () {
    late _MockProductRepository mockRepo;

    final fakeProduct = ProductModel(
      id: 'prod-001',
      name: 'Robe Wax L',
      createdAt: DateTime(2026, 3, 14),
      updatedAt: DateTime(2026, 3, 14),
    );

    setUp(() {
      mockRepo = _MockProductRepository();
    });

    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          productRepositoryProvider.overrideWithValue(mockRepo),
        ]);

    test('returns local data when syncFromRemote succeeds', () async {
      when(() => mockRepo.syncFromRemote()).thenAnswer((_) async {});
      when(() => mockRepo.getAll()).thenAnswer((_) async => [fakeProduct]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(productListForPickerProvider.future);

      expect(result, hasLength(1));
      expect(result.first.id, 'prod-001');
    });

    test(
        'falls back to local data when syncFromRemote throws (offline) '
        'instead of propagating the error to the picker', () async {
      when(() => mockRepo.syncFromRemote())
          .thenThrow(Exception('DioException: connection error'));
      when(() => mockRepo.getAll()).thenAnswer((_) async => [fakeProduct]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(productListForPickerProvider.future);

      expect(result, hasLength(1));
      expect(result.first.id, 'prod-001');
    });
  });

  group('productList / outOfStockProductList / lowStockProductList — '
      'cross-store visibility (bug fix: product missing from other stores\' catalog)', () {
    late _MockProductRepository mockRepo;
    late AppDatabase db;
    late LocalProductDataSource localDataSource;

    final activeProduct = ProductModel(
      id: 'prod-1',
      name: 'Robe Wax L',
      status: ProductStatus.active,
      createdAt: DateTime(2026, 3, 14),
      updatedAt: DateTime(2026, 3, 14),
    );

    setUp(() async {
      mockRepo = _MockProductRepository();
      when(() => mockRepo.getAll()).thenAnswer((_) async => [activeProduct]);
      db = AppDatabase.forTesting();
      localDataSource = LocalProductDataSource(db, _NoOpSyncService());
    });

    tearDown(() async {
      await db.close();
    });

    ProviderContainer buildContainer(String? activeStoreId) =>
        ProviderContainer(overrides: [
          productRepositoryProvider.overrideWithValue(mockRepo),
          localProductDataSourceProvider.overrideWithValue(localDataSource),
          activeStoreIdProvider.overrideWith(() => _FixedActiveStoreNotifier(activeStoreId)),
        ]);

    test(
        'a product with a stock_levels row only in store-a still appears in '
        "store-b's catalog (matches POS, which already shows it there)", () async {
      final now = DateTime.now();
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-1',
            productId: 'prod-1',
            storeId: 'store-a',
            quantity: 10,
            updatedAt: now,
          ));
      final container = buildContainer('store-b');
      addTearDown(container.dispose);

      final result = await container.read(productListProvider.future);

      expect(result.map((p) => p.id), contains('prod-1'));
    });

    test(
        'that same product shows up in store-b\'s "out of stock" tab '
        '(0 stock there) instead of being hidden', () async {
      final now = DateTime.now();
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-1',
            productId: 'prod-1',
            storeId: 'store-a',
            quantity: 10,
            updatedAt: now,
          ));
      final container = buildContainer('store-b');
      addTearDown(container.dispose);

      final result = await container.read(outOfStockProductListProvider.future);

      expect(result.map((p) => p.id), contains('prod-1'));
    });

    test(
        'a product critically low in store-a does NOT falsely show as '
        "low-stock in store-b's low-stock tab", () async {
      final now = DateTime.now();
      await db.into(db.stockLevels).insert(StockLevelsCompanion.insert(
            id: 'sl-1',
            productId: 'prod-1',
            storeId: 'store-a',
            quantity: 1,
            minimumThreshold: const Value(5),
            updatedAt: now,
          ));
      final container = buildContainer('store-b');
      addTearDown(container.dispose);

      final result = await container.read(lowStockProductListProvider.future);

      expect(result.map((p) => p.id), isNot(contains('prod-1')));
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/inventory/data/datasource/local_multi_store_stock_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_multi_store_stock_datasource.dart';
import 'package:keevo/features/inventory/data/repository/multi_store_stock_repository_impl.dart';
import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';

class _MockLocalDs extends Mock implements LocalMultiStoreStockDataSource {}
class _MockRemoteDs extends Mock implements RemoteMultiStoreStockDataSource {}
class _MockSyncService extends Mock implements SyncService {}

void main() {
  late _MockLocalDs local;
  late _MockRemoteDs remote;
  late _MockSyncService syncService;
  late MultiStoreStockRepositoryImpl repo;

  final summaries = [
    const StoreStockSummaryModel(
      storeId: 's1', storeName: 'Boutique', storeType: 'STORE',
      productCount: 3, totalValueXaf: 30000, lowStockCount: 1,
    ),
  ];

  final products = [
    const StoreProductStockModel(
      productId: 'p1', productName: 'Produit A', storeId: 's1',
      quantity: 2, minimumThreshold: 5, status: 'BAS', isLow: true, isCritical: false,
    ),
  ];

  setUp(() {
    local = _MockLocalDs();
    remote = _MockRemoteDs();
    syncService = _MockSyncService();
    when(() => syncService.unsyncedStockKeys()).thenAnswer((_) async => {});
    repo = MultiStoreStockRepositoryImpl(
      local: local, remote: remote, syncService: syncService,
    );
  });

  group('MultiStoreStockRepositoryImpl (Story 3.2 — Task 12)', () {
    test('getOverview() returns remote data when online', () async {
      when(() => remote.getOverview()).thenAnswer((_) async => summaries);

      final result = await repo.getOverview();

      expect(result, summaries);
      verify(() => remote.getOverview()).called(1);
      verifyNever(() => local.getStoreOverviews());
    });

    test('getOverview() falls back to local when remote throws', () async {
      when(() => remote.getOverview()).thenThrow(Exception('network error'));
      when(() => local.getStoreOverviews()).thenAnswer((_) async => summaries);

      final result = await repo.getOverview();

      expect(result, summaries);
      verify(() => local.getStoreOverviews()).called(1);
    });

    test('getStoreStockDetail() fetches remote, upserts to Drift, returns remote data',
        () async {
      when(() => remote.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenAnswer((_) async => (content: products, hasMore: false));
      when(() => local.upsertStockLevels('s1', products))
          .thenAnswer((_) async {});

      final result = await repo.getStoreStockDetail('s1');

      expect(result, products);
      verify(() => local.upsertStockLevels('s1', products)).called(1);
    });

    test('getStoreStockDetail() falls back to local when remote throws', () async {
      when(() => remote.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenThrow(Exception('offline'));
      when(() => local.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenAnswer((_) async => products);

      final result = await repo.getStoreStockDetail('s1');

      expect(result, products);
      verify(() => local.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .called(1);
    });

    test('searchAcrossStores() always delegates to local datasource', () async {
      when(() => local.searchAcrossStores('chemise'))
          .thenAnswer((_) async => products);

      final result = await repo.searchAcrossStores('chemise');

      expect(result, products);
      verifyNever(() => remote.getOverview());
    });

    // Story 13.8b: only stock changed by a pending or held operation keeps
    // its local value; other queued operations do not freeze the refresh.
    test('getOverview() returns local data while some stock is protected',
        () async {
      when(() => syncService.unsyncedStockKeys())
          .thenAnswer((_) async => {'p1|s1'});
      when(() => local.getStoreOverviews()).thenAnswer((_) async => summaries);

      final result = await repo.getOverview();

      expect(result, summaries);
      verifyNever(() => remote.getOverview());
      verify(() => local.getStoreOverviews()).called(1);
    });

    test('getStoreStockDetail(): protected row kept, others refreshed, local '
        'view returned', () async {
      const other = StoreProductStockModel(
        productId: 'p2', productName: 'Produit B', storeId: 's1',
        quantity: 9, status: 'NORMAL', isLow: false, isCritical: false,
      );
      when(() => syncService.unsyncedStockKeys())
          .thenAnswer((_) async => {'p1|s1'});
      when(() => remote.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenAnswer((_) async => (content: [...products, other], hasMore: false));
      when(() => local.upsertStockLevels(any(), any())).thenAnswer((_) async {});
      when(() => local.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenAnswer((_) async => products);

      final result = await repo.getStoreStockDetail('s1');

      expect(result, products);
      verify(() => local.upsertStockLevels('s1', [other])).called(1);
    });

    test('getStoreStockDetail(): a protected row of another store does not '
        'change this store', () async {
      when(() => syncService.unsyncedStockKeys())
          .thenAnswer((_) async => {'p1|s2'});
      when(() => remote.getStoreStockDetail('s1', page: 0, size: 25, sortLowFirst: true))
          .thenAnswer((_) async => (content: products, hasMore: false));
      when(() => local.upsertStockLevels('s1', products)).thenAnswer((_) async {});

      expect(await repo.getStoreStockDetail('s1'), products);
      verify(() => local.upsertStockLevels('s1', products)).called(1);
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/features/profitability/data/datasource/local_profitability_datasource.dart';
import 'package:keevo/features/profitability/data/datasource/remote_profitability_datasource.dart';
import 'package:keevo/features/profitability/data/repository/profitability_repository_impl.dart';
import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';

class _MockLocal extends Mock implements LocalProfitabilityDatasource {}

class _MockRemote extends Mock implements RemoteProfitabilityDatasource {}

class _MockConnectivity extends Mock implements ConnectivityService {}

void main() {
  late _MockLocal local;
  late _MockRemote remote;
  late _MockConnectivity connectivity;
  late ProfitabilityRepositoryImpl sut;

  final from = DateTime(2025, 1, 1);
  final to = DateTime(2025, 1, 31);

  // ── Helpers ──────────────────────────────────────────────────────────────

  ProductProfitabilityEntry _entry({String id = 'p1'}) =>
      ProductProfitabilityEntry(
        productId: id,
        productName: 'Produit Test',
        categoryName: 'Cat',
        unitsSold: 5,
        totalRevenue: 10000,
        totalCost: 7000,
        grossMarginXaf: 3000,
        marginPercent: 42.8,
        isLoss: false,
        storeId: null,
        marginLevel: 'PROFITABLE',
      );

  StorePerformanceEntry _storeEntry({String id = 's1'}) => StorePerformanceEntry(
        rank: 1,
        storeId: id,
        storeName: 'Boutique A',
        totalRevenue: 500000,
        salesCount: 10,
        averageBasket: 50000,
        topProductName: 'Produit Test',
        deltaPercent: 12.5,
      );

  setUp(() {
    local = _MockLocal();
    remote = _MockRemote();
    connectivity = _MockConnectivity();
    sut = ProfitabilityRepositoryImpl(
      local: local,
      remote: remote,
      connectivity: connectivity,
    );
  });

  // ── getProductProfitability ─────────────────────────────────────────────

  group('getProductProfitability', () {
    final params = ProfitabilityParams(from: from, to: to);

    test('online_returnsRemoteData_doesNotCallLocal', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => true);
      when(() => remote.fetchProducts(
            from: any(named: 'from'),
            to: any(named: 'to'),
            sort: any(named: 'sort'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => [_entry(), _entry(id: 'p2')]);

      final result = await sut.getProductProfitability(params: params);

      expect(result, hasLength(2));
      expect(result.first.productId, 'p1');
      verifyNever(() => local.getProductProfitability(
            from: any(named: 'from'),
            to: any(named: 'to'),
          ));
    });

    test('online_remoteFails_fallsBackToLocal', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => true);
      when(() => remote.fetchProducts(
            from: any(named: 'from'),
            to: any(named: 'to'),
            sort: any(named: 'sort'),
            storeId: any(named: 'storeId'),
          )).thenThrow(Exception('DioException: network error'));
      when(() => local.getProductProfitability(
            from: any(named: 'from'),
            to: any(named: 'to'),
            sort: any(named: 'sort'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => [_entry(id: 'p_local')]);

      final result = await sut.getProductProfitability(params: params);

      expect(result, hasLength(1));
      expect(result.first.productId, 'p_local');
    });

    test('offline_callsLocalOnly_doesNotCallRemote', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => false);
      when(() => local.getProductProfitability(
            from: any(named: 'from'),
            to: any(named: 'to'),
            sort: any(named: 'sort'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => [_entry()]);

      final result = await sut.getProductProfitability(params: params);

      expect(result, hasLength(1));
      verifyNever(() => remote.fetchProducts(
            from: any(named: 'from'),
            to: any(named: 'to'),
          ));
    });

    test('offline_passesCorrectSortToLocal', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => false);
      when(() => local.getProductProfitability(
            from: any(named: 'from'),
            to: any(named: 'to'),
            sort: any(named: 'sort'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => []);

      final paramsXaf =
          ProfitabilityParams(from: from, to: to, sort: SortOption.marginXafDesc);
      await sut.getProductProfitability(params: paramsXaf);

      verify(() => local.getProductProfitability(
            from: from,
            to: to,
            sort: 'MARGIN_XAF_DESC',
            storeId: null,
          )).called(1);
    });
  });

  // ── getStorePerformance ─────────────────────────────────────────────────

  group('getStorePerformance', () {
    final params = StorePerformanceParams(from: from, to: to);

    test('online_returnsRemoteData_doesNotCallLocal', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => true);
      when(() => remote.fetchStores(
            from: any(named: 'from'),
            to: any(named: 'to'),
            metric: any(named: 'metric'),
          )).thenAnswer((_) async => [_storeEntry(), _storeEntry(id: 's2')]);

      final result = await sut.getStorePerformance(params: params);

      expect(result, hasLength(2));
      expect(result.first.storeId, 's1');
      verifyNever(() => local.getStorePerformance(
            from: any(named: 'from'),
            to: any(named: 'to'),
          ));
    });

    test('online_remoteFails_fallsBackToLocal', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => true);
      when(() => remote.fetchStores(
            from: any(named: 'from'),
            to: any(named: 'to'),
            metric: any(named: 'metric'),
          )).thenThrow(Exception('DioException: network error'));
      when(() => local.getStorePerformance(
            from: any(named: 'from'),
            to: any(named: 'to'),
            metric: any(named: 'metric'),
          )).thenAnswer((_) async => [_storeEntry(id: 's_local')]);

      final result = await sut.getStorePerformance(params: params);

      expect(result.first.storeId, 's_local');
    });

    test('offline_callsLocalOnly_doesNotCallRemote', () async {
      when(() => connectivity.isOnline()).thenAnswer((_) async => false);
      when(() => local.getStorePerformance(
            from: any(named: 'from'),
            to: any(named: 'to'),
            metric: any(named: 'metric'),
          )).thenAnswer((_) async => [_storeEntry()]);

      await sut.getStorePerformance(params: params);

      verifyNever(() => remote.fetchStores(
            from: any(named: 'from'),
            to: any(named: 'to'),
          ));
    });
  });
}

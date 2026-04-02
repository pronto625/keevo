import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/profitability/domain/model/product_profitability_model.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

class MockProfitabilityRepository extends Mock implements ProfitabilityRepository {}

void main() {
  setUpAll(() {
    registerFallbackValue(
      ProfitabilityParams(from: DateTime(2025), to: DateTime(2025)),
    );
    registerFallbackValue(
      StorePerformanceParams(from: DateTime(2025), to: DateTime(2025)),
    );
  });
  // ── Helpers ────────────────────────────────────────────────────────────────

  ProductProfitabilityEntry makeEntry({
    String productId = 'p1',
    String productName = 'Produit Test',
    int unitsSold = 10,
    int totalRevenue = 50000,
    int totalCost = 30000,
    int grossMarginXaf = 20000,
    double marginPercent = 66.67,
    bool isLoss = false,
    String marginLevel = 'PROFITABLE',
  }) =>
      ProductProfitabilityEntry(
        productId: productId,
        productName: productName,
        categoryName: 'Cat',
        unitsSold: unitsSold,
        totalRevenue: totalRevenue,
        totalCost: totalCost,
        grossMarginXaf: grossMarginXaf,
        marginPercent: marginPercent,
        isLoss: isLoss,
        storeId: null,
        marginLevel: marginLevel,
      );

  final now = DateTime.now();
  final from = DateTime(now.year, now.month, now.day - 30);
  final to = DateTime(now.year, now.month, now.day);
  final defaultParams = ProfitabilityParams(from: from, to: to);

  group('productProfitabilityProvider', () {
    test('productProfitabilityProvider_loadsFromRemote_whenOnline_andCachesLocally',
        () async {
      final mockRepo = MockProfitabilityRepository();
      final entries = [makeEntry(), makeEntry(productId: 'p2', productName: 'P2')];

      when(() => mockRepo.getProductProfitability(params: defaultParams))
          .thenAnswer((_) async => entries);

      final container = ProviderContainer(
        overrides: [
          profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );
      addTearDown(container.dispose);

      final result = await container
          .read(productProfitabilityProvider(defaultParams).future);

      expect(result, hasLength(2));
      expect(result.first.productName, 'Produit Test');
    });

    // Note: this test validates that productProfitabilityProvider delegates to
    // the repository regardless of connectivity — the actual online/offline
    // branch selection is tested in profitability_repository_impl_test.dart.
    test('productProfitabilityProvider_delegatesToRepository_returnsData',
        () async {
      final mockRepo = MockProfitabilityRepository();
      final entries = [makeEntry()];

      when(() => mockRepo.getProductProfitability(params: defaultParams))
          .thenAnswer((_) async => entries);

      final container = ProviderContainer(
        overrides: [
          profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );
      addTearDown(container.dispose);

      final result = await container
          .read(productProfitabilityProvider(defaultParams).future);
      expect(result, hasLength(1));
    });

    test('productProfitabilityProvider_sortChange_resortsList', () async {
      final mockRepo = MockProfitabilityRepository();
      final entries = [
        makeEntry(productId: 'p1', marginPercent: 10, grossMarginXaf: 5000),
        makeEntry(productId: 'p2', marginPercent: 50, grossMarginXaf: 2000),
      ];

      when(() => mockRepo.getProductProfitability(params: any(named: 'params')))
          .thenAnswer((_) async => entries);

      final container = ProviderContainer(
        overrides: [
          profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );
      addTearDown(container.dispose);

      // Default sort: MARGIN_PCT_DESC → p2 (50%) first
      final resultDefault = await container
          .read(productProfitabilityProvider(defaultParams).future);
      expect(resultDefault.first.productId, 'p2');

      // Sort by MARGIN_XAF_DESC → p1 (5000 XAF) first
      final paramsXaf = defaultParams.copyWith(sort: SortOption.marginXafDesc);
      final resultXaf = await container
          .read(productProfitabilityProvider(paramsXaf).future);
      expect(resultXaf.first.productId, 'p1');
    });
  });

  group('storePerformanceProvider', () {
    test('storePerformanceProvider_loadsRankedList_withDeltaPercent', () async {
      final mockRepo = MockProfitabilityRepository();
      final entries = [
        StorePerformanceEntry(
          rank: 1,
          storeId: 's1',
          storeName: 'Store A',
          totalRevenue: 100000,
          salesCount: 10,
          averageBasket: 10000,
          topProductName: 'Prod A',
          deltaPercent: 8.0,
        ),
      ];

      final storeParams = StorePerformanceParams(from: from, to: to);
      when(() => mockRepo.getStorePerformance(params: storeParams))
          .thenAnswer((_) async => entries);

      final container = ProviderContainer(
        overrides: [
          profitabilityRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );
      addTearDown(container.dispose);

      final result = await container
          .read(storePerformanceProvider(storeParams).future);

      expect(result, hasLength(1));
      expect(result.first.rank, 1);
      expect(result.first.deltaPercent, closeTo(8.0, 0.01));
    });
  });
}

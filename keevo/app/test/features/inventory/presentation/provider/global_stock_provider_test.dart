import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';
import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
import 'package:keevo/features/inventory/domain/repository/multi_store_stock_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/global_stock_provider.dart';

class _MockRepo extends Mock implements MultiStoreStockRepository {}

void main() {
  group('globalStockProviders (Story 3.2 — Task 13)', () {
    late _MockRepo mockRepo;

    final summaries = [
      const StoreStockSummaryModel(
        storeId: 's1', storeName: 'Boutique', storeType: 'STORE',
        productCount: 2, totalValueXaf: 20000, lowStockCount: 0,
      ),
    ];

    final products = [
      const StoreProductStockModel(
        productId: 'p1', productName: 'Produit', storeId: 's1',
        quantity: 10, status: 'NORMAL', isLow: false, isCritical: false,
      ),
    ];

    setUp(() {
      mockRepo = _MockRepo();
    });

    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          multiStoreStockRepositoryProvider.overrideWithValue(mockRepo),
        ]);

    test('globalStockOverviewProvider returns list of summaries', () async {
      when(() => mockRepo.getOverview()).thenAnswer((_) async => summaries);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(globalStockOverviewProvider.future);
      expect(result, summaries);
    });

    test('storeStockDetailProvider returns products for storeId', () async {
      when(() => mockRepo.getStoreStockDetail('s1', sortLowFirst: true))
          .thenAnswer((_) async => products);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result =
          await container.read(storeStockDetailProvider('s1').future);
      expect(result, products);
    });

    test('stockSearchResultsProvider returns empty for query < 2 chars', () async {
      final container = buildContainer();
      addTearDown(container.dispose);

      final result =
          await container.read(stockSearchResultsProvider.future);
      expect(result, isEmpty);
    });

    test('stockSearchResultsProvider delegates to repo for query >= 2 chars',
        () async {
      when(() => mockRepo.searchAcrossStores('ch'))
          .thenAnswer((_) async => products);
      final container = buildContainer();
      addTearDown(container.dispose);

      container.read(stockSearchQueryProvider.notifier).state = 'ch';
      final result =
          await container.read(stockSearchResultsProvider.future);
      expect(result, products);
    });
  });
}

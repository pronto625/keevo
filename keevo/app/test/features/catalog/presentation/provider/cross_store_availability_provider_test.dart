import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/cross_store_availability_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/cross_store_availability_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';

class _MockStockRepository extends Mock implements StockRepository {}

/// RED → GREEN tests for crossStoreAvailabilityProvider (Story 3.4 — Task 10).
void main() {
  late _MockStockRepository mockRepo;

  final now = DateTime(2024, 1, 1);

  final tModel = CrossStoreAvailabilityModel(
    productId: 'p1',
    productName: 'Produit A',
    entries: [
      CrossStoreAvailabilityEntry(
        storeId: 's1',
        storeName: 'Boutique Centre',
        quantity: 10,
        minimumThreshold: 2,
        isLow: false,
      ),
      CrossStoreAvailabilityEntry(
        storeId: 's2',
        storeName: 'Entrepôt Nord',
        storeType: 'WAREHOUSE',
        quantity: 0,
        minimumThreshold: 0,
        isLow: false,
      ),
    ],
    refreshedAt: now,
  );

  setUp(() {
    mockRepo = _MockStockRepository();
  });

  ProviderContainer _container() => ProviderContainer(
        overrides: [
          stockRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );

  group('crossStoreAvailabilityProvider (Story 3.4)', () {
    test('returns model from repository on success', () async {
      when(() => mockRepo.getCrossStoreAvailability('p1'))
          .thenAnswer((_) async => tModel);

      final container = _container();
      addTearDown(container.dispose);

      final result =
          await container.read(crossStoreAvailabilityProvider('p1').future);

      expect(result.productId, 'p1');
      expect(result.entries.length, 2);
      verify(() => mockRepo.getCrossStoreAvailability('p1')).called(1);
    });

    test('propagates exception when repository throws', () async {
      when(() => mockRepo.getCrossStoreAvailability('unknown'))
          .thenThrow(Exception('not found'));

      final container = _container();
      addTearDown(container.dispose);

      expect(
        () => container.read(crossStoreAvailabilityProvider('unknown').future),
        throwsException,
      );
    });

    test('entry.stockStatus is ok when quantity > 0 and not low', () {
      final entry = tModel.entries.first;
      expect(entry.stockStatus, CrossStoreStockStatus.ok);
    });

    test('entry.stockStatus is outOfStock when quantity == 0', () {
      final entry = tModel.entries.last;
      expect(entry.stockStatus, CrossStoreStockStatus.outOfStock);
    });

    test('entry.isWarehouse is true for WAREHOUSE storeType', () {
      expect(tModel.entries.last.isWarehouse, isTrue);
      expect(tModel.entries.first.isWarehouse, isFalse);
    });
  });
}

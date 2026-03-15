import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
import 'package:keevo/features/inventory/domain/repository/stock_transfer_repository.dart';
import 'package:keevo/features/inventory/presentation/provider/stock_transfer_provider.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';

class _MockRepo extends Mock implements StockTransferRepository {}

class _FakeStockTransferModel extends Fake implements StockTransferModel {}

// Stub notifiers that return empty lists — avoids hitting real DB/network.
class _FakeStoreListNotifier extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [];
}

void main() {
  setUpAll(() {
    registerFallbackValue(_FakeStockTransferModel());
  });

  group('stockTransferProviders (Story 3.3 — Task 14)', () {
    late _MockRepo mockRepo;

    final fakeTransfer = StockTransferModel(
      id: 'tf-001',
      sourceStoreId: 'src-001',
      destinationStoreId: 'dst-001',
      productId: 'prod-001',
      quantity: 5,
      actorId: 'actor-001',
      occurredAt: DateTime(2026, 3, 14),
      status: 'COMPLETED',
    );

    setUp(() {
      mockRepo = _MockRepo();
    });

    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          stockTransferRepositoryProvider.overrideWithValue(mockRepo),
          // Stub out providers that would hit the real DB/network.
          storeListNotifierProvider
              .overrideWith(_FakeStoreListNotifier.new),
          productListForPickerProvider
              .overrideWith((_) async => <ProductModel>[]),
        ]);

    test('transferHistoryProvider returns list from repository', () async {
      when(() => mockRepo.getHistory()).thenAnswer((_) async => [fakeTransfer]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result =
          await container.read(transferHistoryProvider().future);
      expect(result, hasLength(1));
      expect(result.first.id, 'tf-001');
    });

    test('transferHistoryProvider with storeId filter passes it to repo',
        () async {
      when(() => mockRepo.getHistory(
            sourceStoreId: any(named: 'sourceStoreId'),
          )).thenAnswer((_) async => [fakeTransfer]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container
          .read(transferHistoryProvider(sourceStoreId: 'src-001').future);
      expect(result, hasLength(1));
      verify(() => mockRepo.getHistory(sourceStoreId: 'src-001')).called(1);
    });

    test('ExecuteTransferNotifier.execute calls repo and returns model',
        () async {
      when(() => mockRepo.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async => fakeTransfer);
      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(executeTransferNotifierProvider.notifier);
      final result = await notifier.execute(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
      );

      expect(result.id, 'tf-001');
    });

    test('ExecuteTransferNotifier.execute sets state to AsyncData on success',
        () async {
      when(() => mockRepo.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async => fakeTransfer);
      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(executeTransferNotifierProvider.notifier);
      await notifier.execute(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
      );

      final state = container.read(executeTransferNotifierProvider);
      expect(state, isA<AsyncData<StockTransferModel?>>());
      expect(state.value?.id, 'tf-001');
    });
  });
}

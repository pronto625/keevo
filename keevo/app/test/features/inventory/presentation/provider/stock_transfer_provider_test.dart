import 'dart:async';

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

    void stubHistory(
        Future<List<StockTransferModel>> Function(Invocation inv) answer) {
      when(() => mockRepo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: any(named: 'page'),
            pageSize: any(named: 'pageSize'),
          )).thenAnswer(answer);
    }

    List<StockTransferModel> pageOf(int page, int count) => [
          for (var i = 0; i < count; i++)
            fakeTransfer.copyWith(id: 'tf-$page-$i'),
        ];

    test('transferHistoryProvider returns the first page from repository',
        () async {
      stubHistory((_) async => [fakeTransfer]);
      final container = buildContainer();
      addTearDown(container.dispose);

      final result = await container.read(transferHistoryProvider().future);
      expect(result.items, hasLength(1));
      expect(result.items.first.id, 'tf-001');
      expect(result.hasMore, isFalse);
    });

    test('store and status are passed to the query (v1s-16-23)', () async {
      stubHistory((_) async => [fakeTransfer]);
      final container = buildContainer();
      addTearDown(container.dispose);

      await container.read(
          transferHistoryProvider(storeId: 'src-001', status: 'COMPLETED')
              .future);
      verify(() => mockRepo.getHistory(
            storeId: 'src-001',
            status: 'COMPLETED',
            page: 0,
            pageSize: kTransferHistoryPageSize,
          )).called(1);
    });

    test('loadMore appends the next page, dedups ids, stops at a short page',
        () async {
      stubHistory((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        return [pageOf(0, 1).first, ...pageOf(1, 5)];
      });
      final container = buildContainer();
      addTearDown(container.dispose);
      final provider = transferHistoryProvider(storeId: 'src-001');
      final sub = container.listen(provider, (_, __) {});
      addTearDown(sub.close);

      final first = await container.read(provider.future);
      expect(first.hasMore, isTrue);

      await container.read(provider.notifier).loadMore();
      final state = container.read(provider).value!;
      expect(state.items, hasLength(kTransferHistoryPageSize + 5));
      expect(state.items.map((t) => t.id).toSet(), hasLength(state.items.length));
      expect(state.hasMore, isFalse);

      // No more pages → no further call.
      await container.read(provider.notifier).loadMore();
      verify(() => mockRepo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: 1,
            pageSize: any(named: 'pageSize'),
          )).called(1);
      verifyNever(() => mockRepo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: 2,
            pageSize: any(named: 'pageSize'),
          ));
    });

    test('loadMore failure leaves the loaded list untouched', () async {
      stubHistory((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        throw Exception('network');
      });
      final container = buildContainer();
      addTearDown(container.dispose);
      final provider = transferHistoryProvider();
      final sub = container.listen(provider, (_, __) {});
      addTearDown(sub.close);
      await container.read(provider.future);

      await expectLater(
          container.read(provider.notifier).loadMore(), throwsException);
      final state = container.read(provider).value!;
      expect(state.items, hasLength(kTransferHistoryPageSize));
      expect(state.hasMore, isTrue);
      expect(container.read(provider.notifier).isLoadingMore, isFalse);
    });

    test('loadMore after a failed rebuild does nothing (error kept)',
        () async {
      var failFirst = false;
      stubHistory((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0 && failFirst) throw Exception('network');
        return pageOf(page, kTransferHistoryPageSize);
      });
      final container = buildContainer();
      addTearDown(container.dispose);
      final provider = transferHistoryProvider();
      final sub = container.listen(provider, (_, __) {});
      addTearDown(sub.close);
      await container.read(provider.future);

      failFirst = true;
      container.invalidate(transferHistoryProvider);
      await expectLater(container.read(provider.future), throwsException);

      await container.read(provider.notifier).loadMore();
      expect(container.read(provider).hasError, isTrue);
      verifyNever(() => mockRepo.getHistory(
            storeId: any(named: 'storeId'),
            status: any(named: 'status'),
            page: 1,
            pageSize: any(named: 'pageSize'),
          ));
    });

    test('a refresh during loadMore drops the stale page', () async {
      final gate = Completer<List<StockTransferModel>>();
      stubHistory((inv) async {
        final page = inv.namedArguments[#page] as int;
        if (page == 0) return pageOf(0, kTransferHistoryPageSize);
        return gate.future;
      });
      final container = buildContainer();
      addTearDown(container.dispose);
      final provider = transferHistoryProvider();
      final sub = container.listen(provider, (_, __) {});
      addTearDown(sub.close);
      await container.read(provider.future);

      final pending = container.read(provider.notifier).loadMore();
      container.invalidate(transferHistoryProvider);
      await container.read(provider.future);
      gate.complete(pageOf(1, 3));
      await pending;

      expect(container.read(provider).value!.items,
          hasLength(kTransferHistoryPageSize));
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

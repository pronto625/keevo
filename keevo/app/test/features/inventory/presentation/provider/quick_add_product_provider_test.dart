import 'dart:ffi';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/inventory/data/datasource/remote_quick_add_datasource.dart';
import 'package:keevo/features/inventory/presentation/provider/quick_add_product_provider.dart';

void _overrideSqlite3ForLinuxTesting() {
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

class _MockRemoteDatasource extends Mock implements RemoteQuickAddDatasource {}

class _MockSyncService extends Mock implements SyncService {}

class _MockDio extends Mock implements Dio {}

final _fakeResult = QuickAddResult(
  productId: 'prod-new',
  productName: 'Robe Wax L',
  sku: 'KEV-ABC123',
  categoryId: 'cat-001',
  physicalQty: 3,
  inventoryCountId: 'cnt-001',
  stockLevelId: 'stk-001',
);

void main() {
  group('QuickAddProductNotifier (Story 6.2.a — Task 20)', () {
    late _MockRemoteDatasource mockDatasource;
    late _MockSyncService mockSyncService;
    late AppDatabase db;

    setUpAll(() {
      _overrideSqlite3ForLinuxTesting();
    });

    setUp(() {
      mockDatasource = _MockRemoteDatasource();
      mockSyncService = _MockSyncService();
      db = AppDatabase.forTesting();
    });

    tearDown(() async {
      await db.close();
    });

    ProviderContainer buildContainer() => ProviderContainer(overrides: [
          remoteQuickAddDatasourceProvider
              .overrideWithValue(mockDatasource),
          appDatabaseProvider.overrideWithValue(db),
          syncServiceProvider.overrideWithValue(mockSyncService),
          dioProvider.overrideWithValue(_MockDio()),
        ]);

    test('quickAdd online success returns QuickAddResult', () async {
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenAnswer((_) async => _fakeResult);

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      final result = await notifier.quickAdd(
        sessionId: 'session-001',
        name: 'Robe Wax L',
        categoryId: 'cat-001',
        physicalQty: 3,
      );

      expect(result, isNotNull);
      expect(result!.productId, 'prod-new');
      expect(result.productName, 'Robe Wax L');
      expect(result.sku, 'KEV-ABC123');
    });

    test('quickAdd online 409 returns null (dedup)', () async {
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: ''),
        response: Response(
          requestOptions: RequestOptions(path: ''),
          statusCode: 409,
        ),
        type: DioExceptionType.badResponse,
      ));

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      final result = await notifier.quickAdd(
        sessionId: 'session-001',
        name: 'Robe Wax L',
        categoryId: 'cat-001',
        physicalQty: 3,
      );

      expect(result, isNull);
    });

    test(
        'v1s-14-13 — quickAdd online 403 PLAN_LIMIT_EXCEEDED rethrows, '
        'nothing written offline', () async {
      final refusal = DioException(
        requestOptions: RequestOptions(path: ''),
        response: Response(
          requestOptions: RequestOptions(path: ''),
          statusCode: 403,
          data: {
            'error': 'Limite de produits atteinte',
            'code': 403,
            'domainCode': 'PLAN_LIMIT_EXCEEDED',
            'details': {'entity': 'products', 'limit': 500, 'current': 500},
          },
        ),
        type: DioExceptionType.badResponse,
      );
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenThrow(refusal);

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      await expectLater(
        notifier.quickAdd(
          sessionId: 'session-001',
          name: 'Robe Wax L',
          categoryId: 'cat-001',
          physicalQty: 3,
        ),
        throwsA(same(refusal)),
      );

      expect(await db.select(db.products).get(), isEmpty);
      expect(await db.select(db.stockLevels).get(), isEmpty);
      expect(await db.select(db.inventoryCounts).get(), isEmpty);
      verifyNever(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
    });

    test('quickAdd caches locally in Drift after online success', () async {
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenAnswer((_) async => _fakeResult);

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      await notifier.quickAdd(
        sessionId: 'session-001',
        name: 'Robe Wax L',
        categoryId: 'cat-001',
        physicalQty: 3,
      );

      // Verify product was cached in Drift
      final products = await db.select(db.products).get();
      expect(products.any((p) => p.id == 'prod-new'), isTrue);

      // Verify stock level was cached
      final stockLevels = await db.select(db.stockLevels).get();
      expect(stockLevels.any((s) => s.id == 'stk-001'), isTrue);
      // J18 — no stock at creation: the validation applies the count once.
      expect(stockLevels.firstWhere((s) => s.id == 'stk-001').quantity, 0);
      expect(products.firstWhere((p) => p.id == 'prod-new').stockQuantity, 0);

      // Verify inventory count was cached
      final counts = await db.select(db.inventoryCounts).get();
      expect(counts.any((c) => c.id == 'cnt-001'), isTrue);
    });

    test('quickAdd offline creates locally and queues sync ops', () async {
      // Simulate network error
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: ''),
        type: DioExceptionType.connectionError,
      ));

      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      final result = await notifier.quickAdd(
        sessionId: 'session-001',
        name: 'Nouveau Produit',
        categoryId: 'cat-001',
        physicalQty: 5,
      );

      expect(result, isNotNull);
      expect(result!.productName, 'Nouveau Produit');

      // Verify 3 sync operations were queued. The server keeps the product
      // id only from payload['id']; the stock entry and the count below
      // reference that id, so it must be the one the server creates.
      final created = verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_PRODUCT',
            payload: captureAny(named: 'payload'),
            entityId: captureAny(named: 'entityId'),
          ))
        ..called(1);
      final createPayload = created.captured[0] as Map<String, dynamic>;
      expect(createPayload['id'], created.captured[1]);
      expect(createPayload['id'], result.productId);
      // J18 — no stock entry: the counted 5 is applied once, by the
      // inventory validation (theoretical 0 → physical 5).
      expect(createPayload['stockQuantity'], 0);
      verifyNever(() => mockSyncService.queueOperation(
            operation: 'RECORD_STOCK_ENTRY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          ));
      final saved = verify(() => mockSyncService.queueOperation(
            operation: 'SAVE_INVENTORY_COUNT',
            payload: captureAny(named: 'payload'),
            entityId: any(named: 'entityId'),
          ))
        ..called(1);
      final countPayload = saved.captured.single as Map<String, dynamic>;
      expect(countPayload['theoretical'], 0);
      expect(countPayload['physical'], 5);

      final stock = await (db.select(db.stockLevels)
            ..where((s) => s.productId.equals(result.productId)))
          .getSingle();
      expect(stock.quantity, 0);
      final count = await (db.select(db.inventoryCounts)
            ..where((c) => c.productId.equals(result.productId)))
          .getSingle();
      expect(count.theoretical, 0);
      expect(count.physical, 5);
    });

    // v1s-13-8: local inserts + the 3 queued ops commit together.
    test('quickAdd offline: queue failure leaves no product/stock/count row',
        () async {
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: ''),
        type: DioExceptionType.connectionError,
      ));
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {});
      when(() => mockSyncService.queueOperation(
            operation: 'SAVE_INVENTORY_COUNT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('queue failure'));

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      Object? error;
      try {
        await notifier.quickAdd(
          sessionId: 'session-001',
          name: 'Produit Orphelin',
          categoryId: 'cat-001',
          physicalQty: 5,
        );
      } catch (e) {
        error = e;
      }

      expect(error, isNotNull);
      expect(await db.select(db.products).get(), isEmpty);
      expect(await db.select(db.stockLevels).get(), isEmpty);
      expect(await db.select(db.inventoryCounts).get(), isEmpty);
    });

    // v1s-16-16: offline dedup candidates use the shared Unicode fold;
    // equality stays case-only (backend `lower(name)` unique index).
    group('offline local dedup (Unicode)', () {
      Future<void> seedProduct(String id, String name) async {
        final now = DateTime.now();
        await db.into(db.products).insert(ProductsCompanion.insert(
              id: id,
              name: name,
              createdAt: now,
              updatedAt: now,
            ));
      }

      Future<QuickAddResult?> quickAddOffline(String name) async {
        when(() => mockDatasource.quickAdd(
              sessionId: any(named: 'sessionId'),
              name: any(named: 'name'),
              categoryId: any(named: 'categoryId'),
              physicalQty: any(named: 'physicalQty'),
            )).thenThrow(DioException(
          requestOptions: RequestOptions(path: ''),
          type: DioExceptionType.connectionError,
        ));
        when(() => mockSyncService.queueOperation(
              operation: any(named: 'operation'),
              payload: any(named: 'payload'),
              entityId: any(named: 'entityId'),
            )).thenAnswer((_) async {});
        final container = buildContainer();
        addTearDown(container.dispose);
        return container.read(quickAddProductNotifierProvider.notifier).quickAdd(
              sessionId: 'session-001',
              name: name,
              categoryId: 'cat-001',
              physicalQty: 1,
            );
      }

      test('"épicerie" next to "Épicerie" is a duplicate', () async {
        await seedProduct('p-epi', 'Épicerie');
        expect(await quickAddOffline(' épicerie '), isNull);
      });

      test('"Epicerie" next to "Épicerie" is allowed', () async {
        await seedProduct('p-epi', 'Épicerie');
        final result = await quickAddOffline('Epicerie');
        expect(result, isNotNull);
        expect(result!.productName, 'Epicerie');
      });

      test('several fold candidates do not throw; case match is a duplicate',
          () async {
        await seedProduct('p-cafe-1', 'Café');
        await seedProduct('p-cafe-2', 'Cafe');
        expect(await quickAddOffline('CAFÉ'), isNull);
      });

      test('identical non-Latin name "ЖУК" is a duplicate', () async {
        await seedProduct('p-zhuk', 'ЖУК');
        expect(await quickAddOffline('ЖУК'), isNull);
      });
    });

    test('quickAdd state transitions to AsyncData on success', () async {
      when(() => mockDatasource.quickAdd(
            sessionId: any(named: 'sessionId'),
            name: any(named: 'name'),
            categoryId: any(named: 'categoryId'),
            physicalQty: any(named: 'physicalQty'),
          )).thenAnswer((_) async => _fakeResult);

      final container = buildContainer();
      addTearDown(container.dispose);

      final notifier =
          container.read(quickAddProductNotifierProvider.notifier);
      await notifier.quickAdd(
        sessionId: 'session-001',
        name: 'Robe Wax L',
        categoryId: 'cat-001',
        physicalQty: 3,
      );

      final state = container.read(quickAddProductNotifierProvider);
      expect(state, isA<AsyncData>());
    });
  });
}

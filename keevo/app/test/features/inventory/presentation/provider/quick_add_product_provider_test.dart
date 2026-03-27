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

      // Verify 3 sync operations were queued
      verify(() => mockSyncService.queueOperation(
            operation: 'CREATE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'RECORD_STOCK_ENTRY',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verify(() => mockSyncService.queueOperation(
            operation: 'SAVE_INVENTORY_COUNT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
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

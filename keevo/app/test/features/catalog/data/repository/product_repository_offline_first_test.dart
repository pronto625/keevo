/// product_repository_offline_first_test.dart — Story 5.6 AC2 + AC10
///
/// TDD RED: verifies [ProductRepositoryImpl] offline-first writes for
/// create(), update(), archive(), unarchive().
library;

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/data/datasource/local_product_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_csv_import_datasource.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:keevo/features/catalog/data/repository/product_repository_impl.dart';
import 'package:keevo/features/catalog/domain/exception/product_exception.dart';
import 'package:keevo/features/catalog/domain/model/csv_import_result.dart';
import 'package:keevo/features/catalog/domain/model/product_model.dart';
import 'package:keevo/features/catalog/domain/model/product_response_dto.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:keevo_core/sync/sync_trigger_dispatcher.dart';
import 'package:mocktail/mocktail.dart';

class MockLocalProductDataSource extends Mock implements LocalProductDataSource {}
class MockRemoteProductDataSource extends Mock implements RemoteProductDataSource {}
class MockRemoteCsvImportDataSource extends Mock implements RemoteCsvImportDataSource {}
class MockConnectivityService extends Mock implements ConnectivityService {}
class MockSyncService extends Mock implements SyncService {}
class MockSyncTriggerDispatcher extends Mock implements SyncTriggerDispatcher {}

class FakeProductModel extends Fake implements ProductModel {}

ProductModel _testProduct({String id = 'prod-uuid-5-6'}) => ProductModel(
      id: id,
      name: 'Savon Test',
      price: 500,
      buyPrice: 300,
      transportCost: 0,
      createdAt: DateTime(2026, 4, 22),
      updatedAt: DateTime(2026, 4, 22),
    );

void main() {
  late MockLocalProductDataSource mockLocal;
  late MockRemoteProductDataSource mockRemote;
  late MockRemoteCsvImportDataSource mockRemoteCsv;
  late MockConnectivityService mockConnectivity;
  late MockSyncService mockSyncService;
  late MockSyncTriggerDispatcher mockDispatcher;
  late ProductRepositoryImpl repo;

  setUpAll(() {
    registerFallbackValue(FakeProductModel());
    registerFallbackValue(<String, dynamic>{});
    registerFallbackValue(Uint8List(0));
    registerFallbackValue(<String, String>{});
  });

  setUp(() {
    mockLocal = MockLocalProductDataSource();
    mockRemote = MockRemoteProductDataSource();
    mockRemoteCsv = MockRemoteCsvImportDataSource();
    mockConnectivity = MockConnectivityService();
    mockSyncService = MockSyncService();
    mockDispatcher = MockSyncTriggerDispatcher();
    repo = ProductRepositoryImpl(
      local: mockLocal,
      remote: mockRemote,
      remoteCsv: mockRemoteCsv,
      connectivity: mockConnectivity,
      syncService: mockSyncService,
      syncTriggerDispatcher: mockDispatcher,
    );
  });

  group('AC2 — create() offline-first', () {
    // Helper: stub _local.insert(...) with all optional named params.
    void _stubInsert(ProductModel returnValue) {
      when(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).thenAnswer((_) async => returnValue);
    }

    test('create_writesLocalFirst_withGeneratedUUID', () async {
      // _local.insert() generates UUID + persists locally (queue is internal to datasource).
      _stubInsert(_testProduct(id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'));
      when(() => mockLocal.search(any())).thenAnswer((_) async => []);
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      final product = await repo.create(name: 'Nouveau produit', price: 500);

      expect(product.id.length, 36, reason: 'UUID v4 is 36 chars');
      verify(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).called(1);
      verifyNever(() => mockRemote.create(any()));
    });

    test('create_triggersDispatcherAfterLocalWrite', () async {
      // CREATE_PRODUCT queue is enqueued inside _local.insert() (datasource responsibility).
      // Repo responsibility: trigger push after local write.
      _stubInsert(_testProduct(id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'));
      when(() => mockLocal.search(any())).thenAnswer((_) async => []);
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.create(name: 'Nouveau produit', price: 500);

      verify(() => mockDispatcher.triggerPushIfIdle()).called(1);
    });

    test('create_duplicateName_throwsProductException_beforeWrite', () async {
      final dupe = ProductModel(
        id: 'existing-1',
        name: 'Dupe',
        price: 500,
        buyPrice: 0,
        transportCost: 0,
        createdAt: DateTime(2026, 4, 22),
        updatedAt: DateTime(2026, 4, 22),
      );
      when(() => mockLocal.search('Dupe')).thenAnswer((_) async => [dupe]);
      when(() => mockLocal.search(any())).thenAnswer((_) async => [dupe]);

      await expectLater(
        () => repo.create(name: 'Dupe', price: 500),
        throwsA(isA<ProductException>()),
      );
      verifyNever(() => mockLocal.insert(
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          ));
    });

    // v1s-16-16: search() candidates are accent-insensitive; equality stays
    // case-only, like the backend `lower(name)` unique index.
    ProductModel named(String name) => ProductModel(
          id: 'existing-$name',
          name: name,
          price: 500,
          buyPrice: 0,
          transportCost: 0,
          createdAt: DateTime(2026, 4, 22),
          updatedAt: DateTime(2026, 4, 22),
        );

    test('create "épicerie" next to "Épicerie" is a duplicate (non-ASCII case)',
        () async {
      when(() => mockLocal.search(any()))
          .thenAnswer((_) async => [named('Épicerie')]);

      await expectLater(
        () => repo.create(name: 'épicerie', price: 500),
        throwsA(isA<ProductException>()),
      );
    });

    test('createDraft "épicerie" next to "Épicerie" is a duplicate', () async {
      when(() => mockLocal.search(any()))
          .thenAnswer((_) async => [named('Épicerie')]);

      await expectLater(
        () => repo.createDraft(
            name: 'épicerie', priceVente: 500, categoryId: 'cat-1'),
        throwsA(isA<ProductException>()),
      );
    });

    test('create "Cafe" next to "Café" is allowed (accents are distinct)',
        () async {
      _stubInsert(_testProduct(id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12'));
      when(() => mockLocal.search(any()))
          .thenAnswer((_) async => [named('Café')]);
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.create(name: 'Cafe', price: 500);

      verify(() => mockLocal.insert(
            name: 'Cafe',
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).called(1);
    });
  });

  group('AC2 — update() offline-first', () {
    test('update_writesLocalFirst_queuesUpdateProduct', () async {
      final callOrder = <String>[];
      when(() => mockLocal.updateById(
            id: any(named: 'id'),
            name: any(named: 'name'),
            description: any(named: 'description'),
            sku: any(named: 'sku'),
            categoryId: any(named: 'categoryId'),
            price: any(named: 'price'),
            buyPrice: any(named: 'buyPrice'),
            transportCost: any(named: 'transportCost'),
            photoUrl: any(named: 'photoUrl'),
          )).thenAnswer((_) async {
        callOrder.add('local');
        return _testProduct();
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.update(id: 'prod-uuid-5-6', name: 'Updated');

      expect(callOrder, ['local', 'queue'],
          reason: 'local write must precede queue');
      verify(() => mockSyncService.queueOperation(
            operation: 'UPDATE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
      verifyNever(() => mockRemote.update(any(), any()));
    });
  });

  group('AC2 — archive() offline-first', () {
    test('archive_writesLocalFirst_queuesArchiveProduct', () async {
      final callOrder = <String>[];
      when(() => mockLocal.archiveById(any())).thenAnswer((_) async {
        callOrder.add('local');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.archive('prod-uuid-5-6');

      expect(callOrder, ['local', 'queue']);
      verify(() => mockSyncService.queueOperation(
            operation: 'ARCHIVE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  group('AC2 — unarchive() offline-first', () {
    test('unarchive_writesLocalFirst_queuesUnarchiveProduct', () async {
      final callOrder = <String>[];
      when(() => mockLocal.unarchiveById(any())).thenAnswer((_) async {
        callOrder.add('local');
      });
      when(() => mockSyncService.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenAnswer((_) async {
        callOrder.add('queue');
      });
      when(() => mockDispatcher.triggerPushIfIdle()).thenReturn(null);

      await repo.unarchive('prod-uuid-5-6');

      expect(callOrder, ['local', 'queue']);
      verify(() => mockSyncService.queueOperation(
            operation: 'UNARCHIVE_PRODUCT',
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).called(1);
    });
  });

  // Device test: imported products showed under "Rupture" until the next
  // sync — the import must pull the stock levels too.
  test('importCsv pulls deltas (stock levels) after a successful import',
      () async {
    when(() => mockRemoteCsv.importCsv(
          csvBytes: any(named: 'csvBytes'),
          fileName: any(named: 'fileName'),
          columnMapping: any(named: 'columnMapping'),
          storeId: any(named: 'storeId'),
        )).thenAnswer((_) async => const CsvImportResult(imported: 2, skipped: 0));
    when(() => mockSyncService.pull()).thenAnswer((_) async {});
    when(() => mockRemote.getAll()).thenAnswer((_) async => []);
    when(() => mockLocal.getAll()).thenAnswer((_) async => []);
    when(() => mockSyncService.unsyncedEntityIds()).thenAnswer((_) async => {});

    final result = await repo.importCsv(
      csvBytes: Uint8List.fromList([1]),
      fileName: 'f.csv',
      columnMapping: const {'nameColumn': 'nom'},
    );

    expect(result.imported, 2);
    verify(() => mockSyncService.pull()).called(1);
  });

  test('importCsv still succeeds when the follow-up pull fails', () async {
    when(() => mockRemoteCsv.importCsv(
          csvBytes: any(named: 'csvBytes'),
          fileName: any(named: 'fileName'),
          columnMapping: any(named: 'columnMapping'),
          storeId: any(named: 'storeId'),
        )).thenAnswer((_) async => const CsvImportResult(imported: 1, skipped: 0));
    when(() => mockSyncService.pull()).thenThrow(Exception('offline'));
    when(() => mockRemote.getAll()).thenAnswer((_) async => []);
    when(() => mockLocal.getAll()).thenAnswer((_) async => []);
    when(() => mockSyncService.unsyncedEntityIds()).thenAnswer((_) async => {});

    final result = await repo.importCsv(
      csvBytes: Uint8List.fromList([1]),
      fileName: 'f.csv',
      columnMapping: const {'nameColumn': 'nom'},
    );
    expect(result.imported, 1);
  });

  // Story 13.8b: only the products with (or referenced by) a pending or held
  // operation keep their local version; a held operation on another entity
  // no longer freezes the whole catalogue refresh.
  group('syncFromRemote() with held operations', () {
    ProductResponseDto dto(String id, String name) => ProductResponseDto(
          id: id,
          name: name,
          sku: '',
          createdAt: '2026-10-30T09:00:00.000Z',
          updatedAt: '2026-10-30T09:00:00.000Z',
        );

    setUp(() {
      when(() => mockLocal.upsert(any()))
          .thenAnswer((inv) async => inv.positionalArguments[0] as ProductModel);
      when(() => mockLocal.archiveById(any())).thenAnswer((_) async {});
    });

    test('held queue on an unrelated entity: products still refresh',
        () async {
      when(() => mockRemote.getAll())
          .thenAnswer((_) async => [dto('p-1', 'Savon')]);
      when(() => mockLocal.getAll())
          .thenAnswer((_) async => [_testProduct(id: 'p-gone')]);
      when(() => mockSyncService.unsyncedEntityIds())
          .thenAnswer((_) async => {'client-1'});

      await repo.syncFromRemote();

      final upserted = verify(() => mockLocal.upsert(captureAny()))
          .captured
          .cast<ProductModel>()
          .map((p) => p.id);
      expect(upserted, ['p-1']);
      verify(() => mockLocal.archiveById('p-gone')).called(1);
    });

    test('a product with a held operation is neither replaced nor archived',
        () async {
      when(() => mockRemote.getAll()).thenAnswer(
          (_) async => [dto('p-held', 'Ancien nom'), dto('p-1', 'Savon')]);
      when(() => mockLocal.getAll()).thenAnswer((_) async =>
          [_testProduct(id: 'p-held'), _testProduct(id: 'p-new-held')]);
      when(() => mockSyncService.unsyncedEntityIds())
          .thenAnswer((_) async => {'p-held', 'p-new-held'});

      await repo.syncFromRemote();

      final upserted = verify(() => mockLocal.upsert(captureAny()))
          .captured
          .cast<ProductModel>()
          .map((p) => p.id);
      expect(upserted, ['p-1']);
      verifyNever(() => mockLocal.archiveById(any()));
    });
  });
}

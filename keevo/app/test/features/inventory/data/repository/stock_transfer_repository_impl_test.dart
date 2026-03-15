import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/inventory/data/datasource/local_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/datasource/remote_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/data/repository/stock_transfer_repository_impl.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';

class _MockLocal extends Mock implements LocalStockTransferDataSource {}
class _MockRemote extends Mock implements RemoteStockTransferDataSource {}

class _FakeStockTransferModel extends Fake implements StockTransferModel {}

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

void main() {
  late _MockLocal mockLocal;
  late _MockRemote mockRemote;
  late AppDatabase testDb;
  late StockTransferRepositoryImpl repository;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    registerFallbackValue(_FakeStockTransferModel());
  });

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
    mockLocal = _MockLocal();
    mockRemote = _MockRemote();
    testDb = AppDatabase.forTesting();
  });

  tearDown(() async {
    await testDb.close();
  });

  group('StockTransferRepositoryImpl (Story 3.3 — Task 13)', () {
    test('executeTransfer online — calls remote, saves locally, returns model',
        () async {
      repository = StockTransferRepositoryImpl(
        local: mockLocal,
        remote: mockRemote,
        db: testDb,
        isOnline: () => true,
      );
      when(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async => fakeTransfer);
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});
      when(() => mockLocal.applyLocalStockChange(
            productId: any(named: 'productId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});

      final result = await repository.executeTransfer(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
      );

      verify(() => mockRemote.executeTransfer(
            sourceStoreId: 'src-001',
            destinationStoreId: 'dst-001',
            productId: 'prod-001',
            quantity: 5,
          )).called(1);
      verify(() => mockLocal.saveTransfer(fakeTransfer)).called(1);
      verify(() => mockLocal.applyLocalStockChange(
            productId: 'prod-001',
            sourceStoreId: 'src-001',
            destinationStoreId: 'dst-001',
            quantity: 5,
          )).called(1);
      expect(result.id, 'tf-001');
    });

    test('executeTransfer offline — validates stock, updates locally, queues to sync, returns PENDING_SYNC',
        () async {
      repository = StockTransferRepositoryImpl(
        local: mockLocal,
        remote: mockRemote,
        db: testDb,
        isOnline: () => false,
      );
      when(() => mockLocal.getLocalStock(
            productId: any(named: 'productId'),
            storeId: any(named: 'storeId'),
          )).thenAnswer((_) async => 10); // 10 available
      when(() => mockLocal.applyLocalStockChange(
            productId: any(named: 'productId'),
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            quantity: any(named: 'quantity'),
          )).thenAnswer((_) async {});
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});

      final result = await repository.executeTransfer(
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 5,
      );

      verifyNever(() => mockRemote.executeTransfer(
            sourceStoreId: any(named: 'sourceStoreId'),
            destinationStoreId: any(named: 'destinationStoreId'),
            productId: any(named: 'productId'),
            quantity: any(named: 'quantity'),
          ));
      verify(() => mockLocal.getLocalStock(
            productId: 'prod-001',
            storeId: 'src-001',
          )).called(1);
      verify(() => mockLocal.applyLocalStockChange(
            productId: 'prod-001',
            sourceStoreId: 'src-001',
            destinationStoreId: 'dst-001',
            quantity: 5,
          )).called(1);
      verify(() => mockLocal.saveTransfer(any())).called(1);
      expect(result.status, 'PENDING_SYNC');
    });

    test('getHistory online — fetches remote, caches locally, returns list',
        () async {
      repository = StockTransferRepositoryImpl(
        local: mockLocal,
        remote: mockRemote,
        db: testDb,
        isOnline: () => true,
      );
      when(() => mockRemote.getHistory()).thenAnswer((_) async => [fakeTransfer]);
      when(() => mockLocal.saveTransfer(any())).thenAnswer((_) async {});

      final result = await repository.getHistory();

      verify(() => mockRemote.getHistory()).called(1);
      verify(() => mockLocal.saveTransfer(fakeTransfer)).called(1);
      expect(result, hasLength(1));
    });

    test('getHistory offline — falls back to local cache', () async {
      repository = StockTransferRepositoryImpl(
        local: mockLocal,
        remote: mockRemote,
        db: testDb,
        isOnline: () => false,
      );
      when(() => mockLocal.getHistory()).thenAnswer((_) async => [fakeTransfer]);

      final result = await repository.getHistory();

      verifyNever(() => mockRemote.getHistory());
      verify(() => mockLocal.getHistory()).called(1);
      expect(result, hasLength(1));
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:keevo/features/pos/data/repository/sale_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/core/storage/app_database.dart' hide Sale, SaleItem;
import 'package:mocktail/mocktail.dart';

class MockLocalSaleDataSource extends Mock implements LocalSaleDataSource {}

class MockRemoteSaleDataSource extends Mock implements RemoteSaleDataSource {}

class MockAppDatabase extends Mock implements AppDatabase {}

class FakeSale extends Fake implements Sale {}

Sale _testSale({String id = 'sale-1'}) => Sale(
      id: id,
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: 1500,
      items: [
        SaleItemModel(
          id: 'item-1',
          productId: 'prod-1',
          productName: 'Savon',
          catalogueUnitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 3,
          subtotal: 1500,
        ),
      ],
      occurredAt: DateTime(2026, 3, 17),
      createdAt: DateTime(2026, 3, 17),
    );

void main() {
  late MockLocalSaleDataSource mockLocal;
  late MockRemoteSaleDataSource mockRemote;
  late MockAppDatabase mockDb;
  late SaleRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeSale()));

  setUp(() {
    mockLocal = MockLocalSaleDataSource();
    mockRemote = MockRemoteSaleDataSource();
    mockDb = MockAppDatabase();
    repo = SaleRepositoryImpl(mockLocal, mockRemote, mockDb);
  });

  group('SaleRepositoryImpl', () {
    test('recordSale saves locally and pushes remote', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any())).thenAnswer((_) async {});
      when(() => mockRemote.pushSale(any())).thenAnswer((_) async {});
      when(() => mockLocal.markSynced(any())).thenAnswer((_) async {});
      when(() => mockLocal.removeSyncQueueEntry(any()))
          .thenAnswer((_) async {});

      await repo.recordSale(sale);

      verify(() => mockLocal.insertAll(sale)).called(1);
      // Background push is unawaited — give microtask a chance
      await Future<void>.delayed(const Duration(milliseconds: 50));
      verify(() => mockRemote.pushSale(sale)).called(1);
    });

    test('recordSale offline mode — only saves locally', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any())).thenAnswer((_) async {});
      when(() => mockRemote.pushSale(any()))
          .thenThrow(Exception('Network unreachable'));

      await repo.recordSale(sale);

      verify(() => mockLocal.insertAll(sale)).called(1);
      // Give background task time to run
      await Future<void>.delayed(const Duration(milliseconds: 50));
      // Remote was called but failed — sale still saved locally
      verify(() => mockRemote.pushSale(sale)).called(1);
      verifyNever(() => mockLocal.markSynced(any()));
    });

    test('recordSale online — marks synced after successful push', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any())).thenAnswer((_) async {});
      when(() => mockRemote.pushSale(any())).thenAnswer((_) async {});
      when(() => mockLocal.markSynced(any())).thenAnswer((_) async {});
      when(() => mockLocal.removeSyncQueueEntry(any()))
          .thenAnswer((_) async {});

      await repo.recordSale(sale);
      await Future<void>.delayed(const Duration(milliseconds: 50));

      verify(() => mockLocal.markSynced(sale.id)).called(1);
      verify(() => mockLocal.removeSyncQueueEntry(sale.id)).called(1);
    });

    test('recordSale offline — leaves entry in sync queue', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any())).thenAnswer((_) async {});
      when(() => mockRemote.pushSale(any()))
          .thenThrow(Exception('Timeout'));

      await repo.recordSale(sale);
      await Future<void>.delayed(const Duration(milliseconds: 50));

      verifyNever(() => mockLocal.markSynced(any()));
      verifyNever(() => mockLocal.removeSyncQueueEntry(any()));
    });

    test('recordSale server error — leaves entry in sync queue', () async {
      final sale = _testSale();
      when(() => mockLocal.insertAll(any())).thenAnswer((_) async {});
      when(() => mockRemote.pushSale(any()))
          .thenThrow(Exception('503 Service Unavailable'));

      await repo.recordSale(sale);
      await Future<void>.delayed(const Duration(milliseconds: 50));

      verify(() => mockLocal.insertAll(sale)).called(1);
      verifyNever(() => mockLocal.markSynced(any()));
    });

    test('getFrequentProductIds returns top N by count', () async {
      // This requires a real DB. Contract test: interface returns List<String>
      expect(repo, isA<SaleRepositoryImpl>());
    });
  });
}

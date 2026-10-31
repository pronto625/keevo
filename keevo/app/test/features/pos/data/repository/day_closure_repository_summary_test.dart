import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/local_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/datasource/remote_day_closure_datasource.dart';
import 'package:keevo/features/pos/data/repository/day_closure_repository_impl.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/domain/model/payment_mode_enum.dart';
import 'package:keevo/features/pos/domain/model/sale_model.dart';
import 'package:keevo/features/pos/domain/model/sales_history_filter.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';

class MockLocal extends Mock implements LocalDayClosureDataSource {}

class MockRemote extends Mock implements RemoteDayClosureDataSource {}

class MockConnectivity extends Mock implements ConnectivityService {}

class MockSync extends Mock implements SyncService {}

class FakeFilter extends Fake implements SalesHistoryFilter {}

Sale _sale(String id, DateTime at,
        {String status = 'COMPLETED', int total = 1000}) =>
    Sale(
      id: id,
      storeId: 'store-1',
      employeeId: 'emp-1',
      paymentMode: PaymentModeEnum.cash,
      totalAmount: total,
      status: status,
      items: [
        SaleItemModel(
          id: 'i-$id',
          productId: 'p1',
          productName: 'Savon',
          catalogueUnitPrice: total,
          appliedUnitPrice: total,
          quantity: 1,
          subtotal: total,
        ),
      ],
      occurredAt: at,
      createdAt: at,
    );

void main() {
  late MockLocal local;
  late MockRemote remote;
  late DayClosureRepositoryImpl repo;

  setUpAll(() => registerFallbackValue(FakeFilter()));

  setUp(() {
    local = MockLocal();
    remote = MockRemote();
    final connectivity = MockConnectivity();
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    repo = DayClosureRepositoryImpl(
      localDataSource: local,
      remoteDataSource: remote,
      connectivity: connectivity,
      syncService: MockSync(),
    );
  });

  group('computeTodaySummary — owner online', () {
    test('fetches every page, counts pending, only since last closure',
        () async {
      final now = DateTime.now();
      final closedAt = now.subtract(const Duration(minutes: 30));
      when(() => local.getLastClosure('store-1')).thenAnswer((_) async =>
          DayClosure(
            id: 'c1',
            storeId: 'store-1',
            actorId: 'owner',
            closedAt: closedAt,
            summary: const DayClosureSummary(
              totalSales: 0,
              totalRevenue: 0,
              cashAmount: 0,
              momoAmount: 0,
              topProductQty: 0,
              pendingSalesCount: 0,
              pendingSalesTotal: 0,
            ),
            isAutomatic: false,
            synced: true,
          ));
      when(() => remote.fetchAllSalesHistory(any())).thenAnswer((_) async => [
            _sale('after', now.subtract(const Duration(minutes: 5))),
            _sale('pending', now.subtract(const Duration(minutes: 4)),
                status: 'PENDING_VALIDATION', total: 700),
            _sale('before', closedAt.subtract(const Duration(minutes: 1))),
          ]);

      final summary = await repo.computeTodaySummary('store-1', null);

      expect(summary.totalSales, 1);
      expect(summary.totalRevenue, 1000);
      expect(summary.topProductRevenue, 1000);
      expect(summary.pendingSalesCount, 1);
      expect(summary.pendingSalesTotal, 700);
      final filter = verify(() => remote.fetchAllSalesHistory(captureAny()))
          .captured
          .single as SalesHistoryFilter;
      expect(filter.from.isAfter(closedAt), isFalse);
    });

    test('no closure → whole day', () async {
      when(() => local.getLastClosure('store-1')).thenAnswer((_) async => null);
      when(() => remote.fetchAllSalesHistory(any())).thenAnswer((_) async => [
            _sale('s1', DateTime.now()),
          ]);

      final summary = await repo.computeTodaySummary('store-1', null);

      expect(summary.totalSales, 1);
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/data/datasource/remote_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/data/repository/dashboard_repository_impl.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:mocktail/mocktail.dart';

class _MockLocalDashboardDatasource extends Mock
    implements LocalDashboardDatasource {}

class _MockRemoteDashboardDatasource extends Mock
    implements RemoteDashboardDatasource {}

class _MockConnectivityService extends Mock implements ConnectivityService {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late _MockLocalDashboardDatasource mockLocal;
  late _MockRemoteDashboardDatasource mockRemote;
  late _MockConnectivityService mockConnectivity;
  late DashboardRepositoryImpl repository;

  setUp(() {
    mockLocal = _MockLocalDashboardDatasource();
    mockRemote = _MockRemoteDashboardDatasource();
    mockConnectivity = _MockConnectivityService();
    repository = DashboardRepositoryImpl(mockLocal, mockRemote, mockConnectivity);
  });

  final snapshot = DashboardSnapshot(
    todayCA: 1000,
    yesterdayCA: 800,
    dayBeforeYesterdayCA: 700,
    trendPercent: 14.3,
    totalTransactionsMonth: 50,
    averageBasketMonth: 3000,
    prevMonthTransactions: 40,
    prevMonthAverageBasket: 2500,
    lowStockCount: 2,
    weeklyTopProducts: const [],
    weeklyWorstProducts: const [],
    dailyCALast30: const [],
    storeOverviews: const [],
    todaySalesCount: 5,
  );

  test('getDashboardSnapshot uses remote when online', () async {
    when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
    when(() => mockRemote.getDashboardSummary())
        .thenAnswer((_) async => snapshot);

    final result = await repository.getDashboardSnapshot();
    expect(result.todayCA, 1000);
    verify(() => mockRemote.getDashboardSummary()).called(1);
    verifyNever(() => mockLocal.getDashboardSnapshot());
  });

  test('getDashboardSnapshot falls back to local when offline', () async {
    when(() => mockConnectivity.isOnline()).thenAnswer((_) async => false);
    when(() => mockLocal.getDashboardSnapshot())
        .thenAnswer((_) async => snapshot);

    final result = await repository.getDashboardSnapshot();
    expect(result.todayCA, 1000);
    verify(() => mockLocal.getDashboardSnapshot()).called(1);
    verifyNever(() => mockRemote.getDashboardSummary());
  });

  test('getDashboardSnapshot falls back to local on remote error', () async {
    when(() => mockConnectivity.isOnline()).thenAnswer((_) async => true);
    when(() => mockRemote.getDashboardSummary()).thenThrow(Exception('Network error'));
    when(() => mockLocal.getDashboardSnapshot())
        .thenAnswer((_) async => snapshot);

    final result = await repository.getDashboardSnapshot();
    expect(result.todayCA, 1000);
    verify(() => mockLocal.getDashboardSnapshot()).called(1);
  });

  test('getStoreOverviews delegates to local datasource', () async {
    final overviews = [
      const StoreOverview(
        storeId: 's1',
        storeName: 'Store 1',
        todayCA: 5000,
        yesterdayCA: 4000,
        employeeCount: 3,
        statusLevel: StoreStatusLevel.stable,
      ),
    ];
    when(() => mockLocal.getStoreOverviews())
        .thenAnswer((_) async => overviews);

    final result = await repository.getStoreOverviews();
    expect(result.length, 1);
    expect(result.first.storeName, 'Store 1');
    verify(() => mockLocal.getStoreOverviews()).called(1);
  });
}

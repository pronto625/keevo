import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/domain/repository/dashboard_repository.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:keevo/core/di/providers.dart';

class _MockDashboardRepository extends Mock implements DashboardRepository {}

class _MockLocalDashboardDatasource extends Mock
    implements LocalDashboardDatasource {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  final snapshot = DashboardSnapshot(
    todayCA: 50000,
    yesterdayCA: 40000,
    dayBeforeYesterdayCA: 35000,
    trendPercent: 14.3,
    totalTransactionsMonth: 120,
    averageBasketMonth: 5000,
    prevMonthTransactions: 100,
    prevMonthAverageBasket: 4500,
    lowStockCount: 3,
    weeklyTopProducts: const [],
    weeklyWorstProducts: const [],
    dailyCALast30: const [],
    storeOverviews: const [],
    todaySalesCount: 8,
  );

  group('dashboardSnapshotProvider', () {
    test('returns snapshot from repository', () async {
      final mockRepo = _MockDashboardRepository();
      when(() => mockRepo.getDashboardSnapshot())
          .thenAnswer((_) async => snapshot);

      final container = ProviderContainer(overrides: [
        dashboardRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      final result = await container.read(dashboardSnapshotProvider.future);
      expect(result.todayCA, 50000);
      expect(result.yesterdayCA, 40000);
    });
  });

  group('storeOverviewsProvider', () {
    test('returns store overviews from snapshot', () async {
      final mockRepo = _MockDashboardRepository();
      const overviews = [
        StoreOverview(
          storeId: 's1',
          storeName: 'Boutique A',
          todayCA: 10000,
          yesterdayCA: 8000,
          employeeCount: 2,
          statusLevel: StoreStatusLevel.stable,
        ),
      ];
      when(() => mockRepo.getDashboardSnapshot()).thenAnswer((_) async =>
          DashboardSnapshot(
            todayCA: 0,
            yesterdayCA: 0,
            dayBeforeYesterdayCA: 0,
            trendPercent: 0,
            totalTransactionsMonth: 0,
            averageBasketMonth: 0,
            prevMonthTransactions: 0,
            prevMonthAverageBasket: 0,
            lowStockCount: 0,
            weeklyTopProducts: const [],
            weeklyWorstProducts: const [],
            dailyCALast30: const [],
            storeOverviews: overviews,
            todaySalesCount: 0,
          ));

      final container = ProviderContainer(overrides: [
        dashboardRepositoryProvider.overrideWithValue(mockRepo),
      ]);
      addTearDown(container.dispose);

      final result = await container.read(storeOverviewsProvider.future);
      expect(result.length, 1);
      expect(result.first.storeName, 'Boutique A');
    });
  });

  group('currentUserFirstNameProvider', () {
    test('returns firstName from SharedPreferences', () async {
      SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Amadou'});
      final prefs = await SharedPreferences.getInstance();

      final container = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
      ]);
      addTearDown(container.dispose);

      final result = container.read(currentUserFirstNameProvider);
      expect(result, 'Amadou');
    });

    test('returns null when not set', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      final container = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
      ]);
      addTearDown(container.dispose);

      final result = container.read(currentUserFirstNameProvider);
      expect(result, isNull);
    });
  });

  group('isMotivationalDismissedProvider', () {
    test('returns false when no dismissed date set', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      final container = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
      ]);
      addTearDown(container.dispose);

      expect(container.read(isMotivationalDismissedProvider), isFalse);
    });
  });

  group('dismissMotivationalMessage', () {
    test('stores today date string in shared preferences', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await dismissMotivationalMessage(prefs);
      expect(prefs.getString(kMotivationalDismissedDate), isNotNull);
    });
  });

  // v1s-16-19: remote series are sparse — the online chart must show the same
  // 12 weekly slots as the offline one.
  group('remote chart zero-fill (v1s-16-19)', () {
    final now = DateTime(2026, 1, 7, 15); // Wednesday
    final sparseWeek = [DailyCA(date: DateTime(2025, 12, 29), amount: 800)];

    DashboardSnapshot remote({List<StoreOverview> stores = const []}) =>
        DashboardSnapshot(
          todayCA: 0,
          yesterdayCA: 0,
          dayBeforeYesterdayCA: 0,
          trendPercent: 0,
          totalTransactionsMonth: 0,
          averageBasketMonth: 0,
          prevMonthTransactions: 0,
          prevMonthAverageBasket: 0,
          lowStockCount: 0,
          weeklyTopProducts: const [],
          weeklyWorstProducts: const [],
          dailyCALast30: const [],
          weeklyCA: sparseWeek,
          storeOverviews: stores,
          todaySalesCount: 0,
        );

    ProviderContainer container(DashboardSnapshot snap) {
      final repo = _MockDashboardRepository();
      when(() => repo.getDashboardSnapshot()).thenAnswer((_) async => snap);
      final c = ProviderContainer(overrides: [
        dashboardRepositoryProvider.overrideWithValue(repo),
        dashboardClockProvider.overrideWithValue(() => now),
        localDashboardDatasourceProvider
            .overrideWithValue(_MockLocalDashboardDatasource()),
      ]);
      addTearDown(c.dispose);
      return c;
    }

    void expectWeekly(List<DailyCA> weeks) {
      expect(weeks, hasLength(12));
      expect(weeks.first.date, DateTime(2025, 10, 20));
      expect(weeks.last.date, DateTime(2026, 1, 5));
      expect(weeks.every((w) => w.date.weekday == DateTime.monday), isTrue);
      expect(weeks[10].date, DateTime(2025, 12, 29));
      expect(weeks[10].amount, 800);
      expect(weeks.fold<int>(0, (a, w) => a + w.amount), 800);
    }

    test('all stores: one remote week -> 12 slots, sale on its Monday',
        () async {
      final c = container(remote());
      c.read(chartPeriodProvider.notifier).state = ChartPeriod.weekly;

      expectWeekly(await c.read(chartDataProvider.future));
    });

    test('one store: one remote week -> 12 slots, sale on its Monday',
        () async {
      final c = container(remote(stores: [
        StoreOverview(
          storeId: 's1',
          storeName: 'Boutique A',
          todayCA: 0,
          yesterdayCA: 0,
          employeeCount: 1,
          statusLevel: StoreStatusLevel.stable,
          weeklyCA: sparseWeek,
        ),
      ]));
      c.read(storeChartPeriodProvider('s1').notifier).state =
          ChartPeriod.weekly;

      expectWeekly(await c.read(storeChartDataProvider('s1').future));
    });

    test('monthly (12, YYYY-MM) and yearly (5, YYYY) slot shapes', () {
      final months = zeroFillMonthlyCA(
          [DailyCA(date: DateTime(2025, 12, 1), amount: 5)], now);
      expect(months, hasLength(12));
      expect(months.first.date, DateTime(2025, 2, 1));
      expect(months.last.date, DateTime(2026, 1, 1));
      expect(months[10].amount, 5);

      final years =
          zeroFillYearlyCA([DailyCA(date: DateTime(2024), amount: 7)], now);
      expect(years.map((y) => y.date.year), [2022, 2023, 2024, 2025, 2026]);
      expect(years[2].amount, 7);
    });
  });
}

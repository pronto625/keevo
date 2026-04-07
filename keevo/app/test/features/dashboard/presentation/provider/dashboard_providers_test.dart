import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/domain/repository/dashboard_repository.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:keevo/core/di/providers.dart';

class _MockDashboardRepository extends Mock implements DashboardRepository {}

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
}

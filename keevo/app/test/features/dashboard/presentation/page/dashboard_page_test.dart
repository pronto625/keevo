import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/page/dashboard_page.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
    weeklyTopProducts: const [
      TopProduct(
        productId: 'p1',
        name: 'Riz 5kg',
        unitsSold: 20,
        revenue: 100000,
        sharePercent: 40,
      ),
    ],
    weeklyWorstProducts: const [],
    dailyCALast30: List.generate(
      7,
      (i) => DailyCA(date: DateTime(2025, 1, i + 1), amount: (i + 1) * 5000),
    ),
    storeOverviews: const [],
    todaySalesCount: 8,
  );

  group('DashboardPage', () {
    testWidgets('shows empty state when all data is zero', (tester) async {
      final emptySnapshot = DashboardSnapshot(
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
        storeOverviews: const [],
        todaySalesCount: 0,
      );

      SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Fatou'});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(ProviderScope(
        overrides: [
          dashboardSnapshotProvider.overrideWith((_) => Future.value(emptySnapshot)),
          storeOverviewsProvider.overrideWith((_) => Future.value(const <StoreOverview>[])),
          sharedPreferencesProvider.overrideWithValue(prefs),
        ],
        child: const MaterialApp(home: DashboardPage()),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Bonjour Fatou !'), findsOneWidget);
      expect(find.textContaining('Aucune vente'), findsOneWidget);
      expect(find.text('Actualiser'), findsOneWidget);
    });

    testWidgets('shows dashboard content when data exists', (tester) async {
      SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Simon'});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(ProviderScope(
        overrides: [
          dashboardSnapshotProvider.overrideWith((_) => Future.value(snapshot)),
          storeOverviewsProvider.overrideWith((_) => Future.value(const <StoreOverview>[])),
          chartDataProvider.overrideWith((_) => Future.value(snapshot.dailyCALast30)),
          sharedPreferencesProvider.overrideWithValue(prefs),
        ],
        child: const MaterialApp(home: DashboardPage()),
      ));
      await tester.pumpAndSettle();

      // Hero card shows today CA
      expect(find.textContaining("CA D'AUJOURD'HUI"), findsOneWidget);
      // Metric badges
      expect(find.text("CA d'Hier"), findsOneWidget);
      expect(find.text('Top Produits (7j)'), findsOneWidget);
    });
  });
}

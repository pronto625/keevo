import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/page/dashboard_page.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr');
  });

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
      expect(find.text('Top Produits — toutes boutiques (7j)'), findsOneWidget);
    });
      DashboardSnapshot copyOf(DashboardSnapshot s,
            {int? todayCA,
            int? yesterdayCA,
            int? tx,
            int? prevTx,
            List<StoreOverview>? stores,
            DateTime? asOf}) =>
        DashboardSnapshot(
          todayCA: todayCA ?? s.todayCA,
          yesterdayCA: yesterdayCA ?? s.yesterdayCA,
          dayBeforeYesterdayCA: s.dayBeforeYesterdayCA,
          trendPercent: s.trendPercent,
          totalTransactionsMonth: tx ?? s.totalTransactionsMonth,
          averageBasketMonth: s.averageBasketMonth,
          prevMonthTransactions: prevTx ?? s.prevMonthTransactions,
          prevMonthAverageBasket: s.prevMonthAverageBasket,
          lowStockCount: s.lowStockCount,
          weeklyTopProducts: s.weeklyTopProducts,
          weeklyWorstProducts: s.weeklyWorstProducts,
          dailyCALast30: s.dailyCALast30,
          storeOverviews: stores ?? s.storeOverviews,
          todaySalesCount: s.todaySalesCount,
          asOf: asOf,
        );

    Future<void> pumpWith(WidgetTester tester,
        Future<DashboardSnapshot> Function() load) async {
      SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Simon'});
      final prefs = await SharedPreferences.getInstance();
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dashboardSnapshotProvider.overrideWith((_) => load()),
          storeOverviewsProvider
              .overrideWith((_) => Future.value(const <StoreOverview>[])),
          chartDataProvider
              .overrideWith((_) => Future.value(snapshot.dailyCALast30)),
          sharedPreferencesProvider.overrideWithValue(prefs),
        ],
        child: const MaterialApp(home: DashboardPage()),
      ));
    }

    testWidgets('"Mis à jour" reflects when the data was fetched',
        (tester) async {
      final old = copyOf(snapshot,
          asOf: DateTime.now().subtract(const Duration(hours: 2)));
      await pumpWith(tester, () async => old);
      await tester.pumpAndSettle();

      expect(find.text('Mis à jour il y a 2h'), findsOneWidget);
    });

    testWidgets('no sale yet but stores exist: dashboard (bell, stores) shown',
        (tester) async {
      final noSales = copyOf(snapshot,
          todayCA: 0,
          yesterdayCA: 0,
          tx: 0,
          prevTx: 0,
          stores: const [
            StoreOverview(
              storeId: 's1',
              storeName: 'Boutique Centre',
              todayCA: 0,
              yesterdayCA: 0,
              employeeCount: 1,
              statusLevel: StoreStatusLevel.stable,
            ),
          ]);
      await pumpWith(tester, () async => noSales);
      await tester.pumpAndSettle();

      expect(find.textContaining("CA D'AUJOURD'HUI"), findsOneWidget);
    });

    testWidgets('pull-to-refresh waits for the new data', (tester) async {
      var calls = 0;
      final second = Completer<DashboardSnapshot>();
      await pumpWith(tester, () {
        calls++;
        return calls == 1 ? Future.value(snapshot) : second.future;
      });
      await tester.pumpAndSettle();

      var done = false;
      tester
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh()
          .then((_) => done = true);
      await tester.pump();
      expect(done, isFalse);

      second.complete(snapshot);
      await tester.pumpAndSettle();
      expect(done, isTrue);
    });
  });
}

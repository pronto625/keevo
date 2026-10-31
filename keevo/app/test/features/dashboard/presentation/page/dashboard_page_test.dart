import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/domain/repository/dashboard_repository.dart';
import 'package:keevo/features/dashboard/presentation/page/dashboard_page.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:keevo/features/dashboard/presentation/widget/total_revenue_card.dart';
import 'package:keevo/features/inventory/presentation/page/local_low_stock_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';

class _MockRepo extends Mock implements DashboardRepository {}

const periodTotals = {
  RevenuePeriod.week: PeriodRevenue(amount: 125000, salesCount: 12),
  RevenuePeriod.month: PeriodRevenue(amount: 480000, salesCount: 45),
  RevenuePeriod.year: PeriodRevenue(amount: 2500000, salesCount: 230),
  RevenuePeriod.allTime: PeriodRevenue(amount: 7300000, salesCount: 1),
};

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
    periodRevenue: periodTotals,
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
          periodRevenue: s.periodRevenue,
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

    // J3 — « VOIR TOUT » de « Vos boutiques » ouvre « Mes boutiques »
    // (/stores), pas l'onglet Plus (/settings).
    testWidgets('"VOIR TOUT" opens the stores list', (tester) async {
      final withStores = copyOf(snapshot, stores: const [
        StoreOverview(
          storeId: 's1',
          storeName: 'Boutique Centre',
          todayCA: 1000,
          yesterdayCA: 1000,
          employeeCount: 1,
          statusLevel: StoreStatusLevel.stable,
        ),
      ]);
      SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Simon'});
      final prefs = await SharedPreferences.getInstance();
      final router = GoRouter(
        initialLocation: '/dashboard',
        routes: [
          GoRoute(
              path: '/dashboard', builder: (_, __) => const DashboardPage()),
          GoRoute(
              path: '/stores',
              builder: (_, __) => const Scaffold(body: Text('MES BOUTIQUES'))),
          GoRoute(
              path: '/settings',
              builder: (_, __) => const Scaffold(body: Text('PLUS'))),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dashboardSnapshotProvider.overrideWith((_) async => withStores),
          storeOverviewsProvider
              .overrideWith((_) => Future.value(const <StoreOverview>[])),
          chartDataProvider
              .overrideWith((_) => Future.value(snapshot.dailyCALast30)),
          sharedPreferencesProvider.overrideWithValue(prefs),
        ],
        child: MaterialApp.router(routerConfig: router),
      ));
      await tester.pumpAndSettle();

      // The section sits inside one SliverToBoxAdapter: scroll the text
      // itself into view (scrollUntilVisible stops once the sliver shows).
      await tester.ensureVisible(find.text('VOIR TOUT'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('VOIR TOUT'));
      await tester.pumpAndSettle();

      expect(find.text('MES BOUTIQUES'), findsOneWidget);
      expect(find.text('PLUS'), findsNothing);
      // Poussée (pas remplacée) : le retour ramène au tableau de bord.
      expect(router.canPop(), isTrue);
    });

    group("« Chiffre d'affaires total »", () {
      Finder chip(String label) => find.descendant(
          of: find.byType(TotalRevenueCard),
          matching: find.widgetWithText(ChoiceChip, label));
      final xaf =
          NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0);

      Future<SharedPreferences> pumpTotal(WidgetTester tester,
          {DashboardSnapshot? data, Map<String, Object> prefs = const {}}) async {
        SharedPreferences.setMockInitialValues(
            {kUserFirstNameKey: 'Simon', ...prefs});
        final sp = await SharedPreferences.getInstance();
        await tester.pumpWidget(ProviderScope(
          overrides: [
            dashboardSnapshotProvider.overrideWith((_) async => data ?? snapshot),
            storeOverviewsProvider
                .overrideWith((_) => Future.value(const <StoreOverview>[])),
            chartDataProvider
                .overrideWith((_) => Future.value(snapshot.dailyCALast30)),
            sharedPreferencesProvider.overrideWithValue(sp),
          ],
          child: const MaterialApp(home: DashboardPage()),
        ));
        await tester.pumpAndSettle();
        return sp;
      }

      testWidgets('comes first, then CA du jour, then the metric cards',
          (tester) async {
        await pumpTotal(tester);

        final total = tester.getTopLeft(find.text("Chiffre d'affaires total")).dy;
        final today = tester.getTopLeft(find.text("CA D'AUJOURD'HUI")).dy;
        final yesterday = tester.getTopLeft(find.text("CA d'Hier")).dy;
        expect(total, lessThan(today));
        expect(today, lessThan(yesterday));
        // « CA d'aujourd'hui » keeps its content.
        expect(find.text(xaf.format(50000)), findsOneWidget);
      });

      testWidgets('shows the four periods, « Mois » by default with its count',
          (tester) async {
        await pumpTotal(tester);

        for (final label in ['Semaine', 'Mois', 'Année', 'Depuis le début']) {
          expect(chip(label), findsOneWidget);
        }
        expect(
            tester
                .widget<ChoiceChip>(chip('Mois'))
                .selected,
            isTrue);
        expect(find.text(xaf.format(480000)), findsOneWidget);
        expect(find.text('45 ventes'), findsOneWidget);
        // Server figure: no device-only notice.
        expect(find.text('Données de cet appareil'), findsNothing);
      });

      testWidgets('switching period updates the figure and is remembered',
          (tester) async {
        final sp = await pumpTotal(tester);

        await tester.tap(chip('Semaine'));
        await tester.pumpAndSettle();
        expect(find.text(xaf.format(125000)), findsOneWidget);
        expect(find.text('12 ventes'), findsOneWidget);

        await tester.tap(chip('Depuis le début'));
        await tester.pumpAndSettle();
        expect(find.text(xaf.format(7300000)), findsOneWidget);
        expect(find.text('1 vente'), findsOneWidget);
        expect(sp.getString(kDashboardRevenuePeriodKey), 'allTime');
      });

      testWidgets('restores the period saved on this device', (tester) async {
        await pumpTotal(tester, prefs: {kDashboardRevenuePeriodKey: 'year'});

        expect(
            tester
                .widget<ChoiceChip>(chip('Année'))
                .selected,
            isTrue);
        expect(find.text(xaf.format(2500000)), findsOneWidget);
        expect(find.text('230 ventes'), findsOneWidget);
      });

      // v1s-16-21: « 0 XAF — Aucune vente » right after sign-in, the server
      // totals only after « Actualiser ».
      testWidgets(
          'sign-in: loading state, then the server totals without « Actualiser »',
          (tester) async {
        DashboardSnapshot make(PeriodRevenue r, {bool onDevice = false}) =>
            DashboardSnapshot(
              todayCA: 0,
              yesterdayCA: 0,
              dayBeforeYesterdayCA: 0,
              trendPercent: 0,
              totalTransactionsMonth: 0,
              averageBasketMonth: 0,
              prevMonthTransactions: 0,
              prevMonthAverageBasket: 0,
              lowStockCount: 1,
              weeklyTopProducts: const [],
              weeklyWorstProducts: const [],
              dailyCALast30: const [],
              storeOverviews: const [],
              todaySalesCount: 0,
              periodRevenue: {for (final p in RevenuePeriod.values) p: r},
              computedOnDevice: onDevice,
            );
        final session = StateProvider<String?>((_) => null);
        final server = Completer<DashboardSnapshot>();
        final repo = _MockRepo();
        var calls = 0;
        when(() => repo.getDashboardSnapshot()).thenAnswer((_) => calls++ == 0
            ? Future.value(make(const PeriodRevenue(amount: 0, salesCount: 0),
                onDevice: true))
            : server.future);
        SharedPreferences.setMockInitialValues({
          kUserFirstNameKey: 'Simon',
          kDashboardRevenuePeriodKey: 'allTime',
        });
        final sp = await SharedPreferences.getInstance();
        final container = ProviderContainer(overrides: [
          dashboardRepositoryProvider.overrideWithValue(repo),
          dashboardSessionKeyProvider
              .overrideWith((ref) => ref.watch(session)),
          isOwnerSessionProvider
              .overrideWith((ref) => ref.watch(session) != null),
          storeOverviewsProvider
              .overrideWith((_) => Future.value(const <StoreOverview>[])),
          chartDataProvider
              .overrideWith((_) => Future.value(const <DailyCA>[])),
          sharedPreferencesProvider.overrideWithValue(sp),
        ]);
        addTearDown(container.dispose);
        await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: DashboardPage()),
        ));
        await tester.pumpAndSettle();
        // Computed before the session was known.
        expect(find.text('Aucune vente'), findsOneWidget);

        container.read(session.notifier).state = 'owner@t1|OWNER';
        await tester.pump();
        await tester.pump();
        // Loading: never the previous session's 0.
        expect(find.text('Aucune vente'), findsNothing);
        expect(find.text(xaf.format(0)), findsNothing);

        server.complete(make(const PeriodRevenue(amount: 93500, salesCount: 6)));
        await tester.pumpAndSettle();
        expect(find.text(xaf.format(93500)), findsOneWidget);
        expect(find.text('6 ventes'), findsOneWidget);
        expect(find.text('Données de cet appareil'), findsNothing);
      });

      testWidgets('offline figure is labelled as this device\'s data',
          (tester) async {
        final offline = DashboardSnapshot(
          todayCA: snapshot.todayCA,
          yesterdayCA: snapshot.yesterdayCA,
          dayBeforeYesterdayCA: snapshot.dayBeforeYesterdayCA,
          trendPercent: snapshot.trendPercent,
          totalTransactionsMonth: snapshot.totalTransactionsMonth,
          averageBasketMonth: snapshot.averageBasketMonth,
          prevMonthTransactions: snapshot.prevMonthTransactions,
          prevMonthAverageBasket: snapshot.prevMonthAverageBasket,
          lowStockCount: snapshot.lowStockCount,
          weeklyTopProducts: snapshot.weeklyTopProducts,
          weeklyWorstProducts: const [],
          dailyCALast30: snapshot.dailyCALast30,
          storeOverviews: const [],
          todaySalesCount: snapshot.todaySalesCount,
          periodRevenue: const {
            RevenuePeriod.month: PeriodRevenue(amount: 0, salesCount: 0),
            RevenuePeriod.allTime: PeriodRevenue(amount: 3000, salesCount: 2),
          },
          computedOnDevice: true,
        );
        await pumpTotal(tester,
            data: offline, prefs: {kDashboardRevenuePeriodKey: 'allTime'});

        expect(find.text(xaf.format(3000)), findsOneWidget);
        expect(find.text('2 ventes'), findsOneWidget);
        expect(find.text('Données de cet appareil'), findsOneWidget);

        await tester.tap(chip('Mois'));
        await tester.pumpAndSettle();
        expect(find.text('Aucune vente'), findsOneWidget);
        expect(find.text('Données de cet appareil'), findsOneWidget);
      });
    });
  
    // 13.10b — the real « Stock Bas » tile: Free → local list of every store
    // (the tile counts them all), Premium → multi-store overview.
    group('« Stock Bas » tile', () {
      Future<String> tapTile(WidgetTester tester, String multiStore) async {
        SharedPreferences.setMockInitialValues({kUserFirstNameKey: 'Simon'});
        final prefs = await SharedPreferences.getInstance();
        final router = GoRouter(
          initialLocation: '/dashboard',
          routes: [
            GoRoute(
                path: '/dashboard', builder: (_, __) => const DashboardPage()),
            GoRoute(
                path: '/stock/overview',
                builder: (_, s) => Scaffold(body: Text('OVERVIEW ${s.uri}'))),
            GoRoute(
                path: kLocalLowStockRoute,
                builder: (_, s) => Scaffold(body: Text('LOCAL ${s.uri}'))),
          ],
        );
        addTearDown(router.dispose);
        await tester.pumpWidget(ProviderScope(
          overrides: [
            dashboardSnapshotProvider.overrideWith((_) async => snapshot),
            storeOverviewsProvider
                .overrideWith((_) => Future.value(const <StoreOverview>[])),
            chartDataProvider
                .overrideWith((_) => Future.value(snapshot.dailyCALast30)),
            sharedPreferencesProvider.overrideWithValue(prefs),
            entitlementsOverride({'multi-store': multiStore}),
          ],
          child: MaterialApp.router(routerConfig: router),
        ));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('Stock Bas'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Stock Bas'));
        await tester.pumpAndSettle();
        final shown = find.textContaining(RegExp('^(LOCAL|OVERVIEW) '));
        return tester.widget<Text>(shown).data!.split(' ').last;
      }

      testWidgets('Free → local list, all stores', (tester) async {
        expect(await tapTile(tester, 'DISABLED'), '/stock/low?all=true');
      });

      testWidgets('Premium → multi-store overview filtered on low stock',
          (tester) async {
        expect(await tapTile(tester, 'ENABLED'),
            '/stock/overview?showLowOnly=true');
      });
    });
});
}

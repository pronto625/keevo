import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/page/store_dashboard_page.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('StoreDashboardPage', () {
    testWidgets('shows store detail when found', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          storeOverviewsProvider.overrideWith((_) => Future.value([
                const StoreOverview(
                  storeId: 's1',
                  storeName: 'Boutique Akwa',
                  todayCA: 35000,
                  yesterdayCA: 30000,
                  employeeCount: 5,
                  statusLevel: StoreStatusLevel.stable,
                ),
              ])),
        ],
        child: const MaterialApp(
          home: StoreDashboardPage(storeId: 's1'),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Boutique Akwa'), findsOneWidget);
      expect(find.text('STABLE'), findsOneWidget);
      expect(find.text('Employés'), findsOneWidget);
    });

    testWidgets('shows not found when store id does not match', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          storeOverviewsProvider.overrideWith((_) => Future.value(const <StoreOverview>[])),
        ],
        child: const MaterialApp(
          home: StoreDashboardPage(storeId: 'nonexistent'),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Boutique introuvable'), findsOneWidget);
    });

    testWidgets('shows error state on failure', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          storeOverviewsProvider.overrideWith(
            (_) => Future.error('DB error'),
          ),
        ],
        child: const MaterialApp(
          home: StoreDashboardPage(storeId: 's1'),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Impossible de charger les données'), findsOneWidget);
    });

    // v1s-16-11 follow-up: the overviews derive from the snapshot, so the
    // retry must re-fetch the snapshot (real provider chain, no override).
    testWidgets('Réessayer re-fetches the snapshot and shows the store',
        (tester) async {
      var calls = 0;
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dashboardSnapshotProvider.overrideWith((_) async {
            calls++;
            if (calls == 1) throw StateError('network down');
            return const DashboardSnapshot(
              todayCA: 0,
              yesterdayCA: 0,
              dayBeforeYesterdayCA: 0,
              trendPercent: 0,
              totalTransactionsMonth: 0,
              averageBasketMonth: 0,
              prevMonthTransactions: 0,
              prevMonthAverageBasket: 0,
              lowStockCount: 0,
              weeklyTopProducts: [],
              weeklyWorstProducts: [],
              dailyCALast30: [],
              todaySalesCount: 0,
              storeOverviews: [
                StoreOverview(
                  storeId: 's1',
                  storeName: 'Boutique Akwa',
                  todayCA: 35000,
                  yesterdayCA: 30000,
                  employeeCount: 5,
                  statusLevel: StoreStatusLevel.stable,
                ),
              ],
            );
          }),
        ],
        child: const MaterialApp(
          home: StoreDashboardPage(storeId: 's1'),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.text('Réessayer'), findsOneWidget);

      await tester.tap(find.text('Réessayer'));
      await tester.pumpAndSettle();

      expect(calls, 2);
      expect(find.text('Boutique Akwa'), findsOneWidget);
      expect(find.text('Réessayer'), findsNothing);
    });

    testWidgets('shows EN BAISSE badge for declining store', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          storeOverviewsProvider.overrideWith((_) => Future.value([
                const StoreOverview(
                  storeId: 's2',
                  storeName: 'Boutique Bassa',
                  todayCA: 2000,
                  yesterdayCA: 50000,
                  employeeCount: 2,
                  statusLevel: StoreStatusLevel.enBaisse,
                ),
              ])),
        ],
        child: const MaterialApp(
          home: StoreDashboardPage(storeId: 's2'),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('EN BAISSE'), findsOneWidget);
    });
  });
}

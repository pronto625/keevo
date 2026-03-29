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

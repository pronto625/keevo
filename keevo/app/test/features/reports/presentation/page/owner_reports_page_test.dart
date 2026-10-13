import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/seller_leaderboard_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';
import 'package:keevo/features/reports/presentation/page/owner_reports_page.dart';

class _MockRepo extends Mock implements ProfitabilityRepository {}

/// The hubs read `context.canPop()` → they need a GoRouter.
Widget _routed(Widget page) => MaterialApp.router(
      routerConfig: GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => page),
      ]),
    );

/// v1s-14-2 — the « Équipe » leaderboard tab exists for the OWNER only.
void main() {
  setUpAll(() {
    registerFallbackValue(
        SellerLeaderboardParams(from: DateTime(2025), to: DateTime(2025)));
  });

  testWidgets('OWNER hub has an « Équipe » tab showing the leaderboard '
      'scoped to the active store', (tester) async {
    // Phone width: the 5 tabs must fit without overflow.
    tester.view.physicalSize = const Size(360 * 3, 780 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    SharedPreferences.setMockInitialValues(
        {kActiveStoreIdKey: 's1', kUserRoleKey: 'OWNER'});
    final prefs = await SharedPreferences.getInstance();
    final repo = _MockRepo();
    when(() => repo.getSellerLeaderboard(params: any(named: 'params')))
        .thenAnswer((_) async => const SellerLeaderboardResult(
              fromLocal: false,
              entries: [
                SellerLeaderboardEntry(
                    rank: 0,
                    sellerId: 'u1',
                    sellerName: 'Awa Ndiaye',
                    owner: false,
                    salesCount: 1,
                    totalRevenue: 1000,
                    averageBasket: 1000),
              ],
            ));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        profitabilityRepositoryProvider.overrideWithValue(repo),
      ],
      child: _routed(
          const OwnerReportsPage(initialTab: OwnerReportsPage.tabEquipe)),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Équipe'), findsOneWidget);
    expect(find.byType(SellerLeaderboardPage), findsOneWidget);
    expect(find.text('Awa Ndiaye'), findsOneWidget);
    final params = verify(() =>
            repo.getSellerLeaderboard(params: captureAny(named: 'params')))
        .captured
        .single as SellerLeaderboardParams;
    expect(params.storeId, 's1');
  });

  testWidgets('EMPLOYEE hub has no « Équipe » tab', (tester) async {
    SharedPreferences.setMockInitialValues({kUserRoleKey: 'EMPLOYEE'});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: _routed(const EmployeeReportsPage()),
    ));
    // Only the tab bar matters — do not settle the Jour page's providers.
    await tester.pump();

    expect(find.text('Équipe'), findsNothing);
    expect(find.byType(SellerLeaderboardPage), findsNothing);
  });
}

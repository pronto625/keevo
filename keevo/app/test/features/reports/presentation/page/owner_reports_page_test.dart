import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show RenderParagraph;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/seller_leaderboard_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';
import 'package:keevo/features/reports/presentation/page/owner_reports_page.dart';
import 'package:keevo/features/reports/presentation/page/reports_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';

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
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        profitabilityRepositoryProvider.overrideWithValue(repo),
      ],
      child: _routed(
          const OwnerReportsPage(initialTab: OwnerReportsPage.tabEquipe)),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Équipe'), findsOneWidget);
    // A31: every label is shown whole inside its tab on a 360 dp phone
    // (device: « Historiq », « Rentabi », « Boutiqu »).
    for (final label in ['Jour', 'Historique', 'Marges', 'Boutiques', 'Équipe']) {
      final text = find.text(label);
      final tab = find.ancestor(of: text, matching: find.byType(Tab));
      final textRect = tester.getRect(text);
      final tabRect = tester.getRect(tab);
      expect(textRect.left >= tabRect.left - 0.5, isTrue, reason: label);
      expect(textRect.right <= tabRect.right + 0.5, isTrue, reason: label);
      // Not clipped: the paragraph is laid out at its full natural width.
      final paragraph = tester.renderObject<RenderParagraph>(text);
      expect(
          paragraph.size.width >=
              paragraph.getMaxIntrinsicWidth(double.infinity) - 0.5,
          isTrue,
          reason: label);
    }
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
      retry: noProviderRetry,
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: _routed(const EmployeeReportsPage()),
    ));
    // Only the tab bar matters — do not settle the Jour page's providers.
    await tester.pump();

    expect(find.text('Équipe'), findsNothing);
    expect(find.byType(SellerLeaderboardPage), findsNothing);
  });

  // Story 15.2 — the Marges / Boutiques tabs are the profitability /
  // multi-store routes: hidden when the module cannot be read.
  testWidgets('OWNER hub hides the tabs of unreadable modules; « Équipe » '
      'opened at its new index', (tester) async {
    SharedPreferences.setMockInitialValues(
        {kActiveStoreIdKey: 's1', kUserRoleKey: 'OWNER'});
    final prefs = await SharedPreferences.getInstance();
    final repo = _MockRepo();
    when(() => repo.getSellerLeaderboard(params: any(named: 'params')))
        .thenAnswer((_) async =>
            const SellerLeaderboardResult(fromLocal: false, entries: []));

    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        profitabilityRepositoryProvider.overrideWithValue(repo),
        entitlementsOverride({
          'profitability': 'DISABLED',
          'multi-store': 'READ_ONLY',
          'advanced-reports': 'ENABLED',
        }),
      ],
      child: _routed(
          const OwnerReportsPage(initialTab: OwnerReportsPage.tabEquipe)),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Marges'), findsNothing);
    expect(find.text('Boutiques'), findsOneWidget, reason: 'READ_ONLY');
    expect(find.byType(Tab), findsNWidgets(4));
    expect(find.byType(SellerLeaderboardPage), findsOneWidget);
  });

  // Story 15.8 — « Équipe » is the `advanced-reports` route
  // (`/reports/equipe`): hidden without the module.
  testWidgets('OWNER hub hides « Équipe » when advanced-reports is disabled',
      (tester) async {
    SharedPreferences.setMockInitialValues(
        {kActiveStoreIdKey: 's1', kUserRoleKey: 'OWNER'});
    final prefs = await SharedPreferences.getInstance();
    final repo = _MockRepo();
    when(() => repo.getSellerLeaderboard(params: any(named: 'params')))
        .thenAnswer((_) async =>
            const SellerLeaderboardResult(fromLocal: false, entries: []));

    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        profitabilityRepositoryProvider.overrideWithValue(repo),
        entitlementsOverride({
          'profitability': 'ENABLED',
          'multi-store': 'ENABLED',
          'advanced-reports': 'DISABLED',
        }),
      ],
      child: _routed(
          const OwnerReportsPage(initialTab: OwnerReportsPage.tabEquipe)),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Équipe'), findsNothing);
    expect(find.text('Marges'), findsOneWidget);
    expect(find.byType(Tab), findsNWidgets(4));
    expect(find.byType(SellerLeaderboardPage), findsNothing);
    // The hidden initial tab falls back to « Jour ».
    final bar = tester.widget<TabBar>(find.byType(TabBar));
    expect(bar.controller!.index, 0);
    expect(find.byType(ReportsPage), findsOneWidget);
  });
}

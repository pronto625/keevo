import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/reports/presentation/page/reports_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  testWidgets(
      'employee: no store-wide summary is requested while the user id loads',
      (tester) async {
    SharedPreferences.setMockInitialValues({'active_store_id': 'store-1'});
    final prefs = await SharedPreferences.getInstance();
    final userId = Completer<String?>();
    final requested = <String?>[];

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
        currentUserIdProvider.overrideWith((ref) => userId.future),
        todaySummaryProvider.overrideWith((ref, args) async {
          requested.add(args.employeeId);
          return const DayClosureSummary(
            totalSales: 0,
            totalRevenue: 0,
            cashAmount: 0,
            momoAmount: 0,
            topProductQty: 0,
            pendingSalesCount: 0,
            pendingSalesTotal: 0,
          );
        }),
        dayClosureStateProvider
            .overrideWith((ref, s) async => DayClosureState.noSales),
        lastClosureProvider.overrideWith((ref, s) async => null),
      ],
      child: const MaterialApp(home: ReportsPage()),
    ));
    await tester.pump();

    expect(requested, isEmpty);

    userId.complete('emp-1');
    await tester.pumpAndSettle();

    expect(requested, ['emp-1']);
  });

  testWidgets(
      'employee whose user id cannot be read gets an error with retry, '
      'not an endless spinner nor store-wide figures', (tester) async {
    SharedPreferences.setMockInitialValues({'active_store_id': 'store-1'});
    final prefs = await SharedPreferences.getInstance();
    final requested = <String?>[];

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
        currentUserIdProvider.overrideWith((ref) async => null),
        todaySummaryProvider.overrideWith((ref, args) async {
          requested.add(args.employeeId);
          return const DayClosureSummary(
            totalSales: 0,
            totalRevenue: 0,
            cashAmount: 0,
            momoAmount: 0,
            topProductQty: 0,
            pendingSalesCount: 0,
            pendingSalesTotal: 0,
          );
        }),
        dayClosureStateProvider
            .overrideWith((ref, s) async => DayClosureState.noSales),
        lastClosureProvider.overrideWith((ref, s) async => null),
      ],
      child: const MaterialApp(home: ReportsPage()),
    ));
    await tester.pumpAndSettle();

    expect(requested, isEmpty);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.byType(AppErrorWidget), findsOneWidget);
  });

  // N1 — « Ventes en attente » had no entry point: the owner opens it from the
  // pending-sales banner of Rapports › Jour.
  Future<GoRouter> pumpWithPending(WidgetTester tester, String role) async {
    SharedPreferences.setMockInitialValues({'active_store_id': 'store-1'});
    final prefs = await SharedPreferences.getInstance();
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const ReportsPage()),
      GoRoute(
          path: '/pos/pending',
          builder: (_, __) => const Scaffold(body: Text('VENTES EN ATTENTE'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue(role),
        currentUserIdProvider.overrideWith((ref) async => 'user-1'),
        todaySummaryProvider.overrideWith((ref, args) async =>
            const DayClosureSummary(
              totalSales: 3,
              totalRevenue: 9000,
              cashAmount: 9000,
              momoAmount: 0,
              topProductQty: 0,
              pendingSalesCount: 2,
              pendingSalesTotal: 4000,
            )),
        dayClosureStateProvider
            .overrideWith((ref, s) async => DayClosureState.noSales),
        lastClosureProvider.overrideWith((ref, s) async => null),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
    return router;
  }

  testWidgets('N1: owner taps the pending banner → Ventes en attente',
      (tester) async {
    await pumpWithPending(tester, 'OWNER');

    final banner = find.byKey(const Key('pending-sales-banner'));
    await tester.ensureVisible(banner);
    await tester.tap(banner);
    await tester.pumpAndSettle();

    expect(find.text('VENTES EN ATTENTE'), findsOneWidget);
  });

  testWidgets('N1: the employee banner is informative only', (tester) async {
    await pumpWithPending(tester, 'EMPLOYEE');

    final banner = find.byKey(const Key('pending-sales-banner'));
    await tester.ensureVisible(banner);
    await tester.tap(banner);
    await tester.pumpAndSettle();

    expect(find.text('VENTES EN ATTENTE'), findsNothing);
  });
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/features/pos/domain/model/day_closure_model.dart';
import 'package:keevo/features/pos/presentation/provider/day_closure_providers.dart';
import 'package:keevo/features/reports/presentation/page/reports_page.dart';
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
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:keevo/features/reports/presentation/page/report_history_page.dart';
import 'package:keevo/features/reports/presentation/provider/report_history_providers.dart';

/// Widget tests for ReportHistoryPage.
/// Story 7.2 — Task 21 TDD.
void main() {
  setUpAll(() async => initializeDateFormatting('fr_FR'));

  ReportHistoryModel _makeReport(String id) => ReportHistoryModel(
        id: id,
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: 'Boutique Test',
        reportType: 'DAILY',
        reportDate: DateTime(2025, 6, 20),
        content: 'Rapport $id',
        deliveryStatus: 'SENT',
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 1500000,
        totalSales: 10,
        isAutomatic: true,
        createdAt: DateTime(2025, 6, 20, 22, 0),
      );

  Widget _buildPage(Future<List<ReportHistoryModel>> future) {
    return ProviderScope(
      overrides: [
        reportHistoryProvider().overrideWith((_) => future),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(
          routes: [
            GoRoute(
              path: '/',
              builder: (_, __) => const ReportHistoryPage(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorPage(Object error) {
    return ProviderScope(
      overrides: [
        reportHistoryProvider().overrideWith((_) async => throw error),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(
          routes: [
            GoRoute(
              path: '/',
              builder: (_, __) => const ReportHistoryPage(),
            ),
          ],
        ),
      ),
    );
  }

  testWidgets('shows skeleton loading while fetching', (tester) async {
    final completer = Completer<List<ReportHistoryModel>>();
    await tester.pumpWidget(_buildPage(completer.future));
    await tester.pump(); // one frame before the future completes

    // Skeleton replaces spinner — CircularProgressIndicator should not appear
    expect(find.byType(CircularProgressIndicator), findsNothing);
    // Skeleton renders a ListView with skeleton cards
    expect(find.byType(ListView), findsWidgets);

    completer.complete([]);
    await tester.pumpAndSettle();
  });

  testWidgets('shows empty state when no reports', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([])));
    await tester.pumpAndSettle();

    expect(find.text('Aucun rapport disponible.'), findsOneWidget);
    expect(find.text('Les rapports apparaîtront après la première clôture journalière.'), findsOneWidget);
  });

  testWidgets('shows report cards when data is available', (tester) async {
    final reports = [_makeReport('rpt-1'), _makeReport('rpt-2')];
    await tester.pumpWidget(_buildPage(Future.value(reports)));
    await tester.pumpAndSettle();

    expect(find.textContaining('Boutique Test'), findsNWidgets(2));
  });

  testWidgets('shows error state with retry button on error', (tester) async {
    await tester.pumpWidget(_buildErrorPage(Exception('fail')));
    await tester.pumpAndSettle();

    expect(find.text('Réessayer'), findsOneWidget);
    expect(find.byIcon(Icons.error_outline_rounded), findsOneWidget);
  });

  testWidgets('shows AppBar with correct title', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([])));
    await tester.pumpAndSettle();

    expect(find.text('Historique des rapports'), findsOneWidget);
  });
}

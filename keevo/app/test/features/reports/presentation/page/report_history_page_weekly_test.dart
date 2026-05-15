import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:keevo/features/reports/presentation/page/report_history_page.dart';
import 'package:keevo/features/reports/presentation/provider/report_history_providers.dart';

/// Widget tests for ReportHistoryPage — weekly report visibility and filter tabs.
/// Story 7.3 — AC6 + AC7.
void main() {
  setUpAll(() async => initializeDateFormatting('fr_FR'));

  ReportHistoryModel _weeklyReport() => ReportHistoryModel(
        id: 'rpt-weekly-1',
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: 'Boutique Test',
        reportType: 'WEEKLY',
        reportDate: DateTime(2026, 1, 4), // Sunday
        content: '\ud83d\udcc5 Rapport Hebdomadaire — Boutique Test',
        deliveryStatus: 'SENT',
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 3400000,
        totalSales: 78,
        isAutomatic: true,
        createdAt: DateTime(2026, 1, 4, 20, 5),
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

  testWidgets('weekly report appears in report list (Tous tab)', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([_weeklyReport()])));
    await tester.pumpAndSettle();

    expect(find.textContaining('Boutique Test'), findsOneWidget);
  });

  testWidgets('weekly report shows "Semaine du" date label', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([_weeklyReport()])));
    await tester.pumpAndSettle();

    expect(find.textContaining('Semaine du'), findsOneWidget);
  });

  testWidgets('weekly report shows "📅 HEBDO" badge', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([_weeklyReport()])));
    await tester.pumpAndSettle();

    expect(find.textContaining('HEBDO'), findsOneWidget);
  });

  testWidgets('filter tabs are displayed (Tous, Journalier, Hebdo)', (tester) async {
    await tester.pumpWidget(_buildPage(Future.value([])));
    await tester.pumpAndSettle();

    expect(find.text('Tous'), findsOneWidget);
    expect(find.text('Journalier'), findsOneWidget);
    expect(find.textContaining('Hebdo'), findsOneWidget);
  });
}

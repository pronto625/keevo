import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:keevo/features/reports/presentation/widget/report_history_card.dart';

/// Widget tests for ReportHistoryCard — WEEKLY badge and date label.
/// Story 7.3 — AC6 + AC7.
void main() {
  setUpAll(() async => initializeDateFormatting('fr_FR'));

  Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  ReportHistoryModel _weeklyReport() => ReportHistoryModel(
        id: 'rpt-weekly-1',
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: 'Boutique Test',
        reportType: 'WEEKLY',
        // Sunday 2026-01-04: week Du lundi 29 déc au dim 4 jan
        reportDate: DateTime(2026, 1, 4),
        content: '\ud83d\udcc5 Rapport Hebdomadaire',
        deliveryStatus: 'SENT',
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 3400000,
        totalSales: 78,
        isAutomatic: true,
        createdAt: DateTime(2026, 1, 4, 20, 5),
      );

  ReportHistoryModel _dailyReport() => ReportHistoryModel(
        id: 'rpt-daily-1',
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: 'Boutique Test',
        reportType: 'DAILY',
        reportDate: DateTime(2026, 1, 3),
        content: 'Rapport Journalier',
        deliveryStatus: 'SENT',
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 500000,
        totalSales: 10,
        isAutomatic: false,
        createdAt: DateTime(2026, 1, 3, 20, 0),
      );

  testWidgets('shows "📅 HEBDO" badge for WEEKLY report', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _weeklyReport())));
    expect(find.textContaining('HEBDO'), findsOneWidget);
  });

  testWidgets('shows "Quotidien" badge for DAILY report (not WEEKLY)', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _dailyReport())));
    expect(find.text('Quotidien'), findsOneWidget);
    expect(find.textContaining('HEBDO'), findsNothing);
  });

  testWidgets('shows "Semaine du" date label for WEEKLY report', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _weeklyReport())));
    expect(find.textContaining('Semaine du'), findsOneWidget);
  });

  testWidgets('weekly date label shows Monday-to-Sunday range', (tester) async {
    // reportDate = 2026-01-04 (Sunday) → Monday = 2026-12-29 (i.e. 29 déc)
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _weeklyReport())));
    // Should contain "déc" (Monday 29 Dec) and "janv" or "jan" (Sunday 4 Jan)
    expect(find.textContaining('déc'), findsOneWidget);
  });

  testWidgets('WEEKLY report shows FCFA revenue amount', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _weeklyReport())));
    // Revenue 3400000 XAF is shown
    expect(find.textContaining('3'), findsWidgets);
  });

  testWidgets('DAILY report still shows simple date format', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _dailyReport())));
    // Should contain "janv" (January 3 2026)
    expect(find.textContaining('janv'), findsOneWidget);
    expect(find.textContaining('Semaine du'), findsNothing);
  });
}

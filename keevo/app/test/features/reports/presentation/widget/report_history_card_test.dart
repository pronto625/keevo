import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:keevo/features/reports/presentation/widget/report_history_card.dart';

/// Widget tests for ReportHistoryCard.
/// Story 7.2 — Task 20 TDD.
void main() {
  setUpAll(() async => initializeDateFormatting('fr_FR'));

  Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  ReportHistoryModel _report({
    String deliveryStatus = 'SENT',
    String reportType = 'DAILY',
    String? storeName = 'Boutique Centrale',
  }) =>
      ReportHistoryModel(
        id: 'rpt-1',
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: storeName,
        reportType: reportType,
        reportDate: DateTime(2025, 6, 20),
        content: 'Rapport test',
        deliveryStatus: deliveryStatus,
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 1500000,
        totalSales: 12,
        isAutomatic: true,
        createdAt: DateTime(2025, 6, 20, 22, 0),
      );

  testWidgets('shows store name', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _report())));
    // Story 7.6 AC5: actorId null → title = "<storeName> — Global"
    expect(find.textContaining('Boutique Centrale'), findsOneWidget);
    expect(find.textContaining('Global'), findsOneWidget);
  });

  testWidgets('shows "Boutique" when storeName is null', (tester) async {
    await tester.pumpWidget(
      _wrap(ReportHistoryCard(report: _report(storeName: null))),
    );
    // Story 7.6 AC5: storeName null → "Boutique — Global"
    expect(find.textContaining('Boutique'), findsOneWidget);
    expect(find.textContaining('Global'), findsOneWidget);
  });

  testWidgets('shows "Quotidien" type badge for DAILY report', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _report(reportType: 'DAILY'))));
    expect(find.text('Quotidien'), findsOneWidget);
  });

  testWidgets('shows "Multi-boutiques" badge for DAILY_COMBINED', (tester) async {
    await tester.pumpWidget(
      _wrap(ReportHistoryCard(report: _report(reportType: 'DAILY_COMBINED'))),
    );
    expect(find.text('Multi-boutiques'), findsOneWidget);
  });

  testWidgets('calls onTap when tapped', (tester) async {
    bool tapped = false;
    await tester.pumpWidget(
      _wrap(ReportHistoryCard(
        report: _report(),
        onTap: () => tapped = true,
      )),
    );
    await tester.tap(find.byType(InkWell));
    expect(tapped, isTrue);
  });

  testWidgets('shows sales count and date', (tester) async {
    await tester.pumpWidget(_wrap(ReportHistoryCard(report: _report())));
    // Date is formatted as "20 juin 2025"
    expect(find.textContaining('juin'), findsOneWidget);
    // Sales count
    expect(find.textContaining('12'), findsOneWidget);
  });
}

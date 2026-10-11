import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';
import 'package:keevo/features/reports/presentation/page/report_detail_page.dart';
import 'package:keevo/features/reports/presentation/provider/report_history_providers.dart';

void main() {
  setUpAll(() async {
    GoogleFonts.config.allowRuntimeFetching = false;
    await initializeDateFormatting('fr_FR');
  });

  testWidgets('opened by id (no report passed): a single app bar',
      (tester) async {
    final report = ReportHistoryModel(
      id: 'r1',
      tenantId: 't1',
      storeId: 's1',
      reportType: 'END_OF_DAY',
      reportDate: DateTime(2026, 3, 17),
      content: 'Contenu du rapport',
      deliveryStatus: 'SENT',
      deliveryAttempts: 1,
      totalRevenue: 1000,
      totalSales: 1,
      isAutomatic: false,
      createdAt: DateTime(2026, 3, 17, 20),
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        currentUserRoleProvider.overrideWithValue('OWNER'),
        reportDetailProvider('r1').overrideWith((ref) async => report),
      ],
      child: const MaterialApp(home: ReportDetailPage(reportId: 'r1')),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(AppBar), findsOneWidget);
    expect(find.byType(Scaffold), findsOneWidget);
  });
}

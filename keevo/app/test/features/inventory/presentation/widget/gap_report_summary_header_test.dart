import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/inventory_gap_report_model.dart';
import 'package:keevo/features/inventory/presentation/widget/gap_report_summary_header.dart';

void main() {
  const summary = InventoryGapSummaryModel(
    totalCounted: 10,
    totalConcordant: 5,
    totalSurplus: 2,
    totalShortage: 3,
    totalSurplusValueXaf: 15000,
    totalShortageValueXaf: 47500,
  );

  Widget buildWidget() {
    return const MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: GapReportSummaryHeader(summary: summary),
        ),
      ),
    );
  }

  testWidgets('renders all 4 stat badges', (tester) async {
    await tester.pumpWidget(buildWidget());

    expect(find.text('10 comptés'), findsOneWidget);
    expect(find.text('5 concordants'), findsOneWidget);
    expect(find.text('2 surplus'), findsOneWidget);
    expect(find.text('3 manquants'), findsOneWidget);
  });

  testWidgets('XAF values formatted correctly', (tester) async {
    await tester.pumpWidget(buildWidget());

    expect(find.text('+15 000 FCFA'), findsOneWidget);
    expect(find.text('−47 500 FCFA'), findsOneWidget);
  });

  testWidgets('displays Résumé header with icon', (tester) async {
    await tester.pumpWidget(buildWidget());

    expect(find.text('Résumé'), findsOneWidget);
    expect(find.byIcon(Icons.assessment), findsOneWidget);
  });
}

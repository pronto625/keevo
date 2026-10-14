import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/widget/total_revenue_card.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  Future<void> pump(WidgetTester tester,
      {PeriodRevenue? revenue,
      bool onDeviceOnly = false,
      ValueChanged<RevenuePeriod>? onChanged}) async {
    // Small phone width: the four periods must wrap, never overflow.
    tester.view.physicalSize = const Size(320 * 3, 800 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: TotalRevenueCard(
            period: RevenuePeriod.month,
            revenue: revenue,
            onDeviceOnly: onDeviceOnly,
            onPeriodChanged: onChanged ?? (_) {},
          ),
        ),
      ),
    ));
  }

  testWidgets('labels in French, no overflow at phone width', (tester) async {
    await pump(tester,
        revenue: const PeriodRevenue(amount: 150000, salesCount: 3));

    expect(find.text("Chiffre d'affaires total"), findsOneWidget);
    for (final l in ['Semaine', 'Mois', 'Année', 'Depuis le début']) {
      expect(find.text(l), findsOneWidget);
    }
    expect(find.text('3 ventes'), findsOneWidget);
    expect(find.text('Données de cet appareil'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('loading: em dash, no count', (tester) async {
    await pump(tester);
    expect(find.text('—'), findsOneWidget);
    expect(find.textContaining('vente'), findsNothing);
  });

  testWidgets('device-only figure is labelled', (tester) async {
    await pump(tester,
        revenue: const PeriodRevenue(amount: 0, salesCount: 0),
        onDeviceOnly: true);
    expect(find.text('Aucune vente'), findsOneWidget);
    expect(find.text('Données de cet appareil'), findsOneWidget);
  });

  testWidgets('tapping a period reports it', (tester) async {
    RevenuePeriod? picked;
    await pump(tester,
        revenue: const PeriodRevenue(amount: 1, salesCount: 1),
        onChanged: (p) => picked = p);
    await tester.tap(find.text('Depuis le début'));
    expect(picked, RevenuePeriod.allTime);
  });

  test('sales count label', () {
    expect(TotalRevenueCard.salesCountLabel(0), 'Aucune vente');
    expect(TotalRevenueCard.salesCountLabel(1), '1 vente');
    expect(TotalRevenueCard.salesCountLabel(2), '2 ventes');
  });
}

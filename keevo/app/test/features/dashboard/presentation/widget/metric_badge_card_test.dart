import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/presentation/widget/metric_badge_card.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('MetricBadgeCard', () {
    testWidgets('yesterdayCA factory renders label and amount', (tester) async {
      await tester.pumpWidget(_wrap(MetricBadgeCard.yesterdayCA(amount: 75000)));

      expect(find.text("CA d'Hier"), findsOneWidget);
      expect(find.textContaining('75'), findsAtLeast(1));
    });

    testWidgets('lowStock factory renders count', (tester) async {
      await tester.pumpWidget(_wrap(MetricBadgeCard.lowStock(count: 5)));

      expect(find.text('Stock Bas'), findsOneWidget);
      expect(find.text('5'), findsOneWidget);
    });

    testWidgets('monthlyTransactions factory renders with trend', (tester) async {
      await tester.pumpWidget(_wrap(
        MetricBadgeCard.monthlyTransactions(count: 42, trendPercent: 20),
      ));

      expect(find.text('Transactions Ce Mois'), findsOneWidget);
      expect(find.text('42'), findsOneWidget);
      expect(find.textContaining('↑'), findsOneWidget);
    });

    testWidgets('averageBasket factory renders formatted amount', (tester) async {
      await tester.pumpWidget(_wrap(
        MetricBadgeCard.averageBasket(amount: 3500),
      ));

      expect(find.text('Panier Moyen'), findsOneWidget);
    });

    testWidgets('onTap callback fires when tapped', (tester) async {
      var tapped = false;
      await tester.pumpWidget(_wrap(
        MetricBadgeCard.lowStock(count: 1, onTap: () => tapped = true),
      ));

      await tester.tap(find.byType(MetricBadgeCard));
      expect(tapped, isTrue);
    });
  });
}

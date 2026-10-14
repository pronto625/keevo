import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/catalog/presentation/widget/pricing_calculator_widget.dart';

void main() {
  testWidgets('loss warning is shown once', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(
        body: PricingCalculatorWidget(
            sellingPrice: 800, buyPrice: 1000, transportCost: 0),
      ),
    ));
    expect(find.textContaining('vous vendez à perte'), findsOneWidget);
  });

  Widget calc({required int sell, required int buy}) => MaterialApp(
        home: Scaffold(
          body: PricingCalculatorWidget(
              sellingPrice: sell, buyPrice: buy, transportCost: 0),
        ),
      );

  testWidgets('margin rate uses a French decimal comma', (tester) async {
    await tester.pumpWidget(calc(sell: 1250, buy: 1000));

    expect(find.text('25,0\u00A0%'), findsOneWidget);
    expect(find.text('RENTABLE'), findsOneWidget);
  });

  testWidgets('without a purchase price the margin is not computed',
      (tester) async {
    await tester.pumpWidget(calc(sell: 3000, buy: 0));

    expect(find.text('—'), findsNWidgets(3));
    expect(find.textContaining('3 000 XAF'), findsNothing);
    expect(find.text("Renseignez le prix d'achat pour calculer la marge"),
        findsOneWidget);
    for (final badge in ['PERTE', 'FAIBLE', 'MODÉRÉE', 'RENTABLE']) {
      expect(find.text(badge), findsNothing);
    }
  });
}

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
}

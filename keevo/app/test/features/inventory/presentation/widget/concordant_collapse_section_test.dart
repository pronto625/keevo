import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/presentation/widget/concordant_collapse_section.dart';

void main() {
  final concordantRows = [
    InventoryGapRowModel(
      productId: 'p1',
      productName: 'Savon',
      theoretical: 10,
      physical: 10,
      ecart: 0,
      unitPriceXaf: 500,
      gapValueXaf: 0,
    ),
    InventoryGapRowModel(
      productId: 'p2',
      productName: 'Bière',
      theoretical: 20,
      physical: 20,
      ecart: 0,
      unitPriceXaf: 1000,
      gapValueXaf: 0,
    ),
  ];

  Widget buildWidget() {
    return MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ConcordantCollapseSection(
            count: 2,
            rows: concordantRows,
          ),
        ),
      ),
    );
  }

  testWidgets('renders collapsed by default', (tester) async {
    await tester.pumpWidget(buildWidget());

    expect(find.text('Concordants : 2 produits'), findsOneWidget);
    // Product names should NOT be visible when collapsed
    expect(find.text('Savon'), findsNothing);
    expect(find.text('Bière'), findsNothing);
  });

  testWidgets('expands on tap showing product names', (tester) async {
    await tester.pumpWidget(buildWidget());

    // Tap to expand
    await tester.tap(find.text('Concordants : 2 produits'));
    await tester.pumpAndSettle();

    // Product names now visible
    expect(find.text('Savon'), findsOneWidget);
    expect(find.text('Bière'), findsOneWidget);
    expect(find.text('= 0'), findsNWidgets(2));
  });
}

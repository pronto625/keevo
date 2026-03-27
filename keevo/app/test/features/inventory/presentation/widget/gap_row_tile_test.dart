import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/presentation/widget/gap_row_tile.dart';

void main() {
  final shortageRow = InventoryGapRowModel(
    productId: 'p1',
    productName: 'Savon Palmolive',
    sku: 'KEV-000001',
    theoretical: 10,
    physical: 7,
    ecart: -3,
    unitPriceXaf: 500,
    gapValueXaf: 1500,
  );

  final surplusRow = InventoryGapRowModel(
    productId: 'p2',
    productName: 'Huile Végétale',
    theoretical: 5,
    physical: 8,
    ecart: 3,
    unitPriceXaf: 1000,
    gapValueXaf: 3000,
  );

  Widget buildTile(InventoryGapRowModel row, {VoidCallback? onTap}) {
    return MaterialApp(
      home: Scaffold(
        body: GapRowTile(row: row, onTap: onTap),
      ),
    );
  }

  testWidgets('renders product name with theoretical/physical/écart',
      (tester) async {
    await tester.pumpWidget(buildTile(shortageRow));

    expect(find.text('Savon Palmolive'), findsOneWidget);
    expect(find.text('Keevo : 10 → Réel : 7'), findsOneWidget);
    expect(find.text('-3'), findsOneWidget);
  });

  testWidgets('tap triggers onTap callback', (tester) async {
    var tapped = false;
    await tester.pumpWidget(
        buildTile(shortageRow, onTap: () => tapped = true));

    await tester.tap(find.byType(GapRowTile));
    expect(tapped, isTrue);
  });

  testWidgets('surplus row shows + prefix on écart', (tester) async {
    await tester.pumpWidget(buildTile(surplusRow));

    expect(find.text('+3'), findsOneWidget);
    expect(find.text('Huile Végétale'), findsOneWidget);
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/inventory_gap_row_model.dart';
import 'package:keevo/features/inventory/presentation/widget/product_detail_bottom_sheet.dart';

void main() {
  final shortageRow = InventoryGapRowModel(
    productId: 'p1',
    productName: 'Savon Palmolive',
    sku: 'KEV-000001',
    categoryName: 'Hygiène',
    theoretical: 10,
    physical: 7,
    ecart: -3,
    unitPriceXaf: 500,
    gapValueXaf: 1500,
  );

  Widget buildSheet() {
    return MaterialApp(
      home: Scaffold(
        body: ProductDetailBottomSheet(row: shortageRow),
      ),
    );
  }

  testWidgets('renders product info, theoretical/physical/écart',
      (tester) async {
    await tester.pumpWidget(buildSheet());

    expect(find.text('Savon Palmolive'), findsOneWidget);
    expect(find.text('KEV-000001'), findsOneWidget);
    expect(find.text('Hygiène'), findsOneWidget);
    expect(find.text('Keevo'), findsOneWidget);
    expect(find.text('10'), findsOneWidget);
    expect(find.text('Réel'), findsOneWidget);
    expect(find.text('7'), findsOneWidget);
    expect(find.text('Écart'), findsOneWidget);
    expect(find.text('-3'), findsOneWidget);
  });

  testWidgets('shows as modal bottom sheet and can be dismissed',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => ElevatedButton(
            onPressed: () =>
                ProductDetailBottomSheet.show(context, shortageRow),
            child: const Text('Open'),
          ),
        ),
      ),
    ));

    // Open the sheet
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();

    // Verify content is visible
    expect(find.text('Savon Palmolive'), findsOneWidget);

    // Dismiss by tapping scrim
    await tester.tapAt(const Offset(20, 20));
    await tester.pumpAndSettle();

    // Sheet should be dismissed
    expect(find.byType(ProductDetailBottomSheet), findsNothing);
  });
}

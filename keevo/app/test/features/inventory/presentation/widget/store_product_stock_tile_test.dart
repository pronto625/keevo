import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';
import 'package:keevo/features/inventory/presentation/widget/store_product_stock_tile.dart';

/// Widget tests for StoreProductStockTile (Story 3.2 — Task 14.1).
void main() {
  StoreProductStockModel _model({
    String productName = 'Produit Test',
    int quantity = 10,
    int minimumThreshold = 5,
    String status = 'NORMAL',
    bool isLow = false,
    bool isCritical = false,
  }) =>
      StoreProductStockModel(
        productId: 'pid-1',
        productName: productName,
        storeId: 'sid-1',
        quantity: quantity,
        minimumThreshold: minimumThreshold,
        status: status,
        isLow: isLow,
        isCritical: isCritical,
      );

  Widget _wrap(Widget child) => MaterialApp(
        home: Scaffold(body: child),
      );

  group('StoreProductStockTile (Story 3.2 — Task 14.1)', () {
    testWidgets('shows product name', (tester) async {
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(entry: _model(productName: 'Chaussures Nike')),
      ));
      expect(find.text('Chaussures Nike'), findsOneWidget);
    });

    testWidgets('shows OK badge for NORMAL status', (tester) async {
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(entry: _model()),
      ));
      expect(find.text('OK'), findsOneWidget);
    });

    testWidgets('shows Bas badge for BAS status', (tester) async {
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(
          entry: _model(
            quantity: 3,
            minimumThreshold: 10,
            status: 'BAS',
            isLow: true,
          ),
        ),
      ));
      expect(find.text('Bas'), findsOneWidget);
    });

    testWidgets('shows Critique badge for CRITIQUE status', (tester) async {
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(
          entry: _model(
            quantity: 0,
            status: 'CRITIQUE',
            isCritical: true,
          ),
        ),
      ));
      expect(find.text('Critique'), findsOneWidget);
    });

    testWidgets('invoking onTap fires the callback', (tester) async {
      var tapped = false;
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(
          entry: _model(),
          onTap: () => tapped = true,
        ),
      ));
      await tester.tap(find.byType(InkWell).first);
      expect(tapped, isTrue);
    });

    testWidgets('no InkWell when onTap is null', (tester) async {
      await tester.pumpWidget(_wrap(
        StoreProductStockTile(entry: _model()),
      ));
      // Tile is plain Padding without InkWell when onTap is absent
      expect(find.byType(InkWell), findsNothing);
    });
  });
}

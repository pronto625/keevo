import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/page/checkout_page.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';

CartItem _item({
  String id = 'p1',
  int price = 10000,
  int qty = 2,
}) =>
    CartItem(
      id: id,
      productId: id,
      productName: 'Produit',
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
    );

/// Wraps CheckoutPage with the required ProviderScope + MaterialApp.
/// Prepopulates the cart and optionally applies a discount.
Widget _buildApp({
  required List<CartItem> items,
  int discount = 0,
}) {
  return ProviderScope(
    child: MaterialApp(
      home: Consumer(builder: (context, ref, _) {
        // Populate cart on first build
        WidgetsBinding.instance.addPostFrameCallback((_) {
          final notifier = ref.read(cartProvider.notifier);
          for (final item in items) {
            notifier.addItem(item);
          }
          if (discount > 0) {
            notifier.setDiscount(discount);
          }
        });
        return const CheckoutPage();
      }),
    ),
  );
}

void main() {
  group('CheckoutPage', () {
    testWidgets('shows discount breakdown when discount > 0', (tester) async {
      await tester.pumpWidget(
          _buildApp(items: [_item(price: 10000, qty: 1)], discount: 2000));
      await tester.pumpAndSettle();

      // Subtotal label and discount label should appear
      expect(find.text('Réduction'), findsOneWidget);
      expect(find.text('Total à payer'), findsOneWidget);
    });

    testWidgets('shows simple total when no discount', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // No discount row
      expect(find.text('Réduction'), findsNothing);
      expect(find.text('Total à payer'), findsNothing);
    });

    testWidgets('Espèces shows Montant reçu field', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // Select Espèces
      await tester.tap(find.text('💵 Espèces'));
      await tester.pumpAndSettle();

      expect(find.text('Montant reçu'), findsOneWidget);
    });

    testWidgets('change calculation with Espèces', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 5000, qty: 2)]));
      await tester.pumpAndSettle();

      // Select Espèces
      await tester.tap(find.text('💵 Espèces'));
      await tester.pumpAndSettle();

      // Enter montant reçu = 15000 (total = 10000)
      await tester.enterText(find.bySemanticsLabel('Montant reçu'), '15000');
      await tester.pumpAndSettle();

      expect(find.textContaining('Monnaie à rendre'), findsOneWidget);
    });

    testWidgets('Valider button disabled when no payment mode selected',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      final button = tester.widget<FilledButton>(find.widgetWithText(
        FilledButton,
        'Valider la vente',
      ));
      expect(button.onPressed, isNull);
    });

    testWidgets(
        'Valider button disabled when Espèces selected but Montant reçu insufficient',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('💵 Espèces'));
      await tester.pumpAndSettle();

      // Enter insufficient amount
      await tester.enterText(find.bySemanticsLabel('Montant reçu'), '5000');
      await tester.pumpAndSettle();

      final button = tester.widget<FilledButton>(find.widgetWithText(
        FilledButton,
        'Valider la vente',
      ));
      expect(button.onPressed, isNull);
    });
  });
}

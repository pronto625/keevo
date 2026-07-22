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
  String productStatus = 'ACTIVE',
}) =>
    CartItem(
      id: id,
      productId: id,
      productName: 'Produit',
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
      productStatus: productStatus,
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

      // Discount shown as pill inside the hero gradient card
      expect(find.textContaining('Réduction'), findsOneWidget);
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

      // Select Espèces (no emoji in new UI)
      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      expect(find.text('Montant reçu (optionnel)'), findsOneWidget);
    });

    testWidgets('change calculation with Espèces', (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 5000, qty: 2)]));
      await tester.pumpAndSettle();

      // Select Espèces
      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      // Enter montant reçu = 15000 (total = 10000)
      await tester.enterText(
          find.bySemanticsLabel('Montant reçu (optionnel)'), '15000');
      await tester.pumpAndSettle();

      expect(find.textContaining('Monnaie à rendre'), findsOneWidget);
    });

    testWidgets('Valider button disabled when no payment mode selected',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      // InkWell wraps the button — when disabled its onTap is null
      final inkWell = tester.widget<InkWell>(
          find.ancestor(
            of: find.text('Valider la vente'),
            matching: find.byType(InkWell),
          ));
      expect(inkWell.onTap, isNull);
    });

    testWidgets(
        'Valider button enabled when Espèces selected even without Montant reçu',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item(price: 10000, qty: 1)]));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Espèces'));
      await tester.pumpAndSettle();

      // Montant reçu is now optional — button should be enabled
      final inkWell = tester.widget<InkWell>(
          find.ancestor(
            of: find.text('Valider la vente'),
            matching: find.byType(InkWell),
          ));
      expect(inkWell.onTap, isNotNull);
    });

    // ── AC1: Draft cart → "🔶 Vente brouillon" button label ────────────────

    testWidgets('shows Vente brouillon label when cart has a draft item',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [
        _item(id: 'd1', price: 3000, qty: 1, productStatus: 'DRAFT'),
      ]));
      await tester.pumpAndSettle();

      expect(find.text('🔶 Vente brouillon'), findsOneWidget);
      expect(find.text('Valider la vente'), findsNothing);
    });

    // ── AC2: No-draft cart → "Valider la vente" (non-regression) ───────────

    testWidgets('keeps Valider la vente label when cart has no draft item',
        (tester) async {
      await tester.pumpWidget(_buildApp(items: [_item()]));
      await tester.pumpAndSettle();

      expect(find.text('Valider la vente'), findsOneWidget);
      expect(find.text('🔶 Vente brouillon'), findsNothing);
    });
  });
}

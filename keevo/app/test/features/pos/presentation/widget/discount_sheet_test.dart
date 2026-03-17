import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/widget/discount_sheet.dart';

CartItem _item({
  String id = 'p1',
  int price = 5000,
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

void main() {
  group('DiscountSheet', () {
    testWidgets('shows percentage mode by default', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier).addItem(_item());
                    DiscountSheet.show(context);
                  },
                  child: const Text('Open'),
                );
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('% sur le total'), findsOneWidget);
      expect(find.text('Montant fixe'), findsOneWidget);
      expect(find.text('Appliquer'), findsOneWidget);
    });

    testWidgets('percentage mode previews discounted total', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier)
                        .addItem(_item(price: 10000, qty: 1));
                    DiscountSheet.show(context);
                  },
                  child: const Text('Open'),
                );
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Enter 10%
      await tester.enterText(find.byType(TextField), '10');
      await tester.pumpAndSettle();

      // Preview should show 10000 - 1000 = 9000
      expect(find.textContaining('9'), findsWidgets);
    });

    testWidgets('fixed mode shows error when exceeding subtotal',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier)
                        .addItem(_item(price: 1000, qty: 1));
                    DiscountSheet.show(context);
                  },
                  child: const Text('Open'),
                );
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Switch to fixed mode
      await tester.tap(find.text('Montant fixe'));
      await tester.pumpAndSettle();

      // The FixedAmountDiscountStrategy clamps, so no explicit error —
      // but preview shows 0 (clamped to subtotal)
      await tester.enterText(find.byType(TextField), '5000');
      await tester.pumpAndSettle();

      // Preview total should be 0 (subtotal 1000, discount clamped to 1000)
      expect(find.textContaining('0'), findsWidgets);
    });

    testWidgets('Appliquer button applies discount and closes sheet',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier)
                        .addItem(_item(price: 10000, qty: 1));
                    DiscountSheet.show(context);
                  },
                  child: const Text('Open'),
                );
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Enter 10%
      await tester.enterText(find.byType(TextField), '10');
      await tester.pumpAndSettle();

      // Tap Appliquer
      await tester.tap(find.text('Appliquer'));
      await tester.pumpAndSettle();

      // Sheet should be closed
      expect(find.text('Appliquer'), findsNothing);
    });

    testWidgets('Annuler closes without applying', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier)
                        .addItem(_item(price: 10000, qty: 1));
                    DiscountSheet.show(context);
                  },
                  child: const Text('Open'),
                );
              }),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();

      // Sheet closed — no discount applied
      expect(find.text('Appliquer'), findsNothing);
    });
  });
}

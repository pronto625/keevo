import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/widget/discount_sheet.dart';
import 'package:keevo_core/state/provider_retry.dart';

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
          retry: noProviderRetry,
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
          retry: noProviderRetry,
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
          retry: noProviderRetry,
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

      await tester.enterText(find.byType(TextField), '5000');
      await tester.pumpAndSettle();

      expect(find.text('La réduction ne peut pas dépasser le sous-total'),
          findsOneWidget);
      final button = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Appliquer'));
      expect(button.onPressed, isNull);
    });

    Future<ProviderContainer> openWithSubtotal(
        WidgetTester tester, int subtotal) async {
      late ProviderContainer container;
      await tester.pumpWidget(
        ProviderScope(
          retry: noProviderRetry,
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                container = ProviderScope.containerOf(context);
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier)
                        .addItem(_item(price: subtotal, qty: 1));
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
      return container;
    }

    testWidgets('percentage over 100 is refused inline, Appliquer disabled',
        (tester) async {
      final container = await openWithSubtotal(tester, 10000);

      await tester.enterText(find.byType(TextField), '150');
      await tester.pumpAndSettle();

      expect(find.text('Le pourcentage ne peut pas dépasser 100 %'),
          findsOneWidget);
      final button = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Appliquer'));
      expect(button.onPressed, isNull);
      expect(container.read(cartProvider.notifier).discountAmount, 0);
    });

    testWidgets('100 % stays allowed (discount equals the subtotal)',
        (tester) async {
      final container = await openWithSubtotal(tester, 10000);

      await tester.enterText(find.byType(TextField), '100');
      await tester.pumpAndSettle();
      expect(find.textContaining('dépasser'), findsNothing);

      await tester.tap(find.text('Appliquer'));
      await tester.pumpAndSettle();

      expect(container.read(cartProvider.notifier).discountAmount, 10000);
    });

    testWidgets('fixed amount equal to the subtotal is accepted',
        (tester) async {
      final container = await openWithSubtotal(tester, 3000);
      await tester.tap(find.text('Montant fixe'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '3000');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Appliquer'));
      await tester.pumpAndSettle();

      expect(container.read(cartProvider.notifier).discountAmount, 3000);
    });

    testWidgets('Appliquer button applies discount and closes sheet',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          retry: noProviderRetry,
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
          retry: noProviderRetry,
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

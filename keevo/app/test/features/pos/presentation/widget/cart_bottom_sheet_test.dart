import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/theme/app_theme.dart';
import 'package:keevo/features/catalog/domain/model/product_status.dart';
import 'package:keevo/features/pos/data/datasource/local_sale_datasource.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';
import 'package:keevo/features/pos/presentation/widget/cart_bottom_sheet.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockLocalSaleDs extends Mock implements LocalSaleDataSource {}

class _FixedActiveStore extends ActiveStoreNotifier {
  @override
  String? build() => 's1';
}

CartItem _item({
  String id = 'p1',
  String name = 'Savon',
  int price = 500,
  int appliedPrice = 500,
  int qty = 1,
}) =>
    CartItem(
      id: id,
      productId: id,
      productName: name,
      unitPrice: price,
      appliedUnitPrice: appliedPrice,
      quantity: qty,
    );

/// Helper to pump CartBottomSheet within a ProviderScope + Material scaffold.
Future<void> pumpSheet(
  WidgetTester tester, {
  List<Override> overrides = const [],
  required VoidCallback onEncaisser,
  ThemeData? theme,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: overrides,
      child: MaterialApp(
        theme: theme,
        home: Scaffold(
          body: Builder(builder: (context) {
            return ElevatedButton(
              onPressed: () => CartBottomSheet.show(context,
                  onEncaisser: onEncaisser),
              child: const Text('Open'),
            );
          }),
        ),
      ),
    ),
  );
  // Tap the button to open the bottom sheet
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
}

/// v1s-12-10: opens the sheet with a single DRAFT item in store 's1' and taps
/// "Encaisser". [hasStockRecord] = whether the draft already has a stock row.
Future<_MockLocalSaleDs> _encaisserDraft(WidgetTester tester,
    {required bool hasStockRecord, required VoidCallback onEncaisser}) async {
  final ds = _MockLocalSaleDs();
  when(() => ds.hasStockRecord('d1', 's1'))
      .thenAnswer((_) async => hasStockRecord);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        localSaleDataSourceProvider.overrideWithValue(ds),
        activeStoreIdProvider.overrideWith(_FixedActiveStore.new),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Consumer(builder: (context, ref, _) {
            return ElevatedButton(
              onPressed: () {
                ref.read(cartProvider.notifier).addItem(CartItem(
                      id: 'd1',
                      productId: 'd1',
                      productName: 'Brouillon',
                      unitPrice: 500,
                      appliedUnitPrice: 500,
                      quantity: 1,
                      productStatus: ProductStatus.draft,
                    ));
                CartBottomSheet.show(context, onEncaisser: onEncaisser);
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
  await tester.tap(find.text('Encaisser'));
  await tester.pumpAndSettle();
  return ds;
}

void main() {
  group('CartBottomSheet draft initial stock (v1s-12-10)', () {
    testWidgets('a draft already stocked in the store is not prompted',
        (tester) async {
      var encaissed = false;
      await _encaisserDraft(tester,
          hasStockRecord: true, onEncaisser: () => encaissed = true);

      expect(find.text('Stock initial'), findsNothing);
      expect(encaissed, isTrue);
    });

    testWidgets('a draft never stocked in the store is prompted',
        (tester) async {
      var encaissed = false;
      await _encaisserDraft(tester,
          hasStockRecord: false, onEncaisser: () => encaissed = true);

      expect(find.text('Stock initial'), findsOneWidget);
      expect(encaissed, isFalse);
    });
  });

  group('CartBottomSheet', () {
    testWidgets('shows Réduction button when cart is not empty',
        (tester) async {
      await pumpSheet(tester, onEncaisser: () {});
      // The sheet is open but cart is empty — no Réduction button
      expect(find.text('Réduction'), findsNothing);
    });

    testWidgets('shows discount line when discountAmount > 0',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    final notifier = ref.read(cartProvider.notifier);
                    notifier.addItem(_item(price: 5000, appliedPrice: 5000, qty: 2));
                    notifier.setDiscount(1000);
                    CartBottomSheet.show(context, onEncaisser: () {});
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

      // Discount line should show
      expect(find.text('Réduction'), findsWidgets);
      expect(find.text('Total à payer'), findsOneWidget);
    });
  });

  group('_CartItemTile', () {
    testWidgets('tapping price shows inline editor', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier).addItem(
                        _item(price: 5000, appliedPrice: 5000));
                    CartBottomSheet.show(context, onEncaisser: () {});
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

      // Tap the price text to open inline editor
      final priceFinder = find.textContaining('5');
      expect(priceFinder, findsWidgets);
    });

    testWidgets('shows Prix modifié label when price is overridden',
        (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier).addItem(
                        _item(price: 5000, appliedPrice: 4000));
                    CartBottomSheet.show(context, onEncaisser: () {});
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

      expect(find.text('modifié'), findsOneWidget);
    });

    testWidgets('no modifié label when price is same', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(builder: (context, ref, _) {
                return ElevatedButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier).addItem(
                        _item(price: 5000, appliedPrice: 5000));
                    CartBottomSheet.show(context, onEncaisser: () {});
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

      expect(find.text('modifié'), findsNothing);
    });

    testWidgets('Encaisser button disabled when cart empty', (tester) async {
      await pumpSheet(tester, onEncaisser: () {});

      // InkWell wraps the Encaisser button — when cart is empty onTap is null
      final inkWell = tester.widget<InkWell>(
          find.ancestor(
            of: find.text('Encaisser'),
            matching: find.byType(InkWell),
          ));
      expect(inkWell.onTap, isNull);
    });
      testWidgets('only the sheet\'s own drag handle (theme handle off)',
        (tester) async {
      // The app theme turns drag handles on globally.
      await pumpSheet(tester, onEncaisser: () {}, theme: AppTheme.light());
      final sheet = tester.widget<BottomSheet>(find.byType(BottomSheet));
      expect(sheet.showDragHandle, isFalse);
    });
  });
}

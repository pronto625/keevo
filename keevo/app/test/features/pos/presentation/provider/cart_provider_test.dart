import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';

CartItem _item({
  String id = 'p1',
  String name = 'Savon',
  int price = 500,
  int qty = 1,
}) =>
    CartItem(
      id: id,
      productId: id,
      productName: name,
      unitPrice: price,
      appliedUnitPrice: price,
      quantity: qty,
    );

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer();
  });

  tearDown(() => container.dispose());

  CartNotifier notifier() => container.read(cartProvider.notifier);
  List<CartItem> state() => container.read(cartProvider);

  group('CartNotifier', () {
    test('addItem adds to cart', () {
      notifier().addItem(_item());
      expect(state(), hasLength(1));
      expect(state().first.productName, 'Savon');
    });

    test('addItem increments existing item quantity', () {
      notifier().addItem(_item(qty: 2));
      notifier().addItem(_item(qty: 3));
      expect(state(), hasLength(1));
      expect(state().first.quantity, 5);
    });

    test('removeItem removes from cart', () {
      notifier().addItem(_item());
      notifier().removeItem('p1');
      expect(state(), isEmpty);
    });

    test('updateQuantity updates quantity', () {
      notifier().addItem(_item());
      notifier().updateQuantity('p1', 10);
      expect(state().first.quantity, 10);
    });

    test('updateQuantity with 0 removes item', () {
      notifier().addItem(_item());
      notifier().updateQuantity('p1', 0);
      expect(state(), isEmpty);
    });

    test('clearCart empties state', () {
      notifier().addItem(_item());
      notifier().addItem(_item(id: 'p2', name: 'Riz'));
      notifier().clearCart();
      expect(state(), isEmpty);
    });

    test('totalAmount is sum of subtotals', () {
      notifier().addItem(_item(price: 500, qty: 2)); // 1000
      notifier().addItem(_item(id: 'p2', price: 300, qty: 3)); // 900
      expect(notifier().totalAmount, 1900);
    });

    // Story 4.2 — price override + discount
    test('updatePrice updates appliedUnitPrice', () {
      notifier().addItem(_item(price: 500));
      notifier().updatePrice('p1', 400);
      expect(state().first.appliedUnitPrice, 400);
      expect(state().first.unitPrice, 500); // catalogue unchanged
    });

    test('setDiscount updates discountAmount', () {
      notifier().addItem(_item(price: 1000, qty: 2)); // 2000
      final ok = notifier().setDiscount(500);
      expect(ok, isTrue);
      expect(notifier().discountAmount, 500);
    });

    test('clearCart resets discount', () {
      notifier().addItem(_item(price: 1000, qty: 2));
      notifier().setDiscount(500);
      notifier().clearCart();
      expect(notifier().discountAmount, 0);
    });

    test('finalTotal is subtotal minus discount', () {
      notifier().addItem(_item(price: 1000, qty: 3)); // 3000
      notifier().setDiscount(500);
      expect(notifier().finalTotal, 2500);
    });

    test('setDiscount exceeds subtotal is rejected', () {
      notifier().addItem(_item(price: 1000, qty: 1)); // 1000
      final ok = notifier().setDiscount(2000);
      expect(ok, isFalse);
      expect(notifier().discountAmount, 0); // unchanged
    });
  });
}

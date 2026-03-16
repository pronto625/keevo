import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';

void main() {
  group('CartItem', () {
    test('subtotal equals appliedUnitPrice * quantity', () {
      final item = CartItem(
        id: 'p1',
        productId: 'p1',
        productName: 'Savon',
        unitPrice: 500,
        appliedUnitPrice: 500,
        quantity: 3,
      );
      expect(item.subtotal, 1500);
    });

    test('copyWith updates quantity', () {
      final item = CartItem(
        id: 'p1',
        productId: 'p1',
        productName: 'Savon',
        unitPrice: 500,
        appliedUnitPrice: 500,
        quantity: 1,
      );
      final updated = item.copyWith(quantity: 5);
      expect(updated.quantity, 5);
      expect(updated.subtotal, 2500);
      expect(updated.productName, 'Savon');
    });

    test('zero quantity throws AssertionError', () {
      expect(
        () => CartItem(
          id: 'p1',
          productId: 'p1',
          productName: 'Savon',
          unitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 0,
        ),
        throwsA(isA<AssertionError>()),
      );
    });
  });
}

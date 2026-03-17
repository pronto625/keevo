import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/domain/model/discount_strategy.dart';

void main() {
  group('PercentageDiscountStrategy', () {
    test('10percent of 10000 returns 1000', () {
      const s = PercentageDiscountStrategy(10);
      expect(s.compute(10000), 1000);
    });

    test('0percent returns zero', () {
      const s = PercentageDiscountStrategy(0);
      expect(s.compute(10000), 0);
    });

    test('100percent returns full subtotal', () {
      const s = PercentageDiscountStrategy(100);
      expect(s.compute(5000), 5000);
    });

    test('above 100 clamps to subtotal', () {
      const s = PercentageDiscountStrategy(150);
      expect(s.compute(5000), 5000);
    });
  });

  group('FixedAmountDiscountStrategy', () {
    test('500 returns 500', () {
      const s = FixedAmountDiscountStrategy(500);
      expect(s.compute(10000), 500);
    });

    test('exceeds subtotal clamps to subtotal', () {
      const s = FixedAmountDiscountStrategy(8000);
      expect(s.compute(5000), 5000);
    });

    test('negative returns zero', () {
      const s = FixedAmountDiscountStrategy(-100);
      expect(s.compute(5000), 0);
    });
  });
}

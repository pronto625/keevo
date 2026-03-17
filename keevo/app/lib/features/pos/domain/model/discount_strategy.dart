/// GoF Strategy — two discount algorithms behind one interface.
abstract class DiscountStrategy {
  int compute(int subtotal);
}

class PercentageDiscountStrategy implements DiscountStrategy {
  final int percentage; // 0..100
  const PercentageDiscountStrategy(this.percentage);

  @override
  int compute(int subtotal) {
    if (percentage <= 0) return 0;
    if (percentage >= 100) return subtotal;
    return (subtotal * percentage) ~/ 100;
  }
}

class FixedAmountDiscountStrategy implements DiscountStrategy {
  final int amount; // XAF
  const FixedAmountDiscountStrategy(this.amount);

  @override
  int compute(int subtotal) {
    if (amount <= 0) return 0;
    return amount.clamp(0, subtotal);
  }
}

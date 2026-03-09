import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/domain/model/stock_level_model.dart';

void main() {
  group('StockLevelModel.isLow', () {
    StockLevelModel _level({
      required int quantity,
      required int minimumThreshold,
    }) =>
        StockLevelModel(
          id: 'lvl-1',
          productId: 'prod-1',
          storeId: 'store-1',
          quantity: quantity,
          minimumThreshold: minimumThreshold,
          updatedAt: DateTime(2025, 1, 1),
        );

    test('isLow is true when quantity equals threshold and threshold > 0', () {
      final level = _level(quantity: 5, minimumThreshold: 5);
      expect(level.isLow, isTrue);
    });

    test('isLow is true when quantity is below threshold', () {
      final level = _level(quantity: 2, minimumThreshold: 10);
      expect(level.isLow, isTrue);
    });

    test('isLow is false when quantity is above threshold', () {
      final level = _level(quantity: 20, minimumThreshold: 10);
      expect(level.isLow, isFalse);
    });

    test('isLow is false when threshold is 0 regardless of quantity', () {
      final level = _level(quantity: 0, minimumThreshold: 0);
      expect(level.isLow, isFalse);
    });

    test('isLow is false when threshold is 0 even with low quantity', () {
      final level = _level(quantity: 1, minimumThreshold: 0);
      expect(level.isLow, isFalse);
    });

    test('copyWith preserves isLow logic correctly', () {
      final level = _level(quantity: 5, minimumThreshold: 10);
      expect(level.isLow, isTrue);

      final updated = level.copyWith(quantity: 15);
      expect(updated.isLow, isFalse);
    });
  });
}

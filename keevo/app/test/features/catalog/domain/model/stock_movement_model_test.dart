import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/catalog/domain/model/stock_movement_model.dart';

void main() {
  final kDate = DateTime(2025, 6, 1, 12, 0);

  group('StockMovementModel', () {
    late StockMovementModel baseMovement;

    setUp(() {
      baseMovement = StockMovementModel(
        id: 'mv-1',
        productId: 'prod-1',
        storeId: 'store-1',
        movementType: 'STOCK_ENTRY',
        actorId: 'user-1',
        quantityBefore: 0,
        quantityDelta: 10,
        quantityAfter: 10,
        occurredAt: kDate,
      );
    });

    test('creates movement with required fields', () {
      expect(baseMovement.id, 'mv-1');
      expect(baseMovement.productId, 'prod-1');
      expect(baseMovement.movementType, 'STOCK_ENTRY');
    });

    test('defaults: synced=false, quantityBefore/Delta/After=0 when not set', () {
      final empty = StockMovementModel(
        id: 'mv-2',
        productId: 'prod-2',
        storeId: 'store-2',
        movementType: 'ADJUSTMENT',
        actorId: 'user-2',
        occurredAt: kDate,
      );
      expect(empty.synced, isFalse);
      expect(empty.quantityBefore, 0);
      expect(empty.quantityDelta, 0);
      expect(empty.quantityAfter, 0);
    });

    test('copyWith updates synced state', () {
      final synced = baseMovement.copyWith(synced: true, syncedAt: kDate);
      expect(synced.synced, isTrue);
      expect(synced.syncedAt, kDate);
    });

    test('quantityDelta is negative for SALE movements', () {
      final sale = StockMovementModel(
        id: 'mv-3',
        productId: 'prod-1',
        storeId: 'store-1',
        movementType: 'SALE',
        actorId: 'user-1',
        quantityBefore: 10,
        quantityDelta: -3,
        quantityAfter: 7,
        occurredAt: kDate,
      );
      expect(sale.quantityDelta, isNegative);
      expect(sale.quantityAfter, 7);
    });

    test('fromJson / toJson round-trip', () {
      final json = baseMovement.toJson();
      final restored = StockMovementModel.fromJson(json);
      expect(restored.id, baseMovement.id);
      expect(restored.quantityDelta, baseMovement.quantityDelta);
    });
  });
}


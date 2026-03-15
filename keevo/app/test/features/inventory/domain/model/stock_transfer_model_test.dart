import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';

void main() {
  group('StockTransferModel', () {
    final json = {
      'id': 'tf-001',
      'sourceStoreId': 'src-001',
      'destinationStoreId': 'dst-001',
      'productId': 'prod-001',
      'variantId': null,
      'quantity': 5,
      'actorId': 'actor-001',
      'occurredAt': '2026-03-14T10:00:00.000Z',
      'status': 'COMPLETED',
      'notes': 'Transfert test',
    };

    test('StockTransferModel.fromJson should parse all fields correctly', () {
      final model = StockTransferModel.fromJson(json);
      expect(model.id, 'tf-001');
      expect(model.sourceStoreId, 'src-001');
      expect(model.destinationStoreId, 'dst-001');
      expect(model.productId, 'prod-001');
      expect(model.variantId, isNull);
      expect(model.quantity, 5);
      expect(model.actorId, 'actor-001');
      expect(model.status, 'COMPLETED');
      expect(model.notes, 'Transfert test');
    });

    test('StockTransferModel.isPendingSync should return true when status is PENDING_SYNC', () {
      final model = StockTransferModel(
        id: 'tf-002',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 3,
        actorId: 'actor-001',
        occurredAt: DateTime(2026, 3, 14),
        status: 'PENDING_SYNC',
      );
      expect(model.isPendingSync, isTrue);
      expect(model.isCompleted, isFalse);
    });

    test('StockTransferModel.isCompleted should return true when status is COMPLETED', () {
      final model = StockTransferModel(
        id: 'tf-003',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 10,
        actorId: 'actor-001',
        occurredAt: DateTime(2026, 3, 14),
        status: 'COMPLETED',
      );
      expect(model.isCompleted, isTrue);
      expect(model.isPendingSync, isFalse);
    });

    test('StockTransferModel defaults sourceStoreName and destinationStoreName to empty string', () {
      final model = StockTransferModel(
        id: 'tf-004',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
        productId: 'prod-001',
        quantity: 1,
        actorId: 'actor-001',
        occurredAt: DateTime(2026, 3, 14),
        status: 'COMPLETED',
      );
      expect(model.sourceStoreName, '');
      expect(model.destinationStoreName, '');
    });
  });
}

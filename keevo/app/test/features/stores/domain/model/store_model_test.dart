import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/domain/model/store_type.dart';

void main() {
  final now = DateTime(2025, 1, 1, 10, 0);

  group('StoreModel (Story 3.1 — Task 16)', () {
    test('default type is STORE', () {
      final store = StoreModel(
        id: 'abc',
        name: 'Boutique Test',
        createdAt: now,
        updatedAt: now,
      );
      expect(store.type, StoreType.store);
    });

    test('default isActive is true', () {
      final store = StoreModel(
        id: 'abc',
        name: 'Boutique Test',
        createdAt: now,
        updatedAt: now,
      );
      expect(store.isActive, isTrue);
    });

    test('copyWith updates fields correctly', () {
      final original = StoreModel(
        id: 'abc',
        name: 'Original',
        type: StoreType.store,
        createdAt: now,
        updatedAt: now,
      );
      final updated = original.copyWith(
        name: 'Updated',
        address: '123 Rue Test',
        phone: '+237600000001',
      );
      expect(updated.name, 'Updated');
      expect(updated.address, '123 Rue Test');
      expect(updated.phone, '+237600000001');
      expect(updated.type, StoreType.store); // type unchanged
      expect(updated.id, original.id); // id unchanged
    });

    test('WAREHOUSE type preserved through copyWith', () {
      final warehouse = StoreModel(
        id: 'wh1',
        name: 'Entrepôt',
        type: StoreType.warehouse,
        createdAt: now,
        updatedAt: now,
      );
      final updated = warehouse.copyWith(name: 'Grand Entrepôt');
      expect(updated.type, StoreType.warehouse);
    });

    test('nullable address and phone default to null', () {
      final store = StoreModel(
        id: 'x',
        name: 'Boutique',
        createdAt: now,
        updatedAt: now,
      );
      expect(store.address, isNull);
      expect(store.phone, isNull);
    });
  });
}

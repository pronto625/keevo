import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/stores/domain/model/store_type.dart';

void main() {
  group('StoreType (Story 3.1 — Task 16)', () {
    test('fromString parses STORE case-insensitively', () {
      expect(StoreType.fromString('STORE'), StoreType.store);
      expect(StoreType.fromString('store'), StoreType.store);
      expect(StoreType.fromString('Store'), StoreType.store);
    });

    test('fromString parses WAREHOUSE case-insensitively', () {
      expect(StoreType.fromString('WAREHOUSE'), StoreType.warehouse);
      expect(StoreType.fromString('warehouse'), StoreType.warehouse);
    });

    test('fromString returns store for unknown values', () {
      expect(StoreType.fromString('unknown'), StoreType.store);
      expect(StoreType.fromString(''), StoreType.store);
    });

    test('displayName returns French labels', () {
      expect(StoreType.store.displayName, 'Boutique');
      expect(StoreType.warehouse.displayName, 'Warehouse');
    });

    test('icon returns correct emoji', () {
      expect(StoreType.store.icon, '🏪');
      expect(StoreType.warehouse.icon, '🏭');
    });

    test('values has exactly STORE and WAREHOUSE', () {
      expect(StoreType.values.length, 2);
    });
  });
}

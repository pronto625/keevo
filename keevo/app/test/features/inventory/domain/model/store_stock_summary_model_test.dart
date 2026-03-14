import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/store_stock_summary_model.dart';

void main() {
  group('StoreStockSummaryModel (Story 3.2 — Task 8.2)', () {
    test('holds all fields', () {
      const model = StoreStockSummaryModel(
        storeId: 'store-abc',
        storeName: 'Boutique Centrale',
        storeType: 'STORE',
        productCount: 12,
        totalValueXaf: 500000,
        lowStockCount: 3,
      );

      expect(model.storeId, 'store-abc');
      expect(model.storeName, 'Boutique Centrale');
      expect(model.storeType, 'STORE');
      expect(model.productCount, 12);
      expect(model.totalValueXaf, 500000);
      expect(model.lowStockCount, 3);
      expect(model.isUpdated, false); // default
    });

    test('fromJson parses correctly', () {
      final json = {
        'storeId': 'store-xyz',
        'storeName': 'Entrepôt',
        'storeType': 'WAREHOUSE',
        'productCount': 5,
        'totalValueXaf': 250000,
        'lowStockCount': 1,
      };

      final model = StoreStockSummaryModel.fromJson(json);

      expect(model.storeId, 'store-xyz');
      expect(model.storeName, 'Entrepôt');
      expect(model.storeType, 'WAREHOUSE');
      expect(model.productCount, 5);
      expect(model.totalValueXaf, 250000);
      expect(model.lowStockCount, 1);
    });

    test('copyWith updates isUpdated flag', () {
      const model = StoreStockSummaryModel(
        storeId: 's1',
        storeName: 'Boutique',
        storeType: 'STORE',
        productCount: 1,
        totalValueXaf: 10000,
        lowStockCount: 0,
      );
      final updated = model.copyWith(isUpdated: true);
      expect(updated.isUpdated, true);
      expect(updated.storeId, model.storeId);
    });
  });
}

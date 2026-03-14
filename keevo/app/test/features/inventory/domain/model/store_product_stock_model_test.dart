import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/inventory/domain/model/store_product_stock_model.dart';

void main() {
  group('StoreProductStockModel (Story 3.2 — Task 8.4)', () {
    const baseModel = StoreProductStockModel(
      productId: 'prod-1',
      productName: 'Chaussures Nike',
      storeId: 'store-1',
      quantity: 3,
      minimumThreshold: 5,
      status: 'BAS',
      isLow: true,
      isCritical: false,
    );

    test('holds all fields', () {
      expect(baseModel.productId, 'prod-1');
      expect(baseModel.productName, 'Chaussures Nike');
      expect(baseModel.storeId, 'store-1');
      expect(baseModel.quantity, 3);
      expect(baseModel.minimumThreshold, 5);
      expect(baseModel.status, 'BAS');
      expect(baseModel.isLow, true);
      expect(baseModel.isCritical, false);
    });

    test('isLow is true when qty <= threshold AND threshold > 0', () {
      expect(baseModel.isLow, true);
    });

    test('isCritical is true when qty == 0', () {
      const critical = StoreProductStockModel(
        productId: 'p',
        productName: 'P',
        storeId: 's',
        quantity: 0,
        status: 'CRITIQUE',
        isLow: false,
        isCritical: true,
      );
      expect(critical.isCritical, true);
    });

    test('status returns BAS when isLow', () {
      expect(baseModel.status, 'BAS');
    });

    test('status returns CRITIQUE when isCritical', () {
      const crit = StoreProductStockModel(
        productId: 'p',
        productName: 'P',
        storeId: 's',
        quantity: 0,
        status: 'CRITIQUE',
        isLow: false,
        isCritical: true,
      );
      expect(crit.status, 'CRITIQUE');
    });

    test('status returns NORMAL when neither low nor critical', () {
      const normal = StoreProductStockModel(
        productId: 'p',
        productName: 'P',
        storeId: 's',
        quantity: 10,
        minimumThreshold: 5,
        status: 'NORMAL',
        isLow: false,
        isCritical: false,
      );
      expect(normal.status, 'NORMAL');
    });

    test('fromJson parses correctly', () {
      final json = {
        'productId': 'prod-2',
        'productName': 'Chemise',
        'variantId': null,
        'variantLabel': null,
        'storeId': 'store-2',
        'quantity': 10,
        'minimumThreshold': 5,
        'status': 'NORMAL',
        'isLow': false,
        'isCritical': false,
      };

      final model = StoreProductStockModel.fromJson(json);
      expect(model.productName, 'Chemise');
      expect(model.status, 'NORMAL');
      expect(model.isLow, false);
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_rejection_policy.dart';

void main() {
  group('Story 12.7 — MODULE_INACTIVE holds the operation', () {
    test('is held, not transient', () {
      expect(isHeldSyncRejection('MODULE_INACTIVE'), isTrue);
      expect(isHeldSyncRejection('PRODUCT_NOT_FOUND'), isFalse);
      expect(isHeldSyncRejection(null), isFalse);
      expect(isTransientSyncRejection('MODULE_INACTIVE'), isFalse);
    });

    test('a held money / stock operation is never quarantined', () {
      for (final op in moneyLedgerOperations) {
        expect(quarantinesOnRejection(op, 'MODULE_INACTIVE'), isFalse,
            reason: op);
      }
      expect(quarantinesOnRejection('CREATE_SALE', 'INSUFFICIENT_STOCK'),
          isTrue);
    });

    test('INTERNAL_ERROR stays transient', () {
      expect(isTransientSyncRejection('INTERNAL_ERROR'), isTrue);
      expect(quarantinesOnRejection('CREATE_SALE', 'INTERNAL_ERROR'), isFalse);
    });
  });
}

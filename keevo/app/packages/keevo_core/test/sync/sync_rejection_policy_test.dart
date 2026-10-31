import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/sync/sync_rejection_policy.dart';

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

  group('Story 13.5 — MODULE_READ_ONLY holds the operation too', () {
    test('is held, not transient, never quarantined', () {
      expect(isHeldSyncRejection('MODULE_READ_ONLY'), isTrue);
      expect(isTransientSyncRejection('MODULE_READ_ONLY'), isFalse);
      expect(heldSyncRejectionReasons,
          unorderedEquals(['MODULE_INACTIVE', 'MODULE_READ_ONLY']));
      for (final op in moneyLedgerOperations) {
        expect(quarantinesOnRejection(op, 'MODULE_READ_ONLY'), isFalse,
            reason: op);
      }
    });

    test('dominantHeldReason: MODULE_INACTIVE wins, whatever the order', () {
      expect(dominantHeldReason(['MODULE_READ_ONLY', 'MODULE_INACTIVE']),
          'MODULE_INACTIVE');
      expect(dominantHeldReason(['MODULE_INACTIVE', 'MODULE_READ_ONLY']),
          'MODULE_INACTIVE');
      expect(dominantHeldReason(['MODULE_READ_ONLY', 'PRODUCT_NOT_FOUND']),
          'MODULE_READ_ONLY');
      expect(dominantHeldReason(['PRODUCT_NOT_FOUND', null]), isNull);
      expect(dominantHeldReason(const []), isNull);
    });
  });
}

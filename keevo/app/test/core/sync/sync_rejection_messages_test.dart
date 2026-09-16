import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_rejection_messages.dart';

void main() {
  group('friendlySyncRejectionReason', () {
    test('maps known domain codes to French messages', () {
      expect(friendlySyncRejectionReason('PRODUCT_NAME_ALREADY_EXISTS'),
          'Un produit avec ce nom existe déjà');
      expect(friendlySyncRejectionReason('PLAN_LIMIT_EXCEEDED'),
          'Limite du plan atteinte');
    });

    test('falls back to a generic message carrying the raw code', () {
      expect(friendlySyncRejectionReason('SOME_UNKNOWN_CODE'),
          contains('SOME_UNKNOWN_CODE'));
    });

    test('handles a null reason', () {
      expect(friendlySyncRejectionReason(null), isNotEmpty);
    });
  });

  group('friendlySyncOperationLabel', () {
    test('maps known operations to French labels', () {
      expect(friendlySyncOperationLabel('CREATE_PRODUCT'), 'Création de produit');
      expect(friendlySyncOperationLabel('STOCK_ADJUST'), 'Ajustement de stock');
    });

    test('falls back to the raw operation string when unknown', () {
      expect(friendlySyncOperationLabel('SOME_FUTURE_OP'), 'SOME_FUTURE_OP');
    });
  });
}

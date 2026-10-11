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

    test('v1s-13-8: maps the inventory session codes', () {
      expect(friendlySyncRejectionReason('INVENTORY_SESSION_ALREADY_ACTIVE'),
          'Un autre inventaire est déjà en cours dans cette boutique');
      expect(friendlySyncRejectionReason('INVENTORY_STORE_NOT_FOUND'),
          'Boutique de l\'inventaire introuvable');
      expect(friendlySyncRejectionReason('INVENTORY_INVALID_CATEGORIES'),
          'Catégories de l\'inventaire invalides');
      expect(friendlySyncRejectionReason('INVENTORY_SESSION_NOT_IN_PROGRESS'),
          'Cet inventaire n\'est plus en cours');
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

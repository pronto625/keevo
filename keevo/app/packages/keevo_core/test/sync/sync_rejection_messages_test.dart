import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/sync/sync_rejection_messages.dart';

void main() {
  group('friendlySyncRejectionReason', () {
    test('maps known domain codes to French messages', () {
      expect(friendlySyncRejectionReason('PRODUCT_NAME_ALREADY_EXISTS'),
          'Un produit avec ce nom existe déjà');
      expect(friendlySyncRejectionReason('PLAN_LIMIT_EXCEEDED'),
          'Limite de votre forfait atteinte — passez à un forfait supérieur');
    });

    test('J6: a second warehouse is reported as « entrepôt », not a name '
        'clash', () {
      expect(friendlySyncRejectionReason('WAREHOUSE_ALREADY_EXISTS'),
          'Un seul entrepôt est autorisé par compte');
    });

    test('v1s-16-12: every code a queued operation can be rejected with has a '
        'French message', () {
      const codes = [
        'INVENTORY_SESSION_NOT_FOUND',
        'OPTIMISTIC_LOCK',
        'INTERNAL_ERROR',
        'TRANSACTION_ROLLED_BACK',
        'DATA_INTEGRITY_VIOLATION',
        'UNKNOWN_OPERATION_TYPE',
        'SUBSCRIPTION_NOT_FOUND',
        'TENANT_NOT_FOUND',
        'WAREHOUSE_ALREADY_EXISTS',
        'USER_ALREADY_EXISTS',
        'EMPLOYEE_NOT_FOUND',
        'CANNOT_DEMOTE_LAST_OWNER',
      ];
      for (final code in codes) {
        final message = friendlySyncRejectionReason(code);
        expect(message, isNot(contains(code)), reason: code);
        expect(message, isNot(startsWith('Refusé par le serveur')),
            reason: code);
        expect(message, isNot(startsWith('Erreur')), reason: code);
      }
    });

    test('v1s-16-12: raw exception text is never shown', () {
      expect(friendlySyncRejectionReason('Product not found: 1234-abcd'),
          'Élément introuvable sur le serveur');
      expect(friendlySyncRejectionReason('Quantity must be positive'),
          'Données refusées par le serveur');
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

    test('an unknown code falls back to a generic message without the code', () {
      expect(friendlySyncRejectionReason('SOME_UNKNOWN_CODE'),
          'Refusé par le serveur');
    });

    test('handles a null reason', () {
      expect(friendlySyncRejectionReason(null), isNotEmpty);
    });
  });

  test('12.7: MODULE_INACTIVE has its French message', () {
    expect(friendlySyncRejectionReason('MODULE_INACTIVE'),
        'Module non activé pour votre compte');
  });

  test('13.5: MODULE_READ_ONLY has its French message', () {
    expect(friendlySyncRejectionReason('MODULE_READ_ONLY'),
        'Module en lecture seule : abonnement expiré');
  });

  group('friendlySyncOperationLabel', () {
    test('maps known operations to French labels', () {
      expect(friendlySyncOperationLabel('CREATE_PRODUCT'), 'Création de produit');
      expect(friendlySyncOperationLabel('STOCK_ADJUST'), 'Ajustement de stock');
      for (final op in const [
        'CREATE_DRAFT_PRODUCT',
        'SUBMIT_FEEDBACK',
        'CREATE_EMPLOYEE',
      ]) {
        expect(friendlySyncOperationLabel(op), isNot(op));
      }
    });

    test('an unknown operation falls back to a generic label', () {
      expect(friendlySyncOperationLabel('SOME_FUTURE_OP'), 'Opération');
    });
  });
}

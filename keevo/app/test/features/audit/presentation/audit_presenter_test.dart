import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/audit/domain/audit_format.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/presentation/audit_presenter.dart';

AuditEntryDto _entry({
  String action = 'PRODUCT_CREATED',
  String entityType = 'Product',
  String userId = 'user-awa',
  String? valueBefore,
  String? valueAfter,
  String? entityLabel,
  String? actorName,
  String? actorPhone,
  String? metadata,
  String? source,
  String? deviceId,
  DateTime? at,
}) =>
    AuditEntryDto(
      id: 'e1',
      entityType: entityType,
      entityId: 'ent-1',
      action: action,
      valueBefore: valueBefore,
      valueAfter: valueAfter,
      userId: userId,
      actorPhone: actorPhone,
      occurredAt: at ?? DateTime.utc(2026, 10, 12, 8, 30),
      entityLabel: entityLabel,
      actorName: actorName,
      metadata: metadata,
      source: source,
      deviceId: deviceId,
    );

void main() {
  group('sentence', () {
    test('known action: actor + verb + object label', () {
      final s = AuditPresenter.sentence(
          _entry(actorName: 'Awa', entityLabel: 'Pagne wax'));
      expect(s.actor, 'Awa');
      expect(s.object, 'Pagne wax');
      expect(s.toString(), 'Awa a créé le produit Pagne wax');
    });

    // A38: a sale's stock decrement read « a ajusté le stock de X ».
    test('stock movement reads after its cause (movement type)', () {
      String line(String? type) => AuditPresenter.sentence(_entry(
            action: 'STOCK_ADJUSTED',
            entityType: 'StockMovement',
            actorName: 'Awa',
            entityLabel: 'Pagne wax',
            valueAfter: type == null
                ? '{"quantityChange":-1}'
                : '{"movementType":"$type","quantityChange":-1}',
          )).toString();

      expect(line('SALE'), 'Awa a vendu Pagne wax');
      expect(line('SALE_CANCELLED'), 'Awa a remis en stock Pagne wax');
      expect(line('STOCK_ENTRY'), 'Awa a réapprovisionné Pagne wax');
      expect(line('TRANSFER_OUT'), 'Awa a transféré du stock de Pagne wax');
      expect(line('TRANSFER_IN'),
          'Awa a reçu par transfert du stock de Pagne wax');
      // Manual adjustments (and legacy rows) keep their own wording.
      expect(line('ADJUSTMENT'), 'Awa a ajusté le stock de Pagne wax');
      expect(line(null), 'Awa a ajusté le stock de Pagne wax');
    });

    // v1s-16-23: a transfer names the other store; legacy rows unchanged.
    group('transfer counterpart', () {
      AuditEntryDto transfer(String type, String? metadata,
              {String label = 'Pagne wax'}) =>
          _entry(
            action: 'STOCK_ADJUSTED',
            entityType: 'StockMovement',
            actorName: 'Awa',
            entityLabel: label,
            valueAfter: '{"movementType":"$type","quantityChange":-3}',
            metadata: metadata,
          );

      test('sending reads « … vers B », receipt « … depuis A »', () {
        final out = AuditPresenter.sentence(
            transfer('TRANSFER_OUT', '{"destinationStoreName":"Boutique B"}'));
        expect(out.toString(),
            'Awa a transféré du stock de Pagne wax vers Boutique B');
        expect(out.tailLead, 'vers');
        expect(out.tail, 'Boutique B');
        expect(
            AuditPresenter.sentence(
                    transfer('TRANSFER_IN', '{"sourceStoreName":"Boutique A"}'))
                .toString(),
            'Awa a reçu par transfert du stock de Pagne wax depuis Boutique A');
      });

      test('elided object keeps the counterpart', () {
        expect(
            AuditPresenter.sentence(transfer(
                    'TRANSFER_OUT', '{"destinationStoreName":"Boutique B"}',
                    label: 'Ananas'))
                .toString(),
            'Awa a transféré du stock d\'Ananas vers Boutique B');
      });

      test('legacy entry or missing store: sentence unchanged', () {
        expect(
            AuditPresenter.sentence(transfer('TRANSFER_OUT', null)).toString(),
            'Awa a transféré du stock de Pagne wax');
        expect(
            AuditPresenter.sentence(
                    transfer('TRANSFER_IN', '{"storeId":"x","notes":"n"}'))
                .tail,
            isNull);
        // The key of the other direction is ignored.
        expect(
            AuditPresenter.sentence(transfer(
                    'TRANSFER_OUT', '{"sourceStoreName":"Boutique A"}'))
                .tail,
            isNull);
      });

      test('Contexte rows « Vers » / « Depuis »', () {
        final out = AuditPresenter.context(transfer('TRANSFER_OUT',
            '{"storeId":"x","destinationStoreName":"Boutique B"}'));
        expect(
            out.map((c) => '${c.label} : ${c.value}'), ['Vers : Boutique B']);
        final inn = AuditPresenter.context(
            transfer('TRANSFER_IN', '{"sourceStoreName":"Boutique A"}'));
        expect(
            inn.map((c) => '${c.label} : ${c.value}'), ['Depuis : Boutique A']);
      });
    });

    test('price override reads « a modifié le prix de »', () {
      final s = AuditPresenter.sentence(_entry(
          action: 'PRICE_OVERRIDDEN',
          entityType: 'PriceOverride',
          actorName: 'Awa',
          entityLabel: 'Pagne wax 6 yards'));
      expect(s.toString(), 'Awa a modifié le prix de Pagne wax 6 yards');
    });

    test('self → « Vous avez … », reflexive → « Vous vous êtes … »', () {
      expect(
        AuditPresenter.sentence(_entry(entityLabel: 'Savon'),
                selfUserId: 'user-awa')
            .toString(),
        'Vous avez créé le produit Savon',
      );
      expect(
        AuditPresenter.sentence(
                _entry(action: 'USER_AUTHENTICATED', entityType: 'User'),
                selfUserId: 'user-awa')
            .toString(),
        'Vous vous êtes connecté(e)',
      );
      expect(
        AuditPresenter.sentence(_entry(
                action: 'USER_AUTHENTICATED',
                entityType: 'User',
                actorName: 'Awa'))
            .toString(),
        'Awa s\'est connecté(e)',
      );
    });

    test('unknown action → generic « a effectué « Foo bar » », generic icon',
        () {
      final e =
          _entry(action: 'FOO_BAR', entityType: 'Widget', actorName: 'Awa');
      expect(
          AuditPresenter.sentence(e).toString(), 'Awa a effectué « Foo bar »');
      expect(AuditPresenter.actionInfo('FOO_BAR').icon, Icons.history_rounded);
      expect(
          AuditPresenter.actionInfo('FOO_BAR').category, AuditCategory.other);
      expect(AuditPresenter.isKnownAction('FOO_BAR'), isFalse);
      expect(
          AuditPresenter.sentence(_entry(
                  action: 'FOO_BAR', actorName: 'Awa', entityLabel: 'Truc'))
              .toString(),
          'Awa a effectué « Foo bar » sur Truc');
    });

    test('sale sentence carries the amount', () {
      final e = _entry(
          action: 'SALE_COMPLETED',
          entityType: 'Sale',
          actorName: 'Awa',
          entityLabel: 'Vente',
          valueAfter: '{"totalAmount":15000,"discountAmount":0}');
      expect(AuditPresenter.sentence(e).toString(),
          'Awa a enregistré une vente de 15 000 XAF');
    });

    test('employee target without label → « un employé »', () {
      final e = _entry(
          action: 'EMPLOYEE_ROLE_CHANGED',
          entityType: 'Employee',
          actorName: 'Moussa');
      expect(AuditPresenter.sentence(e).toString(),
          'Moussa a changé le rôle d\'un employé');
    });

    test('every listener action is in the catalogue', () {
      const actions = [
        'USER_REGISTERED',
        'USER_AUTHENTICATED',
        'ONBOARDING_COMPLETED',
        'PRODUCT_CREATED',
        'PRODUCT_UPDATED',
        'PRODUCT_ARCHIVED',
        'PRODUCT_ACTIVATED',
        'PRICE_OVERRIDDEN',
        'STOCK_ADJUSTED',
        'STOCK_THRESHOLD_BREACHED',
        'STOCK_FORCED_ZERO',
        'CLIENT_CREATED',
        'CLIENT_ARCHIVED',
        'SUPPLIER_CREATED',
        'SUPPLIER_ARCHIVED',
        'CATEGORY_CREATED',
        'CATEGORY_RENAMED',
        'CATEGORY_ACTIVATED',
        'CATEGORY_DEACTIVATED',
        'STORE_CREATED',
        'STORE_UPDATED',
        'STORE_DEACTIVATED',
        'EMPLOYEE_UPDATED',
        'EMPLOYEE_ROLE_CHANGED',
        'EMPLOYEE_PASSWORD_SET_BY_OWNER',
        'ACCOUNT_DELETION_REQUESTED',
        'ACCOUNT_DELETION_CANCELLED',
        'SALE_COMPLETED',
        'SALE_PENDING_VALIDATION',
        'SALE_AUTO_VALIDATED',
        'SALE_MANUALLY_VALIDATED',
        'SALE_CANCELLED',
        'SALE_CORRECTED',
        'DAY_CLOSED',
        'INVENTORY_SESSION_CREATED',
        'INVENTORY_SESSION_CANCELLED',
        'INVENTORY_COUNT_SAVED',
        'INVENTORY_REPORT_GENERATED',
        'INVENTORY_VALIDATED',
        'PASSWORD_RESET_REQUESTED',
        'PASSWORD_RESET',
        // J7 / J10
        'STORE_REACTIVATED',
        'PRODUCT_UNARCHIVED',
        'STOCK_THRESHOLD_UPDATED',
        'SUPPLIER_UPDATED',
        'SUPPLIER_PRODUCTS_UPDATED',
        'REPORT_PREFERENCES_UPDATED',
      ];
      for (final a in actions) {
        expect(AuditPresenter.isKnownAction(a), isTrue, reason: a);
      }
    });

    test('J7 / J10: owner sentences for the newly audited actions', () {
      String self(String action, String type, String? label) =>
          AuditPresenter.sentence(
                  _entry(action: action, entityType: type, entityLabel: label),
                  selfUserId: 'user-awa')
              .toString();
      expect(self('PRODUCT_UNARCHIVED', 'Product', 'Savon'),
          'Vous avez désarchivé le produit Savon');
      expect(self('STOCK_THRESHOLD_UPDATED', 'Product', 'Savon'),
          'Vous avez modifié le seuil d\'alerte de Savon');
      expect(self('SUPPLIER_UPDATED', 'Supplier', 'Socada'),
          'Vous avez modifié le fournisseur Socada');
      expect(self('SUPPLIER_PRODUCTS_UPDATED', 'Supplier', 'Socada'),
          'Vous avez modifié les produits du fournisseur Socada');
      expect(self('STORE_REACTIVATED', 'Store', 'Akwa'),
          'Vous avez réactivé la boutique Akwa');
      expect(
          self('REPORT_PREFERENCES_UPDATED', 'Tenant',
              'Préférences des rapports'),
          'Vous avez modifié les préférences des rapports');
    });
  });

  group('device (v1s-16-20)', () {
    test('« Appareil de <actor> », null without device', () {
      expect(
          AuditPresenter.deviceLabel(
              _entry(deviceId: 'android-3f2a9c1d8e7b6a5f', actorName: 'Awa')),
          'Appareil de Awa');
      expect(AuditPresenter.deviceLabel(_entry()), isNull);
      expect(AuditPresenter.deviceLabel(_entry(deviceId: 'x')),
          'Appareil de l\'auteur');
    });

    test('DATA_EXPORTED is a known action; unknown object type humanized', () {
      expect(AuditPresenter.isKnownAction('DATA_EXPORTED'), isTrue);
      expect(AuditPresenter.actionInfo('DATA_EXPORTED').label,
          'Données exportées');
      expect(AuditPresenter.objectTypeLabel('Gadget'), 'Gadget');
      expect(AuditPresenter.objectTypeLabel('FancyThing'), 'Fancy thing');
      expect(AuditPresenter.objectTypeLabel('Product'), 'Produits');
    });
  });

  group('actor', () {
    test('name > metadata actorLabel > phone > generic', () {
      expect(AuditPresenter.actorName(_entry(actorName: 'Awa Diallo')),
          'Awa Diallo');
      expect(
          AuditPresenter.actorName(_entry(metadata: '{"actorLabel":"Moussa"}')),
          'Moussa');
      expect(AuditPresenter.actorName(_entry(actorPhone: '+237600000000')),
          '+237600000000');
      expect(AuditPresenter.actorName(_entry()), 'Un utilisateur');
    });
  });

  group('object label', () {
    test('label, sale amount, legacy value_after name, type noun', () {
      expect(AuditPresenter.objectLabel(_entry(entityLabel: 'Pagne')), 'Pagne');
      expect(
          AuditPresenter.objectLabel(_entry(
              entityType: 'Sale',
              entityLabel: 'Vente',
              valueAfter: '{"totalAmount":15000}')),
          'Vente de 15 000 XAF');
      expect(
          AuditPresenter.objectLabel(
              _entry(valueAfter: '{"productName":"Savon"}')),
          'Savon');
      expect(
          AuditPresenter.objectLabel(
              _entry(entityType: 'DayClosure', action: 'DAY_CLOSED')),
          'Clôture de journée');
      expect(AuditPresenter.objectLabel(_entry(entityType: 'Employee')),
          'Employé');
      expect(AuditPresenter.objectLabel(_entry(entityType: 'Export')),
          'Export de données');
      expect(AuditPresenter.objectLabel(_entry(entityType: 'FancyThing')),
          'Fancy thing');
    });
  });

  group('changes', () {
    test('XAF amounts, translated codes, units', () {
      final rows = AuditPresenter.changes(_entry(
        action: 'PRICE_OVERRIDDEN',
        valueBefore: '{"cataloguePrice":15000}',
        valueAfter: '{"appliedPrice":12500,"productName":"Pagne"}',
      ));
      expect(rows,
          contains(const AuditChange('Prix appliqué', '—', '12 500 XAF')));
      expect(rows,
          contains(const AuditChange('Prix catalogue', '15 000 XAF', '—')));

      final stock = AuditPresenter.changes(_entry(
        action: 'STOCK_ADJUSTED',
        valueBefore: '{"quantityBefore":5}',
        valueAfter:
            '{"movementType":"PURCHASE","quantityChange":10,"quantityAfter":15}',
      ));
      expect(
          stock, contains(const AuditChange('Variation', '—', '+10 unité(s)')));
      expect(stock,
          contains(const AuditChange('Type de mouvement', '—', 'Réception')));
      expect(stock,
          contains(const AuditChange('Quantité avant', '5 unité(s)', '—')));
    });

    test('status before/after translated, lists summarised', () {
      final rows = AuditPresenter.changes(_entry(
        action: 'SALE_CANCELLED',
        entityType: 'Sale',
        valueBefore: '{"status":"PENDING_VALIDATION"}',
        valueAfter: '{"status":"CANCELLED","itemsSnapshot":[{"a":1},{"b":2}]}',
      ));
      expect(
          rows,
          contains(const AuditChange(
              'Statut', 'En attente de validation', 'Annulé(e)')));
      expect(
          rows, contains(const AuditChange('Articles', '—', '2 article(s)')));
    });

    test('list snapshots (sale correction) → item counts', () {
      final rows = AuditPresenter.changes(_entry(
        action: 'SALE_CORRECTED',
        valueBefore: '[{"x":1},{"x":2},{"x":3}]',
        valueAfter: '[{"x":1}]',
      ));
      expect(rows,
          [const AuditChange('Articles', '3 article(s)', '1 article(s)')]);
    });

    test('unknown keys render raw; ids and context keys are hidden', () {
      final rows = AuditPresenter.changes(_entry(
        action: 'FOO_BAR',
        valueAfter:
            '{"someField":"abc","storeId":"s-1","notes":"x","saleId":"y"}',
      ));
      expect(rows, [const AuditChange('Some field', '—', 'abc')]);
    });

    test('codes are translated only for code-typed keys', () {
      final rows = AuditPresenter.changes(_entry(
          valueAfter:
              '{"productName":"SALE","name":"STORE","movementType":"SALE","type":"STORE"}'));
      expect(rows, contains(const AuditChange('Produit', '—', 'SALE')));
      expect(rows, contains(const AuditChange('Nom', '—', 'STORE')));
      expect(
          rows, contains(const AuditChange('Type de mouvement', '—', 'Vente')));
      expect(rows, contains(const AuditChange('Type', '—', 'Boutique')));
      expect(
          AuditPresenter.context(
              _entry(metadata: '{"notes":"SALE","scope":"FULL"}')),
          [
            const AuditContextItem('Motif', 'SALE'),
            const AuditContextItem('Périmètre', 'Complet'),
          ]);
    });

    test('every entity type written by the listener is a selectable object',
        () {
      for (final t in [
        'Product',
        'Sale',
        'StockMovement',
        'PriceOverride',
        'Client',
        'Supplier',
        'Category',
        'Store',
        'Employee',
        'DayClosure',
        'InventorySession',
        'InventoryCount',
        'StockLevel',
        'StockAlert',
        'Tenant',
        'User',
      ]) {
        expect(AuditPresenter.objectTypes.containsKey(t), isTrue, reason: t);
      }
    });

    test('v1s-16-20: nested maps, UUID values and *Id keys never shown', () {
      final rows = AuditPresenter.changes(_entry(
        valueBefore:
            '{"price":1000,"productId":"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"}',
        valueAfter: '{"price":1500,"criteria":{"q":"x"},"ref":'
            '"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f","categoryId":"c-1",'
            '"draftProductIds":["a"],"mode":"DIRECT","kind":"FOO_BAR"}',
      ));
      expect(rows, [
        const AuditChange('Prix', '1 000 XAF', '1 500 XAF'),
        const AuditChange('Mode', '—', 'Téléchargement direct'),
        const AuditChange('Kind', '—', 'Foo bar'),
      ]);
    });

    test('v1s-16-20: unchanged snapshot fields skipped, sale ids stripped', () {
      expect(
          AuditPresenter.changes(_entry(
              valueBefore: '{"name":"A","sku":"S1","price":1000}',
              valueAfter: '{"name":"A","sku":"S1","price":1500}')),
          [const AuditChange('Prix', '1 000 XAF', '1 500 XAF')]);
      expect(
          AuditPresenter.context(_entry(
              metadata: '{"notes":"Annulation vente '
                  '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"}')),
          [const AuditContextItem('Motif', 'Annulation vente')]);
      expect(
          AuditPresenter.objectLabel(
              _entry(entityLabel: 'SOME_CODE', entityType: 'Product')),
          'Produit');
      expect(
          AuditPresenter.objectLabel(
              _entry(entityLabel: '{"a":1}', entityType: 'Product')),
          'Produit');
    });

    test('malformed or absent JSON never throws', () {
      expect(AuditPresenter.changes(_entry(valueAfter: 'not json')), isEmpty);
      expect(AuditPresenter.changes(_entry()), isEmpty);
    });
  });

  group('context', () {
    test('metadata: justification, motif, automatic; technical keys hidden',
        () {
      final items = AuditPresenter.context(_entry(
        metadata:
            '{"actorLabel":"Awa","storeId":"s","occurredAt":"x","justification":"Erreur de saisie","notes":"Casse","isAutomatic":true}',
      ));
      expect(items, [
        const AuditContextItem('Justification', 'Erreur de saisie'),
        const AuditContextItem('Motif', 'Casse'),
        const AuditContextItem('Déclenchement', 'Automatique'),
      ]);
    });

    test('legacy row: context still in value_after', () {
      final items = AuditPresenter.context(_entry(
          valueAfter: '{"status":"CANCELLED","justification":"Client parti"}'));
      expect(items, [const AuditContextItem('Justification', 'Client parti')]);
    });

    test('inventory notes are readable, ids hidden', () {
      expect(
          AuditPresenter.context(_entry(
              metadata:
                  '{"notes":"INVENTORY:7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f",'
                  '"sessionId":"s-1","ip":"1.2.3.4"}')),
          [const AuditContextItem('Motif', 'Ajustement d\'inventaire')]);
    });

    test('empty when nothing to show', () {
      expect(AuditPresenter.context(_entry(metadata: '{"actorLabel":"A"}')),
          isEmpty);
    });
  });

  group('source and formats', () {
    test('source labels', () {
      expect(AuditPresenter.sourceLabel(_entry(source: 'ONLINE')), 'En ligne');
      expect(
          AuditPresenter.sourceLabel(
              _entry(source: 'SYNC', deviceId: 'tab-1', actorName: 'Awa')),
          'Synchronisé depuis l\'appareil de Awa');
      expect(AuditPresenter.sourceLabel(_entry(source: 'SYNC')),
          'Synchronisé depuis l\'appareil');
      expect(AuditPresenter.sourceLabel(_entry(source: 'SYSTEM')),
          'Automatique (système)');
      expect(AuditPresenter.sourceLabel(_entry()), 'Non renseigné');
    });

    test('XAF and dates', () {
      expect(AuditPresenter.formatXaf(15000), '15 000 XAF');
      expect(AuditPresenter.formatXaf(1250000), '1 250 000 XAF');
      expect(AuditPresenter.formatXaf(0), '0 XAF');
      final local = DateTime(2026, 3, 5, 9, 7);
      expect(AuditPresenter.formatDateTime(local), '05/03/2026 09:07');
    });

    test('day headers', () {
      final now = DateTime(2026, 10, 12, 15);
      expect(AuditPresenter.dayHeader(DateTime(2026, 10, 12, 1), now: now),
          'Aujourd\'hui');
      expect(AuditPresenter.dayHeader(DateTime(2026, 10, 11, 23), now: now),
          'Hier');
      expect(AuditPresenter.dayHeader(DateTime(2026, 8, 3, 10), now: now),
          '03 août 2026');
    });
  });

  // Story 13.8b: module activation / deactivation entries (13.8a) read in
  // French — title, sentence, object type and every payload key.
  group('module lifecycle entries', () {
    test('titles, sentence and object type', () {
      expect(
          AuditPresenter.actionInfo('MODULE_ACTIVATED').label, 'Module activé');
      expect(AuditPresenter.actionInfo('MODULE_DEACTIVATED').label,
          'Module désactivé');
      expect(
          AuditPresenter.sentence(_entry(
            action: 'MODULE_DEACTIVATED',
            entityType: 'Module',
            entityLabel: 'Clients & fournisseurs',
            metadata: '{"actorLabel":"Administrateur Keevo"}',
          )).toString(),
          'Administrateur Keevo a désactivé le module Clients & fournisseurs');
      expect(AuditPresenter.objectTypeLabel('Module'), 'Modules');
      expect(AuditPresenter.typeNouns['Module'], 'Module');
    });

    test('payload keys have French labels', () {
      expect(
          AuditFormat.changesText(
              null,
              '{"module":"Clients & fournisseurs","statut":"Actif",'
              '"finAbonnement":"30/11/2026"}'),
          'Module : Clients & fournisseurs ; Statut : Actif ; '
          'Fin de l\'abonnement : 30/11/2026');
      expect(
          AuditFormat.changesText(
              null,
              '{"module":"Clients & fournisseurs","statut":"Désactivé",'
              '"donnees":"Conservées"}'),
          'Module : Clients & fournisseurs ; Statut : Désactivé ; '
          'Données : Conservées');
    });
  });
  group('invoice entries', () {
    String said(String action, String entityType, String? label) =>
        AuditPresenter.sentence(
                _entry(
                    action: action, entityType: entityType, entityLabel: label),
                selfUserId: 'user-awa')
            .toString();

    test('sentences of the 4 invoice actions and the settings change', () {
      expect(said('INVOICE_CREATED', 'Invoice', 'brouillon de Société Ndolo'),
          'Vous avez créé la facture brouillon de Société Ndolo');
      expect(said('INVOICE_ISSUED', 'Invoice', 'FAC-2026-000001'),
          'Vous avez émis la facture FAC-2026-000001');
      expect(said('INVOICE_PAID', 'Invoice', 'FAC-2026-000001'),
          'Vous avez encaissé la facture FAC-2026-000001');
      expect(said('INVOICE_CANCELLED', 'Invoice', 'FAC-2026-000002'),
          'Vous avez annulé la facture FAC-2026-000002');
      expect(
          said('INVOICING_SETTINGS_UPDATED', 'InvoicingSettings',
              'Boutique Awa SARL'),
          'Vous avez modifié les paramètres de facturation');
    });

    test('titles and object types', () {
      expect(
          AuditPresenter.actionInfo('INVOICE_ISSUED').label, 'Facture émise');
      expect(AuditPresenter.actionInfo('INVOICING_SETTINGS_UPDATED').label,
          'Paramètres de facturation modifiés');
      expect(AuditPresenter.objectTypeLabel('Invoice'), 'Factures');
      expect(AuditPresenter.objectTypeLabel('InvoicingSettings'),
          'Paramètres de facturation');
      expect(AuditPresenter.typeNouns['Invoice'], 'Facture');
    });
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

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
      ];
      for (final a in actions) {
        expect(AuditPresenter.isKnownAction(a), isTrue, reason: a);
      }
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
          'Un employé');
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
      expect(rows, contains(const AuditChange('Type de mouvement', '—', 'Vente')));
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
        'Product', 'Sale', 'StockMovement', 'PriceOverride', 'Client',
        'Supplier', 'Category', 'Store', 'Employee', 'DayClosure',
        'InventorySession', 'InventoryCount', 'StockLevel', 'StockAlert',
        'Tenant', 'User',
      ]) {
        expect(AuditPresenter.objectTypes.containsKey(t), isTrue, reason: t);
      }
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

    test('empty when nothing to show', () {
      expect(AuditPresenter.context(_entry(metadata: '{"actorLabel":"A"}')),
          isEmpty);
    });
  });

  group('source and formats', () {
    test('source labels', () {
      expect(AuditPresenter.sourceLabel(_entry(source: 'ONLINE')), 'En ligne');
      expect(
          AuditPresenter.sourceLabel(_entry(source: 'SYNC', deviceId: 'tab-1')),
          'Synchronisé depuis l\'appareil tab-1');
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
}

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/audit/domain/audit_format.dart';
import 'package:keevo/features/audit/domain/audit_labels.dart';
import 'package:keevo/features/audit/presentation/audit_presenter.dart';
import 'package:keevo/features/export/domain/local/local_export_schema.dart';

/// v1s-18-5 — the app catalogue equals the shared contract file
/// `keevo/contracts/export-datasets.json`, itself pinned to the server
/// providers by the backend `ExportContractFileTest`. A server change breaks
/// the backend test until the JSON is regenerated, then this test until
/// `local_export_schema.dart` follows.
void main() {
  final contract =
      jsonDecode(File('../contracts/export-datasets.json').readAsStringSync())
          as Map<String, dynamic>;

  test('datasets, filters and columns match the contract (same order)', () {
    final expected = contract['datasets'] as List<dynamic>;
    final actual = [
      for (final d in localExportCatalog)
        {
          'key': d.key,
          'label': d.label,
          'storeFilter': d.storeFilter,
          'periodFilter': d.periodFilter,
          'criteria': d.criteria,
          'columns': [
            for (final c in d.columns)
              {'key': c.key, 'label': c.label, 'type': c.type.contractName},
          ],
        },
    ];
    expect(actual, expected);
  });

  test('code → French label maps match the contract', () {
    expect(exportLabels, contract['labels']);
  });

  test('constants match the contract', () {
    expect({
      'ownerLabel': exportOwnerLabel,
      'stockStatus': [exportStockOutOfStock, exportStockLow, exportStockOk],
    }, contract['constants']);
  });

  test('exportLabel: null stays null, unknown code is humanized', () {
    expect(exportLabel(exportLabels['sales.status'], 'CANCELLED'), 'Annulée');
    expect(exportLabel(exportLabels['sales.paymentMode'], 'MOBILE_MONEY'),
        'Mobile Money');
    expect(exportLabel(exportLabels['reports.type'], 'DAILY_COMBINED'),
        'Fin de journée (toutes boutiques)');
    expect(exportLabel(exportLabels['sales.status'], 'X'), 'X');
    expect(exportLabel(exportLabels['stores.type'], 'KIOSK'), 'Kiosk');
    expect(exportLabel(exportLabels['audit_log.action'], 'FOO_BAR'), 'Foo bar');
    expect(exportLabel(exportLabels['sales.status'], null), isNull);
  });

  // ── v1s-16-20: the action history table is the server's ────────────────

  group('audit table (v1s-16-20)', () {
    final audit = contract['audit'] as Map<String, dynamic>;
    final labels = contract['labels'] as Map<String, dynamic>;

    test('fields, values, context, hidden and code keys match the contract',
        () {
      expect(auditFieldLabels, audit['fields']);
      expect(auditValueLabels, audit['values']);
      expect(auditContextLabels, audit['context']);
      expect(auditHiddenKeys.toList()..sort(), audit['hiddenKeys']);
      expect(auditCodeKeys.toList()..sort(), audit['codeKeys']);
    });

    test('history screen catalogue = server action labels', () {
      expect(AuditPresenter.catalogueLabels, labels['audit_log.action']);
      expect(AuditPresenter.typeNouns, labels['audit_log.entityType']);
    });

    test('every object type of the « Objet » filter has a singular noun', () {
      for (final t in AuditPresenter.objectTypes.keys) {
        expect(auditEntityTypeLabels.containsKey(t), isTrue, reason: t);
      }
    });

    test('« Changements » text: same rendering as the server export', () {
      expect(
          AuditFormat.changesText(
              '{"price":1000,"productId":"7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f"}',
              '{"price":1500}'),
          'Prix : 1 000 XAF → 1 500 XAF');
      expect(AuditFormat.changesText(null, '{"productName":"Pagne wax","productSku":"PW-1"}'),
          'Produit : Pagne wax ; Référence : PW-1');
      expect(AuditFormat.changesText('{oops', 'nope'), isNull);
      expect(AuditFormat.changesText(null, '{"from":"2026-10-01T00:00:00Z"}'),
          'Du : 01/10/2026 01:00');
      expect(
          AuditFormat.detailsText(
              '{"notes":"INVENTORY:7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f","isAutomatic":true}',
              null),
          'Motif : Ajustement d\'inventaire ; Déclenchement : Automatique');
    });
  });

  group('names and references (v1s-16-20)', () {
    test('notes and isTechnical: same rules as the server ExportValues', () {
      const id = '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f';
      expect(AuditFormat.notes('INVENTORY:$id'), 'Ajustement d\'inventaire');
      expect(AuditFormat.notes('Annulation vente $id'), 'Annulation vente');
      expect(AuditFormat.notes('Correction  vente $id  (x)'),
          'Correction vente (x)');
      expect(AuditFormat.notes(' $id '), isNull);
      expect(AuditFormat.notes('Casse'), 'Casse');
      expect(AuditFormat.isTechnical('Statut : PENDING_VALIDATION'), isTrue);
      expect(AuditFormat.isTechnical('Données : {"a":1}'), isTrue);
      expect(AuditFormat.isTechnical('Mode : CSV'), isFalse);
    });

    test('slug of the French label, never the dataset key', () {
      expect(exportSlug('Journal d\'audit'), 'journal-d-audit');
      expect(exportSlug('Équipe'), 'equipe');
      expect(exportSlug('Clôtures'), 'clotures');
      expect(exportDatasetSlug('stock_movements'), 'mouvements-de-stock');
      for (final d in localExportCatalog) {
        expect(exportDatasetSlug(d.key), matches(RegExp(r'^[a-z0-9-]+$')),
            reason: d.key);
      }
    });

    test('sale reference in WAT with seconds, store when known', () {
      final at = DateTime.utc(2026, 10, 12, 13, 32, 5);
      expect(exportSaleReference(at, 'Boutique Centre'),
          'Vente du 12/10/2026 à 14:32:05 — Boutique Centre');
      expect(exportSaleReference(at, null), 'Vente du 12/10/2026 à 14:32:05');
      expect(exportSaleReference(null, 'X'), isNull);
    });
  });
}

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

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

  test('exportLabel: null stays null, unknown code falls back to the code', () {
    expect(exportLabel(exportLabels['sales.status'], 'CANCELLED'), 'Annulée');
    expect(exportLabel(exportLabels['sales.paymentMode'], 'MOBILE_MONEY'),
        'Mobile Money');
    expect(exportLabel(exportLabels['reports.type'], 'DAILY_COMBINED'),
        'Fin de journée (toutes boutiques)');
    expect(exportLabel(exportLabels['sales.status'], 'X'), 'X');
    expect(exportLabel(exportLabels['sales.status'], null), isNull);
  });
}

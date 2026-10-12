import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';

import '../export_test_helpers.dart';

/// v1s-18-4 — request building (criteria rule, UTC instants, filters that do
/// not apply), job expiry, error mapping; v1s-18-3 — PDF template requests.
void main() {
  group('buildExportRequest', () {
    const criteria = {
      'userId': 'u-1',
      'action': 'SALE_CREATED',
      'entityType': 'Sale',
      'q': '  pagne ',
      'entityId': '   ',
      'unknown': 'x',
    };

    test('audit log alone: declared, non-blank criteria (trimmed)', () {
      final r = buildExportRequest(
        selection: {'audit_log'},
        format: ExportFormat.csv,
        criteria: criteria,
        datasets: testDatasets,
      );
      expect(r.criteria, {
        'userId': 'u-1',
        'action': 'SALE_CREATED',
        'entityType': 'Sale',
        'q': 'pagne',
      });
      expect(r.toJson()['criteria'], r.criteria);
    });

    test('another dataset added: no criteria', () {
      final r = buildExportRequest(
        selection: {'audit_log', 'products'},
        format: ExportFormat.csv,
        criteria: criteria,
        datasets: testDatasets,
      );
      expect(r.criteria, isNull);
      expect(r.toJson().containsKey('criteria'), isFalse);
      expect(r.datasets, ['products', 'audit_log'], reason: 'catalogue order');
    });

    test('from / to are sent as UTC ISO-8601 instants', () {
      final from = DateTime(2026, 10, 1);
      final to = DateTime(2026, 10, 12, 23, 59, 59, 999);
      final json = buildExportRequest(
        selection: {'sales'},
        format: ExportFormat.xlsx,
        storeId: 's-1',
        from: from,
        to: to,
        datasets: testDatasets,
      ).toJson();
      expect(json['from'], from.toUtc().toIso8601String());
      expect(json['to'], to.toUtc().toIso8601String());
      expect((json['from'] as String).endsWith('Z'), isTrue);
      expect(json['format'], 'XLSX');
      expect(json['storeId'], 's-1');
    });

    test('store / period dropped when no selected dataset honours them', () {
      final json = buildExportRequest(
        selection: {'products'},
        format: ExportFormat.csv,
        storeId: 's-1',
        from: DateTime(2026),
        to: DateTime(2026, 2),
        datasets: testDatasets,
      ).toJson();
      expect(json, {
        'datasets': ['products'],
        'format': 'CSV',
      });
    });

    test('PDF: exactly one dataset with a template (v1s-18-3)', () {
      final json = buildExportRequest(
        selection: {'sales'},
        format: ExportFormat.pdf,
        storeId: 's-1',
        from: DateTime(2026, 10, 1),
        to: DateTime(2026, 10, 12),
        datasets: pdfTestDatasets,
      ).toJson();
      expect(json['datasets'], ['sales']);
      expect(json['format'], 'PDF');
      expect(json['storeId'], 's-1');
      expect(json.containsKey('from'), isTrue);

      final stock = buildExportRequest(
        selection: {'stock_levels'},
        format: ExportFormat.pdf,
        from: DateTime(2026, 10, 1),
        datasets: pdfTestDatasets,
      ).toJson();
      expect(
          stock,
          {
            'datasets': ['stock_levels'],
            'format': 'PDF',
          },
          reason: 'period dropped: the stock is a snapshot');

      for (final selection in [
        {'products'}, // no template
        {'sales', 'stock_levels'}, // two templates
        {'unknown'}, // not in the catalogue
      ]) {
        expect(
            () => buildExportRequest(
                selection: selection,
                format: ExportFormat.pdf,
                datasets: pdfTestDatasets),
            throwsArgumentError,
            reason: '$selection');
      }
    });

    test('empty selection rejected', () {
      expect(
          () => buildExportRequest(
              selection: {}, format: ExportFormat.csv, datasets: testDatasets),
          throwsArgumentError);
    });

    test('ExportDataset.fromJson reads the nullable pdfTemplate', () {
      final withTemplate = ExportDataset.fromJson({
        'key': 'day_closures',
        'label': 'Clôtures',
        'pdfTemplate': 'Fin de journée',
      });
      expect(withTemplate.pdfTemplate, 'Fin de journée');
      expect(withTemplate.hasPdfTemplate, isTrue);
      final without =
          ExportDataset.fromJson({'key': 'products', 'pdfTemplate': null});
      expect(without.pdfTemplate, isNull);
      expect(without.hasPdfTemplate, isFalse);
      expect(ExportDataset.fromJson({'key': 'stores'}).hasPdfTemplate, isFalse);
    });
  });

  test('a READY job past its expiry is expired', () {
    final now = DateTime(2026, 10, 12, 12);
    ExportJob job(ExportJobStatus s, DateTime? exp) =>
        ExportJob(id: 'j', status: s, format: 'CSV', expiresAt: exp);
    expect(
        job(ExportJobStatus.ready, now.subtract(const Duration(minutes: 1)))
            .effectiveStatus(now),
        ExportJobStatus.expired);
    expect(
        job(ExportJobStatus.ready, now.add(const Duration(hours: 1)))
            .isExpired(now),
        isFalse);
    expect(job(ExportJobStatus.expired, null).isExpired(now), isTrue);
    expect(job(ExportJobStatus.running, null).isActive, isTrue);
  });

  test('error mapping', () {
    expect(
        ExportException.fromResponse(null, null).domainCode, 'NETWORK_ERROR');
    expect(ExportException.fromResponse(409, null).message,
        'Cet export n\'est pas encore prêt');
    expect(
        ExportException.fromResponse(404, null).domainCode, 'EXPORT_NOT_FOUND');
    expect(
        ExportException.fromResponse(
                400, {'domainCode': 'VALIDATION_ERROR', 'error': 'bad from'})
            .message,
        'bad from');
    expect(
        ExportException.fromResponse(400, {'domainCode': 'VALIDATION_ERROR'})
            .message,
        'Demande d\'export invalide');
    // v1s-18-3 — PDF over its row limit: the server message, else a fallback.
    final tooLarge = ExportException.fromResponse(422, {
      'domainCode': 'EXPORT_PDF_TOO_LARGE',
      'error': 'Ce PDF dépasserait la limite de 2000 lignes : réduisez la '
          'période, choisissez une boutique ou exportez en Excel',
    });
    expect(tooLarge.domainCode, 'EXPORT_PDF_TOO_LARGE');
    expect(tooLarge.message, contains('2000 lignes'));
    expect(
        ExportException.fromResponse(
            422, {'domainCode': 'EXPORT_PDF_TOO_LARGE'}).message,
        'Ce PDF est trop volumineux : réduisez la période ou exportez en Excel');
  });
}

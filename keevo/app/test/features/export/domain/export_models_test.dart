import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';

import '../export_test_helpers.dart';

/// v1s-18-4 — request building (criteria rule, UTC instants, filters that do
/// not apply, PDF never built), job expiry, error mapping.
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

    test('PDF is never built; empty selection rejected', () {
      expect(
          () => buildExportRequest(
              selection: {'sales'},
              format: ExportFormat.pdf,
              datasets: testDatasets),
          throwsArgumentError);
      expect(
          () => buildExportRequest(
              selection: {}, format: ExportFormat.csv, datasets: testDatasets),
          throwsArgumentError);
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
  });
}

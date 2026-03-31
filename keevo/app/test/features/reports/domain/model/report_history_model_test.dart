import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/reports/domain/model/report_history_model.dart';

/// Unit tests for ReportHistoryModel computed getters and JSON deserialization.
/// Story 7.2 — Task 12 TDD.
void main() {
  ReportHistoryModel _report({String deliveryStatus = 'PENDING'}) =>
      ReportHistoryModel(
        id: 'rpt-1',
        tenantId: 'tenant-abc',
        storeId: 'store-1',
        storeName: 'Boutique Centrale',
        reportType: 'DAILY',
        reportDate: DateTime(2025, 6, 20),
        content: '📊 Rapport du 20 juin 2025\nTotal: 15 000 FCFA',
        deliveryStatus: deliveryStatus,
        deliveryAttempts: 1,
        lastAttemptAt: null,
        totalRevenue: 1500000,
        totalSales: 12,
        isAutomatic: true,
        createdAt: DateTime(2025, 6, 20, 22, 0),
      );

  group('ReportHistoryModel.isDelivered', () {
    test('is true when deliveryStatus is SENT', () {
      expect(_report(deliveryStatus: 'SENT').isDelivered, isTrue);
    });

    test('is false when deliveryStatus is PENDING', () {
      expect(_report(deliveryStatus: 'PENDING').isDelivered, isFalse);
    });

    test('is false when deliveryStatus is FAILED', () {
      expect(_report(deliveryStatus: 'FAILED').isDelivered, isFalse);
    });

    test('is false when deliveryStatus is IN_APP_ONLY', () {
      expect(_report(deliveryStatus: 'IN_APP_ONLY').isDelivered, isFalse);
    });
  });

  group('ReportHistoryModel.isFailed', () {
    test('is true when deliveryStatus is FAILED', () {
      expect(_report(deliveryStatus: 'FAILED').isFailed, isTrue);
    });

    test('is true when deliveryStatus is IN_APP_ONLY', () {
      expect(_report(deliveryStatus: 'IN_APP_ONLY').isFailed, isTrue);
    });

    test('is false when deliveryStatus is SENT', () {
      expect(_report(deliveryStatus: 'SENT').isFailed, isFalse);
    });

    test('is false when deliveryStatus is PENDING', () {
      expect(_report(deliveryStatus: 'PENDING').isFailed, isFalse);
    });
  });

  group('ReportHistoryModel.fromJson', () {
    test('parses required fields correctly', () {
      final json = {
        'id': 'rpt-2',
        'tenantId': 'tenant-xyz',
        'storeId': 'store-2',
        'storeName': 'Agence Nord',
        'reportType': 'DAILY_COMBINED',
        'reportDate': '2025-06-21T00:00:00.000Z',
        'content': 'Multi-boutique report',
        'deliveryStatus': 'SENT',
        'deliveryAttempts': 3,
        'lastAttemptAt': '2025-06-21T22:30:00.000Z',
        'totalRevenue': 4500000,
        'totalSales': 35,
        'isAutomatic': true,
        'createdAt': '2025-06-21T22:00:00.000Z',
      };

      final model = ReportHistoryModel.fromJson(json);

      expect(model.id, 'rpt-2');
      expect(model.tenantId, 'tenant-xyz');
      expect(model.storeId, 'store-2');
      expect(model.storeName, 'Agence Nord');
      expect(model.reportType, 'DAILY_COMBINED');
      expect(model.deliveryStatus, 'SENT');
      expect(model.deliveryAttempts, 3);
      expect(model.totalRevenue, 4500000);
      expect(model.totalSales, 35);
      expect(model.isAutomatic, isTrue);
      expect(model.isDelivered, isTrue);
    });

    test('parses null storeName correctly', () {
      final json = {
        'id': 'rpt-3',
        'tenantId': 'tenant-xyz',
        'storeId': 'store-3',
        'storeName': null,
        'reportType': 'DAILY',
        'reportDate': '2025-06-21T00:00:00.000Z',
        'content': 'Report',
        'deliveryStatus': 'PENDING',
        'deliveryAttempts': 0,
        'lastAttemptAt': null,
        'totalRevenue': 0,
        'totalSales': 0,
        'isAutomatic': false,
        'createdAt': '2025-06-21T22:00:00.000Z',
      };

      final model = ReportHistoryModel.fromJson(json);
      expect(model.storeName, isNull);
      expect(model.lastAttemptAt, isNull);
    });

    test('copyWith preserves unmodified fields', () {
      final original = _report();
      final updated = original.copyWith(deliveryStatus: 'SENT', deliveryAttempts: 2);

      expect(updated.id, original.id);
      expect(updated.deliveryStatus, 'SENT');
      expect(updated.deliveryAttempts, 2);
      expect(updated.totalRevenue, original.totalRevenue);
    });
  });
}

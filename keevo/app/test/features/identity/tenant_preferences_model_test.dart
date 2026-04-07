import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/identity/domain/model/tenant_preferences_model.dart';
import 'package:keevo/features/onboarding/domain/model/sector_type.dart';

void main() {
  final baseTime = DateTime(2025, 1, 1);

  group('TenantPreferencesModel.fromJson()', () {
    test('maps all 13 fields from JSON', () {
      final json = {
        'sectorType': 'OTHER',
        'eodReportTime': '20:00:00',
        'stockAlertEnabled': true,
        'createdAt': '2025-01-01T00:00:00.000Z',
        'eodReportEnabled': true,
        'eodReportChannel': 'WHATSAPP',
        'weeklyReportEnabled': true,
        'weeklyReportDay': 5,
        'weeklyReportTime': '08:00:00',
        'weeklyReportChannel': 'IN_APP_ONLY',
        'inventoryReportEnabled': false,
        'inventoryReportChannel': 'WHATSAPP',
        'stockAlertChannel': 'BOTH',
      };

      final model = TenantPreferencesModel.fromJson(json);

      expect(model.sectorType, SectorType.other);
      expect(model.eodReportTime, '20:00:00');
      expect(model.stockAlertEnabled, isTrue);
      expect(model.eodReportEnabled, isTrue);
      expect(model.eodReportChannel, 'WHATSAPP');
      expect(model.weeklyReportEnabled, isTrue);
      expect(model.weeklyReportDay, 5);
      expect(model.weeklyReportTime, '08:00:00');
      expect(model.weeklyReportChannel, 'IN_APP_ONLY');
      expect(model.inventoryReportEnabled, isFalse);
      expect(model.inventoryReportChannel, 'WHATSAPP');
      expect(model.stockAlertChannel, 'BOTH');
    });

    test('uses defaults when report fields are absent', () {
      final json = {
        'sectorType': null,
        'eodReportTime': '20:00:00',
        'stockAlertEnabled': true,
        'createdAt': '2025-01-01T00:00:00.000Z',
      };

      final model = TenantPreferencesModel.fromJson(json);

      expect(model.eodReportEnabled, isFalse);
      expect(model.eodReportChannel, 'WHATSAPP');
      expect(model.weeklyReportEnabled, isFalse);
      expect(model.weeklyReportDay, 5);
      expect(model.weeklyReportTime, '08:00:00');
      expect(model.weeklyReportChannel, 'WHATSAPP');
      expect(model.inventoryReportEnabled, isFalse);
      expect(model.inventoryReportChannel, 'WHATSAPP');
      expect(model.stockAlertChannel, 'PUSH');
    });
  });

  group('TenantPreferencesModel.toJson()', () {
    test('serialises all report fields', () {
      final model = TenantPreferencesModel(
        sectorType: SectorType.other,
        eodReportTime: '20:00:00',
        stockAlertEnabled: true,
        createdAt: baseTime,
        eodReportEnabled: true,
        eodReportChannel: 'WHATSAPP',
        weeklyReportEnabled: false,
        weeklyReportDay: 0,
        weeklyReportTime: '09:00:00',
        weeklyReportChannel: 'IN_APP_ONLY',
        inventoryReportEnabled: true,
        inventoryReportChannel: 'WHATSAPP',
        stockAlertChannel: 'BOTH',
      );

      final json = model.toJson();

      expect(json['eodReportEnabled'], isTrue);
      expect(json['eodReportChannel'], 'WHATSAPP');
      expect(json['weeklyReportEnabled'], isFalse);
      expect(json['weeklyReportDay'], 0);
      expect(json['weeklyReportTime'], '09:00:00');
      expect(json['weeklyReportChannel'], 'IN_APP_ONLY');
      expect(json['inventoryReportEnabled'], isTrue);
      expect(json['inventoryReportChannel'], 'WHATSAPP');
      expect(json['stockAlertChannel'], 'BOTH');
    });
  });

  group('TenantPreferencesModel.copyWith()', () {
    test('copies and overrides individual fields', () {
      final original = TenantPreferencesModel(
        sectorType: null,
        eodReportTime: '20:00:00',
        stockAlertEnabled: false,
        createdAt: baseTime,
      );

      final updated = original.copyWith(
        eodReportEnabled: true,
        eodReportChannel: 'IN_APP_ONLY',
        weeklyReportDay: 3,
        stockAlertChannel: 'WHATSAPP',
      );

      expect(updated.eodReportEnabled, isTrue);
      expect(updated.eodReportChannel, 'IN_APP_ONLY');
      expect(updated.weeklyReportDay, 3);
      expect(updated.stockAlertChannel, 'WHATSAPP');
      // Unchanged fields preserved
      expect(updated.eodReportTime, '20:00:00');
      expect(updated.stockAlertEnabled, isFalse);
      expect(updated.weeklyReportEnabled, isTrue);
    });
  });
}

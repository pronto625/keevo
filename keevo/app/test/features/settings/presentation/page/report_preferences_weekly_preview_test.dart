import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/identity/domain/model/tenant_preferences_model.dart';
import 'package:keevo/features/identity/domain/repository/tenant_preferences_repository.dart';
import 'package:keevo/features/identity/presentation/provider/tenant_preferences_provider.dart';
import 'package:keevo/features/settings/presentation/page/report_preferences_page.dart';
import 'package:keevo_core/state/provider_retry.dart';

import '../../../../helpers/fake_entitlements.dart';

class _FakeRepo implements TenantPreferencesRepository {
  @override
  Future<TenantPreferencesModel?> getCurrentTenantPreferences() async =>
      TenantPreferencesModel.fromJson({
        'sectorType': 'GENERAL',
        'eodReportTime': '20:00',
        'stockAlertEnabled': true,
        'createdAt': '2026-10-14T10:00:00Z',
        'eodReportEnabled': true,
        'eodReportChannel': 'WHATSAPP',
        'weeklyReportEnabled': true,
        'weeklyReportDay': 1,
        'weeklyReportTime': '20:00',
        'weeklyReportChannel': 'WHATSAPP',
        'inventoryReportEnabled': true,
        'inventoryReportChannel': 'WHATSAPP',
        'stockAlertChannel': 'PUSH',
        'trendNotificationEnabled': true,
      });

  @override
  Future<bool> sendTestReport() async => true;

  @override
  Future<String?> triggerWeeklyPreview() async => null;

  @override
  Future<TenantPreferencesModel> updateReportPreferences(
          Map<String, dynamic> payload) =>
      throw UnimplementedError();
}

/// Story 15.8 — the weekly report (settings + « Aperçu », a write) and the
/// trend alerts are `advanced-reports`: shown only when the module is active
/// (hidden when DISABLED or READ_ONLY).
void main() {
  Future<void> pump(WidgetTester tester, Map<String, String> states) async {
    tester.view.physicalSize = const Size(400 * 3, 3000 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        tenantPreferencesRepositoryProvider.overrideWithValue(_FakeRepo()),
        entitlementsOverride(states),
      ],
      child: const MaterialApp(home: ReportPreferencesPage()),
    ));
    await tester.pumpAndSettle();
  }

  const preview = 'Aperçu du rapport hebdomadaire';
  const weekly = [
    'Rapport Hebdomadaire',
    'Activer le rapport hebdo',
    'Heure d\'envoi',
    preview,
    'Tendances de Ventes',
    'Activer les alertes de tendance',
  ];

  for (final state in ['DISABLED', 'READ_ONLY']) {
    testWidgets('advanced-reports $state: weekly report and trend alerts '
        'hidden', (tester) async {
      await pump(
          tester, {'report-config': 'ENABLED', 'advanced-reports': state});
      expect(find.text('Rapport Inventaire'), findsOneWidget);
      for (final label in weekly) {
        expect(find.text(label), findsNothing, reason: label);
      }
    });
  }

  testWidgets('advanced-reports ENABLED: weekly report, preview and trend '
      'alerts shown', (tester) async {
    await pump(tester,
        {'report-config': 'ENABLED', 'advanced-reports': 'ENABLED'});
    for (final label in weekly) {
      expect(find.text(label), findsWidgets, reason: label);
    }
  });

  // Story 15.8 — the stock alert channel is the paid `alerts` module's.
  for (final state in ['DISABLED', 'READ_ONLY']) {
    testWidgets('alerts $state: stock alert channel hidden', (tester) async {
      await pump(tester, {'report-config': 'ENABLED', 'alerts': state});
      expect(find.text('Rapport Inventaire'), findsOneWidget);
      expect(find.text('Alertes Stock Bas'), findsNothing);
    });
  }

  testWidgets('alerts ENABLED: stock alert channel shown', (tester) async {
    await pump(tester, {'report-config': 'ENABLED', 'alerts': 'ENABLED'});
    expect(find.text('Alertes Stock Bas'), findsOneWidget);
  });
}

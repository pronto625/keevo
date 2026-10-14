import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/identity/domain/model/tenant_preferences_model.dart';
import 'package:keevo/features/settings/presentation/page/report_preferences_page.dart';

void main() {
  // Server semantics (TenantPreferences / WeeklyReportScheduler):
  // weeklyReportDay 0 = dimanche, 1 = lundi … 6 = samedi.
  String labelFor(int serverDay) =>
      kWeeklyReportDays.firstWhere((d) => d.$1 == serverDay).$2;

  test('weekly report day labels follow the server numbering', () {
    expect(labelFor(0), 'Dimanche');
    expect(labelFor(1), 'Lundi');
    expect(labelFor(5), 'Vendredi');
    expect(labelFor(6), 'Samedi');
    expect(kWeeklyReportDays.map((d) => d.$1).toSet(), {0, 1, 2, 3, 4, 5, 6});
  });

  test('server default weeklyReportDay=0 is shown as Dimanche', () {
    final prefs = TenantPreferencesModel.fromJson({
      'sectorType': 'GENERAL',
      'eodReportTime': '20:00',
      'stockAlertEnabled': true,
      'createdAt': '2026-10-14T10:00:00Z',
      'eodReportEnabled': true,
      'eodReportChannel': 'WHATSAPP',
      'weeklyReportEnabled': true,
      'weeklyReportDay': 0,
      'weeklyReportTime': '20:00',
      'weeklyReportChannel': 'WHATSAPP',
      'inventoryReportEnabled': true,
      'inventoryReportChannel': 'WHATSAPP',
      'stockAlertChannel': 'PUSH',
      'trendNotificationEnabled': true,
    });
    expect(labelFor(prefs.weeklyReportDay), 'Dimanche');
  });
}

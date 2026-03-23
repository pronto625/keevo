import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  group('daysSinceLastSyncProvider', () {
    test('null_key_returns_0', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 0);
    });

    test('set_to_today_returns_0', () async {
      final nowMs = DateTime.now().millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({kLastSyncAtKey: nowMs});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 0);
    });

    test('set_to_yesterday_returns_1', () async {
      final yesterdayMs = DateTime.now()
          .subtract(const Duration(hours: 25))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({kLastSyncAtKey: yesterdayMs});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 1);
    });

    test('set_to_5_days_ago_returns_5', () async {
      final fiveDaysMs = DateTime.now()
          .subtract(const Duration(days: 5, hours: 1))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({kLastSyncAtKey: fiveDaysMs});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 5);
    });

    test('set_to_7_days_ago_returns_7', () async {
      final sevenDaysMs = DateTime.now()
          .subtract(const Duration(days: 7, hours: 1))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({kLastSyncAtKey: sevenDaysMs});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 7);
    });
  });
}

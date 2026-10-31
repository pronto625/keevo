import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
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

    // v1s-16-13
    test('last_sync_in_the_future_returns_0', () async {
      final future = DateTime.now()
          .add(const Duration(days: 2))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({kLastSyncAtKey: future});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      );
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 0);
    });

    // v1s-15-8: injected clock + the testWidgets fake timer — no real wait
    // (it used to race a real 150 ms Timer against a real 400 ms delay).
    testWidgets('re_evaluates_at_the_next_day_boundary_without_a_sync',
        (tester) async {
      // Last sync 1 day minus 100 ms before "now": the provider must move to
      // 1 on its own once the 24 h boundary is crossed.
      final lastSync = DateTime(2026, 1, 1, 12);
      var now = lastSync
          .add(const Duration(days: 1) - const Duration(milliseconds: 100));
      SharedPreferences.setMockInitialValues(
          {kLastSyncAtKey: lastSync.millisecondsSinceEpoch});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncGateClockProvider.overrideWithValue(() => now),
      ]);
      addTearDown(container.dispose);

      final seen = <int>[];
      container.listen<int>(daysSinceLastSyncProvider, (_, next) => seen.add(next),
          fireImmediately: true);
      expect(seen, [0]);

      now = now.add(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 200));
      expect(seen.last, 1);
      // Cancels the next-boundary Timer before the fake-timer invariant check.
      container.dispose();
    });

    test('uses_the_injected_clock', () async {
      final lastSync = DateTime(2026, 1, 1, 12);
      SharedPreferences.setMockInitialValues(
          {kLastSyncAtKey: lastSync.millisecondsSinceEpoch});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncGateClockProvider
            .overrideWithValue(() => DateTime(2026, 1, 6, 13)),
      ]);
      addTearDown(container.dispose);

      expect(container.read(daysSinceLastSyncProvider), 5);
    });
  });
}

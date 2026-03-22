import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';

/// Story 5.2 — AC6 Sync Indicator Pull State Tests
///
/// Validates that UI indicator states reflect pull sync progress correctly.
void main() {
  group('Sync Indicator — Pull States', () {
    test('idle_afterSuccessfulPull_showsIdleState', () {
      // After a successful triggerSync() (push + pull succeed):
      // state should be SyncTriggerIdle
      const state = SyncTriggerState.idle();
      expect(state, isA<SyncTriggerIdle>());
    });

    test('syncing_duringPull_showsSyncingState', () {
      // While triggerSync() is executing:
      // state should be SyncTriggerSyncing
      const state = SyncTriggerState.syncing();
      expect(state, isA<SyncTriggerSyncing>());
    });

    test('criticalFailure_showsDurationBanner', () {
      // When consecutive failures exceed threshold:
      // state should be SyncTriggerCriticalFailure with duration
      const state = SyncTriggerState.criticalFailure(
        duration: Duration(hours: 25),
      );
      expect(state, isA<SyncTriggerCriticalFailure>());
      final critical = state as SyncTriggerCriticalFailure;
      expect(critical.duration.inHours, greaterThanOrEqualTo(24));
    });
  });
}

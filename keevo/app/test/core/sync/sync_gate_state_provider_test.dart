import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_gate_provider.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';

void main() {
  ProviderContainer _makeContainer(int days) {
    return ProviderContainer(
      overrides: [
        daysSinceLastSyncProvider.overrideWithValue(days),
      ],
    );
  }

  group('syncGateStateProvider', () {
    test('0_days_since_sync_returns_open', () {
      final container = _makeContainer(0);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.open);
    });

    test('4_days_since_sync_returns_open', () {
      final container = _makeContainer(4);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.open);
    });

    test('5_days_since_sync_returns_warning', () {
      final container = _makeContainer(5);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.warning);
    });

    test('6_days_since_sync_returns_critical', () {
      final container = _makeContainer(6);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.critical);
    });

    test('7_days_since_sync_returns_blocked', () {
      final container = _makeContainer(7);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.blocked);
    });

    test('10_days_since_sync_returns_blocked', () {
      final container = _makeContainer(10);
      addTearDown(container.dispose);
      expect(container.read(syncGateStateProvider), SyncGateState.blocked);
    });
  });
}

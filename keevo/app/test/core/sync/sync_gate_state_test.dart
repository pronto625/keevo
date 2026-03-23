import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_gate_state.dart';

void main() {
  group('SyncGateState.fromDaysSinceLastSync', () {
    test('0_days_returns_open', () {
      expect(SyncGateState.fromDaysSinceLastSync(0), SyncGateState.open);
    });

    test('4_days_returns_open', () {
      expect(SyncGateState.fromDaysSinceLastSync(4), SyncGateState.open);
    });

    test('5_days_returns_warning', () {
      expect(SyncGateState.fromDaysSinceLastSync(5), SyncGateState.warning);
    });

    test('6_days_returns_critical', () {
      expect(SyncGateState.fromDaysSinceLastSync(6), SyncGateState.critical);
    });

    test('7_days_returns_blocked', () {
      expect(SyncGateState.fromDaysSinceLastSync(7), SyncGateState.blocked);
    });

    test('100_days_returns_blocked', () {
      expect(SyncGateState.fromDaysSinceLastSync(100), SyncGateState.blocked);
    });
  });

  group('SyncGateState.isWriteBlocked', () {
    test('open_is_not_write_blocked', () {
      expect(SyncGateState.open.isWriteBlocked, isFalse);
    });

    test('warning_is_not_write_blocked', () {
      expect(SyncGateState.warning.isWriteBlocked, isFalse);
    });

    test('critical_is_not_write_blocked', () {
      expect(SyncGateState.critical.isWriteBlocked, isFalse);
    });

    test('blocked_is_write_blocked', () {
      expect(SyncGateState.blocked.isWriteBlocked, isTrue);
    });
  });

  group('SyncGateState.showsBanner', () {
    test('open_does_not_show_banner', () {
      expect(SyncGateState.open.showsBanner, isFalse);
    });

    test('warning_shows_banner', () {
      expect(SyncGateState.warning.showsBanner, isTrue);
    });

    test('critical_shows_banner', () {
      expect(SyncGateState.critical.showsBanner, isTrue);
    });

    test('blocked_does_not_show_banner', () {
      expect(SyncGateState.blocked.showsBanner, isFalse);
    });
  });
}

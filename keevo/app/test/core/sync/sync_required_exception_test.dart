import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_required_exception.dart';

void main() {
  group('SyncRequiredException', () {
    test('toString_containsDaysSinceLastSync', () {
      const ex = SyncRequiredException(daysSinceLastSync: 8);
      expect(ex.toString(), contains('8'),
          reason: 'toString must include daysSinceLastSync value');
    });

    test('isException_runtimeType_isException', () {
      const ex = SyncRequiredException(daysSinceLastSync: 7);
      expect(ex, isA<Exception>());
    });

    test('fieldsPreserved_daysSinceLastSync_andLastPushAt', () {
      const ex = SyncRequiredException(
        daysSinceLastSync: 9,
        lastPushAt: '2026-03-15T10:00:00Z',
      );
      expect(ex.daysSinceLastSync, 9);
      expect(ex.lastPushAt, '2026-03-15T10:00:00Z');
    });

    test('lastPushAt_optional_defaults_to_null', () {
      const ex = SyncRequiredException(daysSinceLastSync: 7);
      expect(ex.lastPushAt, isNull);
    });
  });
}

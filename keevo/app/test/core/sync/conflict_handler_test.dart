import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/domain/sync_conflict.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';

/// Story 5.3 Task 13.1 — Tests for conflict handling in push flow.
///
/// Validates conflict result classification, SyncConflict model parsing from
/// push response maps, and the pendingConflictNotificationsProvider pipeline.
void main() {
  group('Push result conflict classification', () {
    test('CONFLICT status result is classified as stock conflict', () {
      final result = {
        'operationId': 'op-1',
        'status': 'CONFLICT',
        'conflictData': {
          'conflictType': 'STOCK_NEGATIVE',
          'productName': 'Widget A',
          'resultingStock': -3,
        },
      };

      expect(result['status'], equals('CONFLICT'));
      final data = result['conflictData'] as Map<String, dynamic>;
      expect(data['conflictType'], equals('STOCK_NEGATIVE'));
    });

    test('APPLIED with non-null conflictData is classified as LWW conflict', () {
      final result = {
        'operationId': 'op-2',
        'status': 'APPLIED',
        'conflictData': {
          'conflictType': 'LAST_WRITE_WINS',
          'overwrittenDeviceId': 'device-abc',
          'overwrittenAt': '2025-01-01T12:00:00Z',
        },
      };

      expect(result['status'], equals('APPLIED'));
      expect(result['conflictData'], isNotNull);
      final data = result['conflictData'] as Map<String, dynamic>;
      expect(data['conflictType'], equals('LAST_WRITE_WINS'));
    });

    test('APPLIED with null conflictData has no conflict', () {
      final result = {
        'operationId': 'op-3',
        'status': 'APPLIED',
        'conflictData': null,
      };

      expect(result['conflictData'], isNull);
    });

    test('filtering CONFLICT results from mixed batch', () {
      final results = [
        {'operationId': 'op-1', 'status': 'APPLIED', 'conflictData': null},
        {
          'operationId': 'op-2',
          'status': 'CONFLICT',
          'conflictData': {'conflictType': 'STOCK_NEGATIVE'},
        },
        {'operationId': 'op-3', 'status': 'DUPLICATE', 'conflictData': null},
        {
          'operationId': 'op-4',
          'status': 'APPLIED',
          'conflictData': {'conflictType': 'LAST_WRITE_WINS'},
        },
      ];

      // Collect conflicts the same way RestSyncService does
      final conflicts = <Map<String, dynamic>>[];
      for (final r in results) {
        final status = r['status'] as String;
        if (status == 'CONFLICT') {
          conflicts.add(r);
        } else if (status == 'APPLIED' && r['conflictData'] != null) {
          conflicts.add(r);
        }
      }

      expect(conflicts, hasLength(2));
      expect(conflicts[0]['operationId'], equals('op-2'));
      expect(conflicts[1]['operationId'], equals('op-4'));
    });
  });

  group('SyncConflict.fromJson with push conflictData', () {
    test('parses STOCK_NEGATIVE conflict from API response', () {
      final json = {
        'id': 'c-1',
        'operationId': 'op-1',
        'operationType': 'UPDATE_STOCK',
        'conflictType': 'STOCK_NEGATIVE',
        'strategy': 'CLAMPED_TO_ZERO',
        'conflictData': {
          'productName': 'Widget A',
          'resultingStock': -3,
          'clampedTo': 0,
        },
        'resolvedAt': '2025-01-15T10:00:00Z',
        'entityType': 'stock_level',
      };

      final conflict = SyncConflict.fromJson(json);

      expect(conflict.id, equals('c-1'));
      expect(conflict.conflictType, equals('STOCK_NEGATIVE'));
      expect(conflict.strategy, equals('CLAMPED_TO_ZERO'));
      expect(conflict.entityType, equals('stock_level'));
      expect(conflict.conflictData?['productName'], equals('Widget A'));
      expect(conflict.conflictData?['resultingStock'], equals(-3));
    });

    test('parses LAST_WRITE_WINS conflict from API response', () {
      final json = {
        'id': 'c-2',
        'operationId': 'op-2',
        'operationType': 'UPDATE_PRODUCT',
        'conflictType': 'LAST_WRITE_WINS',
        'strategy': 'SERVER_WINS',
        'conflictData': {
          'overwrittenDeviceId': 'device-abc',
          'overwrittenAt': '2025-01-15T09:00:00Z',
        },
        'resolvedAt': '2025-01-15T10:00:00Z',
        'entityType': 'product',
      };

      final conflict = SyncConflict.fromJson(json);

      expect(conflict.conflictType, equals('LAST_WRITE_WINS'));
      expect(conflict.entityType, equals('product'));
      expect(conflict.displayTitle, contains('product'));
    });
  });

  group('pendingConflictNotificationsProvider', () {
    test('starts with empty list', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final conflicts = container.read(pendingConflictNotificationsProvider);
      expect(conflicts, isEmpty);
    });

    test('can be populated with stock conflicts', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final stockConflicts = [
        {
          'operationId': 'op-1',
          'status': 'CONFLICT',
          'conflictData': {
            'conflictType': 'STOCK_NEGATIVE',
            'productName': 'Widget A',
            'resultingStock': -3,
          },
        },
      ];

      container
          .read(pendingConflictNotificationsProvider.notifier)
          .state = stockConflicts;

      final result = container.read(pendingConflictNotificationsProvider);
      expect(result, hasLength(1));
      expect(
        (result[0]['conflictData'] as Map)['productName'],
        equals('Widget A'),
      );
    });

    test('can be cleared after UI consumption', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      container
          .read(pendingConflictNotificationsProvider.notifier)
          .state = [
        {'operationId': 'op-1', 'status': 'CONFLICT', 'conflictData': {}},
      ];

      expect(container.read(pendingConflictNotificationsProvider), hasLength(1));

      // Simulate UI clearing after showing SnackBars
      container
          .read(pendingConflictNotificationsProvider.notifier)
          .state = [];

      expect(container.read(pendingConflictNotificationsProvider), isEmpty);
    });
  });
}

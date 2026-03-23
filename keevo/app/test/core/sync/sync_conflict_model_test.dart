import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/domain/sync_conflict.dart';

/// Story 5.3 Task 13.2 — TDD RED tests for SyncConflict domain model.
void main() {
  group('SyncConflict.fromJson()', () {
    test('fromJson_validStockNegative_parsesCorrectly', () {
      final json = {
        'id': 'c-1',
        'operationId': 'op-1',
        'operationType': 'CREATE_SALE',
        'entityId': 'p1',
        'conflictType': 'STOCK_NEGATIVE',
        'strategy': 'DELTA_SUM',
        'conflictData': {
          'productName': 'Widget A',
          'resultingStock': -5,
          'serverQty': 3,
          'requestedDelta': -8,
        },
        'resolvedAt': '2026-03-20T10:00:00Z',
      };

      final conflict = SyncConflict.fromJson(json);

      expect(conflict.id, 'c-1');
      expect(conflict.conflictType, 'STOCK_NEGATIVE');
      expect(conflict.strategy, 'DELTA_SUM');
      expect(conflict.conflictData?['productName'], 'Widget A');
      expect(conflict.conflictData?['resultingStock'], -5);
    });

    test('fromJson_validLww_parsesCorrectly', () {
      final json = {
        'id': 'c-2',
        'operationId': 'op-2',
        'operationType': 'UPDATE_PRODUCT',
        'entityId': 'p2',
        'conflictType': 'LAST_WRITE_WINS',
        'strategy': 'LWW',
        'conflictData': {
          'previousOperationId': 'op-old',
        },
        'resolvedAt': '2026-03-20T11:00:00Z',
      };

      final conflict = SyncConflict.fromJson(json);

      expect(conflict.conflictType, 'LAST_WRITE_WINS');
      expect(conflict.strategy, 'LWW');
    });

    test('fromJson_nullConflictData_handlesGracefully', () {
      final json = {
        'id': 'c-3',
        'operationId': 'op-3',
        'operationType': 'CREATE_SALE',
        'entityId': null,
        'conflictType': 'STOCK_NEGATIVE',
        'strategy': 'DELTA_SUM',
        'conflictData': null,
        'resolvedAt': '2026-03-20T12:00:00Z',
      };

      final conflict = SyncConflict.fromJson(json);

      expect(conflict.entityId, isNull);
      expect(conflict.conflictData, isNull);
    });
  });

  group('SyncConflict.displayTitle', () {
    test('stockNegative_showsProductNameAndQuantity', () {
      final conflict = SyncConflict(
        id: 'c-1',
        operationId: 'op-1',
        operationType: 'CREATE_SALE',
        conflictType: 'STOCK_NEGATIVE',
        strategy: 'DELTA_SUM',
        conflictData: {'productName': 'Widget A', 'resultingStock': -3},
        resolvedAt: DateTime.now(),
      );

      expect(conflict.displayTitle, contains('Widget A'));
      expect(conflict.displayTitle, contains('-3'));
    });

    test('lastWriteWins_showsOverwriteMessage', () {
      final conflict = SyncConflict(
        id: 'c-2',
        operationId: 'op-2',
        operationType: 'UPDATE_PRODUCT',
        conflictType: 'LAST_WRITE_WINS',
        strategy: 'LWW',
        resolvedAt: DateTime.now(),
      );

      expect(conflict.displayTitle, contains('écrasée'));
    });
  });
}

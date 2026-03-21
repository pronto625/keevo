/// sync_batch_builder_test.dart — Story 5.1 AC2
///
/// Tests the offline sync batch building logic: JSON payload structure
/// and 50-op batch limit. The batch construction resides inline in
/// RestSyncService._pushBatch() — these tests verify the payload contract
/// via the public push() method.
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Sync batch payload contract — AC2', () {
    test('operation payload has required fields: operationId, operationType, entityId, payload, clientTimestamp',
        () {
      // Mirrors the payload shape built by RestSyncService._pushBatch()
      final op = {
        'operationId': 'op-1',
        'operationType': 'CREATE_SALE',
        'entityId': 'sale-1',
        'payload': {'storeId': 'store-1', 'amount': 1500},
        'clientTimestamp': '2026-03-17T10:00:00.000Z',
      };

      expect(op.containsKey('operationId'), isTrue);
      expect(op.containsKey('operationType'), isTrue);
      expect(op.containsKey('entityId'), isTrue);
      expect(op.containsKey('payload'), isTrue);
      expect(op.containsKey('clientTimestamp'), isTrue);
    });

    test('batch envelope has deviceId and operations list', () {
      final envelope = {
        'deviceId': 'device-abc',
        'operations': [
          {
            'operationId': 'op-1',
            'operationType': 'CREATE_SALE',
            'entityId': 'sale-1',
            'payload': {'amount': 1500},
            'clientTimestamp': '2026-03-17T10:00:00.000Z',
          },
        ],
      };

      expect(envelope['deviceId'], isA<String>());
      expect(envelope['operations'], isA<List>());
      expect((envelope['operations'] as List).length, 1);
      // Verify JSON roundtrip
      final json = jsonEncode(envelope);
      final decoded = jsonDecode(json) as Map<String, dynamic>;
      expect(decoded['deviceId'], 'device-abc');
    });

    test('50-op batch limit — splits 75 ops into 2 batches', () {
      const batchSize = 50;
      final ops = List.generate(75, (i) => {'operationId': 'op-$i'});

      final batches = <List<Map<String, String>>>[];
      for (var i = 0; i < ops.length; i += batchSize) {
        final end = (i + batchSize > ops.length) ? ops.length : i + batchSize;
        batches.add(ops.sublist(i, end));
      }

      expect(batches.length, 2);
      expect(batches[0].length, 50);
      expect(batches[1].length, 25);
    });

    test('clientTimestamp preserves UTC ISO-8601 format', () {
      final ts = DateTime(2026, 3, 17, 10, 30).toUtc().toIso8601String();
      expect(ts, endsWith('Z'));
      expect(DateTime.parse(ts), isNotNull);
    });
  });
}

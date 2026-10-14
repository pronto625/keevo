import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_monitoring_providers.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

/// Story 5.5 — Task 7.3: Active devices provider tests.
void main() {
  group('activeDevicesProvider', () {
    test('returns device list from API response', () async {
      final dio = Dio();
      // J24: only devices seen in the last 30 days are listed.
      final recent = DateTime.now().toUtc();
      dio.httpClientAdapter = _MockAdapter(data: {
        'data': [
          {'deviceId': 'dev-1', 'userId': 'u1',
           'lastPushAt': recent.subtract(const Duration(days: 1)).toIso8601String()},
          {'deviceId': 'dev-2', 'userId': 'u2',
           'lastPushAt': recent.subtract(const Duration(days: 2)).toIso8601String()},
        ],
      });

      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
        ],
      );
      addTearDown(container.dispose);

      final result = await container.read(activeDevicesProvider.future);
      expect(result, hasLength(2));
      expect(result[0]['deviceId'], 'dev-1');
      expect(result[1]['deviceId'], 'dev-2');
    });

    test('returns empty list on error (offline)', () async {
      final dio = Dio();
      dio.httpClientAdapter = _MockAdapter(shouldThrow: true);

      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
        ],
      );
      addTearDown(container.dispose);

      final result = await container.read(activeDevicesProvider.future);
      expect(result, isEmpty);
    });
  });
}

/// Minimal HttpClientAdapter that returns canned data or throws.
class _MockAdapter implements HttpClientAdapter {
  final Map<String, dynamic>? data;
  final bool shouldThrow;

  _MockAdapter({this.data, this.shouldThrow = false});

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (shouldThrow) {
      throw DioException(requestOptions: options);
    }
    final bytes = utf8.encode(jsonEncode(data));
    return ResponseBody.fromBytes(bytes, 200, headers: {
      'content-type': ['application/json'],
    });
  }

  @override
  void close({bool force = false}) {}
}

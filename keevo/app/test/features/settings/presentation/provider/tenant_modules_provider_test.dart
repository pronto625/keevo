import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/settings/presentation/provider/tenant_modules_provider.dart';

// 13.10b — minimal module states from GET /api/v1/tenants/me/modules.

class _Adapter implements HttpClientAdapter {
  final List<String> paths = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    paths.add(options.path);
    return ResponseBody.fromString(
        jsonEncode({
          'data': [
            {'moduleId': 'multi-store', 'state': 'DISABLED'},
            {'moduleId': 'pos', 'state': 'ENABLED'},
            {'moduleId': 'crm', 'state': 'READ_ONLY'},
          ]
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _Adapter adapter;
  late Dio dio;

  setUp(() {
    adapter = _Adapter();
    dio = Dio(BaseOptions(baseUrl: 'https://keevo.test'))
      ..httpClientAdapter = adapter;
  });

  ProviderContainer container({required bool owner}) {
    final c = ProviderContainer(overrides: [
      isOwnerSessionProvider.overrideWithValue(owner),
      dioProvider.overrideWithValue(dio),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  test('owner: states parsed, fetched once per session', () async {
    final c = container(owner: true);

    final modules = await c.read(tenantModuleStatesProvider.future);
    await c.read(tenantModuleStatesProvider.future);

    expect(modules.isReadable('multi-store'), isFalse);
    expect(modules.isReadable('pos'), isTrue);
    expect(modules.isReadable('crm'), isTrue, reason: 'grace: reads pass');
    expect(modules.isReadable('unknown'), isFalse);
    expect(adapter.paths, ['/api/v1/tenants/me/modules']);
  });

  test('employee: nothing fetched (owner-only endpoint)', () async {
    final c = container(owner: false);

    final modules = await c.read(tenantModuleStatesProvider.future);

    expect(modules.states, isEmpty);
    expect(adapter.paths, isEmpty);
  });

  group('isMultiStoreOverviewAvailable', () {
    test('error → false, not cached: the next call asks again', () async {
      var calls = 0;
      final c = ProviderContainer(overrides: [
        tenantModuleStatesProvider.overrideWith((ref) async {
          calls++;
          if (calls == 1) throw Exception('hors ligne');
          return const TenantModuleStates({'multi-store': 'ENABLED'});
        }),
      ]);
      addTearDown(c.dispose);

      expect(await isMultiStoreOverviewAvailable(c), isFalse);
      expect(await isMultiStoreOverviewAvailable(c), isTrue);
      expect(calls, 2);
    });

    test('slow answer → false now, the answer is kept for the next call',
        () async {
      final gate = Completer<TenantModuleStates>();
      var calls = 0;
      final c = ProviderContainer(overrides: [
        tenantModuleStatesProvider.overrideWith((ref) {
          calls++;
          return gate.future;
        }),
      ]);
      addTearDown(c.dispose);

      expect(
          await isMultiStoreOverviewAvailable(c,
              timeout: const Duration(milliseconds: 10)),
          isFalse);
      gate.complete(const TenantModuleStates({'multi-store': 'READ_ONLY'}));
      expect(await isMultiStoreOverviewAvailable(c), isTrue);
      expect(calls, 1);
    });
  });
}

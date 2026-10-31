import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart'
    show dioProvider;
import 'package:keevo/features/export/presentation/provider/export_providers.dart';
import 'package:keevo/root_scope.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/state/provider_retry.dart';

final _answer = Provider<int>((ref) => 1);

/// Story 15.4b — the root scope built by `main` disables Riverpod 3's
/// automatic retry of failing providers.
void main() {
  test('the root scope never retries a failing provider', () {
    final scope = buildRootScope(overrides: const [], child: const SizedBox());
    expect(scope.retry, same(noProviderRetry));
  });

  testWidgets('the root scope keeps its overrides', (tester) async {
    late int read;
    await tester.pumpWidget(
      buildRootScope(
        overrides: [_answer.overrideWithValue(42)],
        child: Consumer(
          builder: (_, ref, __) {
            read = ref.watch(_answer);
            return const SizedBox();
          },
        ),
      ),
    );
    expect(read, 42);
  });

  // Story 15.4c — module packages read keevo_core ports; the root scope maps
  // them to the app's providers.
  testWidgets('the root scope wires the module ports to the app providers', (
    tester,
  ) async {
    final dio = Dio();
    final temp = Directory.systemTemp;
    Future<void> sharer(File _) async {}
    late ProviderContainer container;
    await tester.pumpWidget(
      buildRootScope(
        overrides: [
          dioProvider.overrideWithValue(dio),
          exportFileSharerProvider.overrideWithValue(sharer),
          exportTempDirProvider.overrideWith((ref) async => temp),
        ],
        child: Consumer(
          builder: (context, _, __) {
            container = ProviderScope.containerOf(context);
            return const SizedBox();
          },
        ),
      ),
    );
    expect(container.read(apiDioProvider), same(dio));
    expect(container.read(fileSharerProvider), same(sharer));
    await tester.runAsync(() async {
      expect(await container.read(tempDirProvider.future), same(temp));
    });
  });

  testWidgets('a port overridden by the caller replaces the default wiring', (
    tester,
  ) async {
    final dio = Dio();
    late ProviderContainer container;
    await tester.pumpWidget(
      buildRootScope(
        overrides: [apiDioProvider.overrideWithValue(dio)],
        child: Consumer(
          builder: (context, _, __) {
            container = ProviderScope.containerOf(context);
            return const SizedBox();
          },
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    expect(container.read(apiDioProvider), same(dio));
    final scope = buildRootScope(
      overrides: [apiDioProvider.overrideWithValue(dio)],
      child: const SizedBox(),
    );
    expect(scope.overrides, hasLength(modulePortOverrides.length));
  });
}

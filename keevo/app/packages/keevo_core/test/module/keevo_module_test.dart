import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/state/provider_retry.dart';

Future<void> _noop(_, __, ___) async {}

/// The error a port read fails with, unwrapped from Riverpod's wrapper.
Object _errorOf(void Function() read) {
  try {
    read();
  } catch (e) {
    return e;
  }
  fail('expected the port to throw');
}

void main() {
  group('ports (15.4c)', () {
    test('not overridden → error naming the port', () async {
      final c = ProviderContainer(retry: noProviderRetry);
      addTearDown(c.dispose);
      expect(
        _errorOf(() => c.read(apiDioProvider)).toString(),
        allOf(contains('apiDioProvider'), contains('buildRootScope')),
      );
      expect(
        _errorOf(() => c.read(fileSharerProvider)).toString(),
        contains('fileSharerProvider'),
      );
      await expectLater(
        c.read(tempDirProvider.future),
        throwsA(
          predicate(
            (Object e) =>
                e is UnimplementedError &&
                e.toString().contains('tempDirProvider'),
          ),
        ),
      );
    });

    test('overridden → the app implementation', () async {
      final dio = Dio();
      final temp = Directory.systemTemp;
      final shared = <File>[];
      final c = ProviderContainer(
        retry: noProviderRetry,
        overrides: [
          apiDioProvider.overrideWithValue(dio),
          fileSharerProvider.overrideWithValue((f) async => shared.add(f)),
          tempDirProvider.overrideWith((ref) async => temp),
        ],
      );
      addTearDown(c.dispose);
      expect(c.read(apiDioProvider), same(dio));
      expect(await c.read(tempDirProvider.future), same(temp));
      await c.read(fileSharerProvider)(File('x.pdf'));
      expect(shared.single.path, 'x.pdf');
    });
  });

  group('KeevoModuleUi composition', () {
    final a = KeevoModuleUi(
      id: 'a',
      routes: [
        GoRoute(path: '/a/:id', builder: (_, __) => const Placeholder()),
      ],
      actions: const {'a.open': _noop},
    );
    final b = KeevoModuleUi(
      id: 'b',
      routes: [GoRoute(path: '/b', builder: (_, __) => const Placeholder())],
      actions: const {'b.open': _noop},
    );

    test('routes and actions of every module, in order', () {
      expect(
        composeModuleRoutes([a, b]).map((r) => (r as GoRoute).path).toList(),
        ['/a/:id', '/b'],
      );
      expect(composeModuleActions([a, b]).keys, ['a.open', 'b.open']);
    });

    test('no module → no route, no action', () {
      expect(composeModuleRoutes(const []), isEmpty);
      expect(composeModuleActions(const []), isEmpty);
    });

    test('same action key twice / same module twice → StateError', () {
      const clash = KeevoModuleUi(id: 'c', actions: {'a.open': _noop});
      expect(() => composeModuleActions([a, clash]), throwsStateError);
      expect(() => composeModuleActions([a, a]), throwsStateError);
    });

    test('same route path twice / same module twice → StateError', () {
      final clash = KeevoModuleUi(
        id: 'c',
        routes: [
          GoRoute(path: '/a/:id', builder: (_, __) => const Placeholder()),
        ],
      );
      expect(() => composeModuleRoutes([a, clash]), throwsStateError);
      expect(() => composeModuleRoutes([a, a]), throwsStateError);
    });
  });
}

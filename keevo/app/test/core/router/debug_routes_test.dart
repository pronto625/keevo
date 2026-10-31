import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/router/app_router.dart';

/// Story 15.8 — the `debug` feature is not part of a release build: its
/// routes are mounted in debug builds only and nothing in the app links to
/// them.
void main() {
  List<String> paths(List<RouteBase> routes) => [
        for (final r in routes) ...[
          if (r is GoRoute) r.path,
          ...paths(r.routes),
        ],
      ];

  test('release route table has no /debug path', () {
    final release = paths(buildAppRoutes(includeDebug: false));
    expect(release, contains('/dashboard'));
    expect(release.where((p) => p.startsWith('/debug')), isEmpty);
  });

  test('debug builds keep the debug screens', () {
    expect(paths(buildAppRoutes(includeDebug: true)),
        contains('/debug/categories'));
  });

  test('no screen links to a /debug route', () {
    final offenders = Directory('lib')
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .where((f) => !f.path.endsWith('core/router/app_router.dart'))
        .where((f) => RegExp('[\'"]/debug').hasMatch(f.readAsStringSync()))
        .map((f) => f.path)
        .toList();
    expect(offenders, isEmpty);
  });
}

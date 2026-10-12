import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/router/app_router.dart';

/// Full paths of every [GoRoute] under [routes]. Shell routes add no path
/// segment; a relative child path is joined to its parent's full path.
List<String> _fullPaths(List<RouteBase> routes, [String parent = '']) {
  final paths = <String>[];
  for (final route in routes) {
    if (route is GoRoute) {
      final full = route.path.startsWith('/')
          ? route.path
          : '${parent == '/' ? '' : parent}/${route.path}';
      paths
        ..add(full)
        ..addAll(_fullPaths(route.routes, full));
    } else {
      paths.addAll(_fullPaths(route.routes, parent));
    }
  }
  return paths;
}

/// Shape of [path] as the router matches it: every `:param` segment becomes
/// the same placeholder and a trailing slash is dropped, so `/x/:id` and
/// `/x/:productId/` compare equal (the second is shadowed by the first).
String _normalised(String path) {
  final shape =
      path.split('/').map((s) => s.startsWith(':') ? ':_' : s).join('/');
  return shape.length > 1 && shape.endsWith('/')
      ? shape.substring(0, shape.length - 1)
      : shape;
}

/// Normalised paths that occur more than once in [paths].
Set<String> _duplicates(List<String> paths) {
  final seen = <String>{};
  return paths.map(_normalised).where((p) => !seen.add(p)).toSet();
}

/// v1s-15-8 (deferred from CR v1s-16-9): a duplicated GoRoute path is
/// silently shadowed by the first match — guard the whole route table.
void main() {
  late List<String> paths;
  setUpAll(() => paths = _fullPaths(appRouter.configuration.routes));

  test('the route table is walked (sanity)', () {
    expect(paths, containsAll(['/splash', '/auth/login', '/pos']));
  });

  test('every GoRoute full path is unique (param names ignored)', () {
    final duplicates = _duplicates(paths);
    expect(duplicates, isEmpty, reason: 'duplicated paths: $duplicates');
  });

  test('every GoRoute full path is non-empty and absolute', () {
    for (final path in paths) {
      expect(path, startsWith('/'), reason: path);
      expect(path.contains('//'), isFalse, reason: path);
    }
  });

  test('the walker flags a duplicate (self-check)', () {
    final dup = _fullPaths([
      GoRoute(path: '/a', builder: (_, __) => throw UnimplementedError()),
      ShellRoute(
        builder: (_, __, child) => child,
        routes: [
          GoRoute(path: '/a', builder: (_, __) => throw UnimplementedError()),
        ],
      ),
    ]);
    expect(dup, ['/a', '/a']);
    expect(_duplicates(dup), {'/a'});
  });

  test('the check flags a duplicate differing only by param name (self-check)',
      () {
    final dup = _fullPaths([
      GoRoute(
        path: '/x/:id',
        builder: (_, __) => throw UnimplementedError(),
      ),
      GoRoute(
        path: '/x',
        builder: (_, __) => throw UnimplementedError(),
        routes: [
          GoRoute(
            path: ':productId/',
            builder: (_, __) => throw UnimplementedError(),
          ),
        ],
      ),
    ]);
    expect(_duplicates(dup), {'/x/:_'});
  });
}

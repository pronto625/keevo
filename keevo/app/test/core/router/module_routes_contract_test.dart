import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/core/router/module_routes.dart';

/// Story 15.2 — the app route registry equals the shared contract file
/// `keevo/contracts/module-routes.json`, itself generated from the backend
/// module manifests (`ModuleRoutesContractFileTest`). A manifest change breaks
/// the backend test until the JSON is regenerated, then this test until
/// `module_routes.dart` follows.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final contract =
      jsonDecode(File('../contracts/module-routes.json').readAsStringSync())
          as Map<String, dynamic>;

  /// Every route path mounted in [appRouter] (shell children included).
  List<String> mountedPaths() {
    final paths = <String>[];
    void walk(List<RouteBase> routes) {
      for (final r in routes) {
        if (r is GoRoute) paths.add(r.path);
        walk(r.routes);
      }
    }

    walk(appRouter.configuration.routes);
    return paths;
  }

  test('registry = contract (same modules, same routes, same order)', () {
    expect(kModuleRoutes, contract);
  });

  // kFreeRouteShadows lives only in the app: a manifest declaring one of
  // these routes would be silently ungated.
  test('no registry route is a free shadow', () {
    for (final route in kModuleRoutes.values.expand((r) => r)) {
      expect(kFreeRouteShadows, isNot(contains(route)), reason: route);
    }
  });

  test('always-on free modules have no route entry', () {
    for (final id in const [
      'core',
      'catalog',
      'pos',
      'inventory',
      'store',
      'sync',
      'reporting'
    ]) {
      expect(kModuleRoutes.containsKey(id), isFalse, reason: id);
    }
  });

  test('every registry route except invoicing (UI in 15.5) is mounted', () {
    final mounted = mountedPaths().toSet();
    for (final entry in kModuleRoutes.entries) {
      if (entry.key == 'invoicing') continue;
      for (final route in entry.value) {
        expect(mounted, contains(route), reason: '${entry.key} $route');
      }
    }
  });

  test('a mounted route outside the registry belongs to no module', () {
    final registered = kModuleRoutes.values.expand((r) => r).toSet();
    for (final path in mountedPaths()) {
      if (registered.contains(path)) continue;
      expect(moduleOfPath(path), isNull, reason: path);
    }
  });

  group('moduleOfPath', () {
    test('literal and :param segments', () {
      expect(moduleOfPath('/stock/transfers'), 'multi-store');
      expect(moduleOfPath('/inventory/counting/abc-123'), 'counting');
      expect(moduleOfPath('/inventory/gap-report/s1'), 'counting');
      expect(moduleOfPath('/inventory'), 'counting');
      expect(moduleOfPath('/reports/rentabilite/p9'), 'profitability');
      expect(moduleOfPath('/clients/c1'), 'crm');
      expect(moduleOfPath('/suppliers/new'), 'crm');
      expect(moduleOfPath('/settings/reports'), 'report-config');
      expect(moduleOfPath('/exports/job-1'), 'exports');
      expect(moduleOfPath('/invoicing/i1'), 'invoicing');
    });

    test('query string and trailing slash ignored; patterns accepted', () {
      expect(moduleOfPath('/stock/overview?showLowOnly=true'), 'multi-store');
      expect(moduleOfPath('/clients/'), 'crm');
      expect(moduleOfPath('/clients/:id'), 'crm');
    });

    test('free routes stay free', () {
      expect(moduleOfPath('/exports/new'), isNull);
      expect(moduleOfPath('/exports/local'), isNull);
      expect(moduleOfPath('/stock/low'), isNull);
      expect(moduleOfPath('/reports'), isNull);
      expect(moduleOfPath('/reports/history'), isNull);
      expect(moduleOfPath('/settings'), isNull);
      expect(moduleOfPath('/'), isNull);
      expect(moduleOfPath('/clients/c1/extra'), isNull);
    });
  });
}

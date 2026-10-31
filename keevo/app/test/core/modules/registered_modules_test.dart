import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/extension/extension_actions.dart';
import 'package:keevo/core/modules/registered_modules.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/core/router/module_routes.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_invoicing/invoicing_module.dart';

/// Story 15.4c — the app shell composes the registered module descriptors.
void main() {
  test('invoicing is registered; every id is a manifest module id', () {
    expect(kRegisteredModules.map((m) => m.id), contains('invoicing'));
    for (final module in kRegisteredModules) {
      expect(
        kModuleRoutes.containsKey(module.id),
        isTrue,
        reason: '${module.id} is not a module id of module_routes.dart',
      );
    }
  });

  test('every module route is a manifest route of its module (15.2 guard)', () {
    for (final module in kRegisteredModules) {
      for (final route in module.routes.whereType<GoRoute>()) {
        final path = route.path;
        expect(
          kModuleRoutes[module.id],
          contains(path),
          reason: '${module.id}: $path missing from module_routes.dart',
        );
        expect(moduleOfPath(path), module.id);
      }
    }
  });

  test('module routes and actions are mounted in the app', () {
    for (final module in kRegisteredModules) {
      for (final route in module.routes.whereType<GoRoute>()) {
        final concrete = route.path.replaceAll(RegExp(r':\w+'), 'x');
        final match = appRouter.configuration.findMatch(Uri.parse(concrete));
        expect(
          match.matches,
          isNotEmpty,
          reason: '${route.path} (${module.id}) not mounted',
        );
        expect(match.matches.last.route, same(route));
      }
      for (final key in module.actions.keys) {
        expect(kExtensionActions[key], same(module.actions[key]));
      }
    }
    expect(kExtensionActions.keys, contains(kOpenSaleInvoiceAction));
  });

  test('no module route path is also a static app route path', () {
    final moduleRoutes = {
      for (final module in kRegisteredModules) ...module.routes,
    };
    final modulePaths = {
      for (final r in moduleRoutes.whereType<GoRoute>()) r.path,
    };
    final appPaths = <String>[];
    void collect(List<RouteBase> routes, String parent) {
      for (final route in routes) {
        var path = parent;
        if (route is GoRoute) {
          path = route.path.startsWith('/')
              ? route.path
              : '${parent == '/' ? '' : parent}/${route.path}';
          if (!moduleRoutes.contains(route)) appPaths.add(path);
        }
        collect(route.routes, path);
      }
    }

    collect(appRouter.configuration.routes, '');
    expect(appPaths, isNotEmpty);
    expect(appPaths.where(modulePaths.contains), isEmpty);
  });

  test('module not registered → route not mounted, action key unknown', () {
    final others = kRegisteredModules
        .where((m) => m.id != kInvoicingModuleId)
        .toList();
    expect(
      composeModuleRoutes(others).whereType<GoRoute>().map((r) => r.path),
      isNot(contains('/invoicing/:id')),
    );
    expect(
      composeModuleActions(others).containsKey(kOpenSaleInvoiceAction),
      isFalse,
    );
  });
}

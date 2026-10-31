import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'module_records.dart';

/// Story 15.3 — runs one extension contribution (a server-driven slot
/// button). [args] is the slot's context (e.g. `{'saleId': …}` on the
/// checkout slot).
typedef ExtensionActionHandler =
    Future<void> Function(
      BuildContext context,
      WidgetRef ref,
      Map<String, Object?> args,
    );

/// Story 15.4c — what a module package contributes to the app shell's UI.
///
/// The app keeps one list of registered descriptors and composes them: the
/// [routes] are mounted in the app router (the 15.2 module guard still applies
/// through the route registry, so each path must be declared in the module
/// manifest `routes`), the [actions] join the extension action registry
/// (action key sent by the server → handler, 15.3). Labels, order and
/// permissions of contributions stay server-side.
///
/// Heavy module code is reached only through `deferred as` imports inside
/// the module package: route builders wrap it in `DeferredWidget`, handlers
/// call `loadLibrary()` first. The descriptor library itself stays small.
@immutable
class KeevoModuleUi {
  /// Module id of the backend manifests (e.g. `invoicing`).
  final String id;

  /// Routes of the module, mounted as top-level routes of the app router.
  final List<RouteBase> routes;

  /// Extension action handlers, by action key (e.g.
  /// `invoicing.open-sale-invoice`).
  final Map<String, ExtensionActionHandler> actions;

  /// Story 15.5b — the module's entities of the sync pull, by pull key (e.g.
  /// `invoices`): the app shell hands each pulled list to its merger, which
  /// stores it in the module records (`replaceUnlessPending`). The merger is
  /// called from the descriptor library: it must stay small (no screen).
  final Map<String, ModuleEntityMerger> pulledEntities;

  /// Story 15.5b — the API path of one record, by pull key (e.g. `invoices`
  /// → `/api/v1/invoices/<id>`): after the server refused an operation of a
  /// record, the app shell reads that record again (answer `{data: row}`,
  /// stored through the merger; 404 = gone) instead of a full pull.
  final Map<String, String Function(String id)> recordPaths;

  const KeevoModuleUi({
    required this.id,
    this.routes = const [],
    this.actions = const {},
    this.pulledEntities = const {},
    this.recordPaths = const {},
  });
}

/// Routes of every module of [modules], in registration order. The same
/// module registered twice, or two top-level [GoRoute]s with the same path,
/// is a programming error ([StateError]).
List<RouteBase> composeModuleRoutes(List<KeevoModuleUi> modules) {
  final ids = <String>{};
  final paths = <String>{};
  final routes = <RouteBase>[];
  for (final module in modules) {
    if (!ids.add(module.id)) {
      throw StateError('Module "${module.id}" registered twice');
    }
    for (final route in module.routes) {
      if (route is GoRoute && !paths.add(route.path)) {
        throw StateError(
          'Route "${route.path}" registered twice (second: "${module.id}")',
        );
      }
      routes.add(route);
    }
  }
  return List.unmodifiable(routes);
}

/// Action registry of [modules]. Two modules claiming the same action key, or
/// the same module registered twice, is a programming error ([StateError]).
Map<String, ExtensionActionHandler> composeModuleActions(
  List<KeevoModuleUi> modules,
) {
  final ids = <String>{};
  final actions = <String, ExtensionActionHandler>{};
  for (final module in modules) {
    if (!ids.add(module.id)) {
      throw StateError('Module "${module.id}" registered twice');
    }
    for (final entry in module.actions.entries) {
      if (actions.containsKey(entry.key)) {
        throw StateError(
          'Extension action "${entry.key}" registered by two '
          'modules (second: "${module.id}")',
        );
      }
      actions[entry.key] = entry.value;
    }
  }
  return Map.unmodifiable(actions);
}

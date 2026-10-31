import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/entitlements/entitlements.dart';
import '../entitlements/entitlements_provider.dart';

/// Story 15.2 — route registry: module id → the app route patterns of that
/// module (go_router syntax, `:name` = any one segment).
///
/// Same content as `keevo/contracts/module-routes.json`, generated from the
/// backend module manifests (`routes`) — `module_routes_contract_test.dart`
/// fails when they differ. Free modules that are always on (core, catalog,
/// pos, inventory, store, sync, reporting) have no entry: their routes are
/// never gated.
const kModuleRoutes = <String, List<String>>{
  'advanced-reports': [
    '/reports/equipe',
  ],
  'counting': [
    '/inventory',
    '/inventory/counting/:sessionId',
    '/inventory/gap-report/:sessionId',
  ],
  'crm': [
    '/clients',
    '/clients/new',
    '/clients/:id',
    '/suppliers',
    '/suppliers/new',
    '/suppliers/:id',
  ],
  'exports': [
    '/exports',
    '/exports/:id',
  ],
  'invoicing': [
    '/invoicing',
    '/invoicing/new',
    '/invoicing/settings',
    '/invoicing/:id',
    '/invoicing/:id/edit',
  ],
  'multi-store': [
    '/stock/overview',
    '/stock/transfers',
    '/reports/boutiques',
  ],
  'profitability': [
    '/reports/rentabilite',
    '/reports/rentabilite/:productId',
  ],
  'report-config': [
    '/settings/reports',
  ],
};

/// App routes declared before a module's `:param` route of the same shape:
/// go_router opens them, not the module route, so they stay free. The server
/// CSV export (`/exports/new`, all plans) and the on-device CSV
/// (`/exports/local`) are not part of the paid « Exports PDF et Excel »
/// module, whose own screen is « Mes exports » (`/exports`, `/exports/:id`).
const kFreeRouteShadows = <String>{'/exports/new', '/exports/local'};

/// Route of the « module non activé » screen; `module` query parameter =
/// module id (never shown).
const kModuleUnavailableRoute = '/module-unavailable';

List<String> _segments(String path) =>
    path.split('/').where((s) => s.isNotEmpty).toList();

bool _matches(List<String> pattern, List<String> path) {
  if (pattern.length != path.length) return false;
  for (var i = 0; i < pattern.length; i++) {
    if (!pattern[i].startsWith(':') && pattern[i] != path[i]) return false;
  }
  return true;
}

/// Module owning [location] (a concrete path such as `/clients/42`, query
/// string allowed, or a route pattern such as `/clients/:id`), null for a
/// route of no gated module.
String? moduleOfPath(String location) {
  final path = Uri.tryParse(location)?.path ?? location;
  final segments = _segments(path);
  if (segments.isEmpty) return null;
  if (kFreeRouteShadows.contains('/${segments.join('/')}')) return null;
  for (final entry in kModuleRoutes.entries) {
    for (final route in entry.value) {
      if (_matches(_segments(route), segments)) return entry.key;
    }
  }
  return null;
}

/// True when [location] may be opened with [entitlements]: not a module
/// route, nothing known yet (`unknown` — the backend still decides, its 403
/// keeps the French message), or the module is readable (ENABLED or
/// READ_ONLY grace).
bool isRouteReadable(Entitlements entitlements, String location) {
  final module = moduleOfPath(location);
  if (module == null || !entitlements.isKnown) return true;
  return entitlements.isReadable(module);
}

/// Widget helper — whether an entry (tab, tile, button) opening [location]
/// is shown. Rebuilds the caller when the module states change.
bool isRouteAvailable(WidgetRef ref, String location) {
  final entitlements =
      ref.watch(entitlementsProvider).value ?? Entitlements.unknown;
  return isRouteReadable(entitlements, location);
}

/// Story 15.8 — same answer as [isRouteAvailable] read once, without
/// rebuilding the caller: for a callback deciding where to navigate.
bool isRouteAvailableNow(WidgetRef ref, String location) {
  final entitlements =
      ref.read(entitlementsProvider).value ?? Entitlements.unknown;
  return isRouteReadable(entitlements, location);
}

/// Module id of the « Rapports avancés » (leaderboard, weekly report).
const kAdvancedReportsModuleId = 'advanced-reports';

/// Story 15.8 — [moduleId] may be read with [entitlements]: nothing known
/// yet (the backend still decides) or ENABLED / READ_ONLY.
bool isModuleReadable(Entitlements entitlements, String moduleId) =>
    !entitlements.isKnown || entitlements.isReadable(moduleId);

/// Story 15.8 — [moduleId] may be written with [entitlements]: nothing known
/// yet or ENABLED (READ_ONLY grace refuses writes).
bool isModuleWritable(Entitlements entitlements, String moduleId) =>
    !entitlements.isKnown || entitlements.isActive(moduleId);

Entitlements _watch(WidgetRef ref) =>
    ref.watch(entitlementsProvider).value ?? Entitlements.unknown;

Entitlements _read(WidgetRef ref) =>
    ref.read(entitlementsProvider).value ?? Entitlements.unknown;

/// Story 15.8 — whether an in-page entry of [moduleId] that opens no route of
/// its own (e.g. the CRM client picker at checkout) is shown: same rule as
/// [isRouteAvailable] (nothing known yet → shown, the backend 403 stays the
/// fallback; READ_ONLY → shown). Rebuilds the caller.
bool isModuleAvailable(WidgetRef ref, String moduleId) =>
    isModuleReadable(_watch(ref), moduleId);

/// [isModuleAvailable] read once, without rebuilding the caller (callbacks).
bool isModuleAvailableNow(WidgetRef ref, String moduleId) =>
    isModuleReadable(_read(ref), moduleId);

/// Story 15.8 — whether an in-page action of [moduleId] that WRITES (e.g. the
/// « Aperçu du rapport hebdomadaire » of `advanced-reports`) or edits its
/// settings is shown: nothing known yet or ENABLED; hidden in READ_ONLY grace.
bool isModuleActive(WidgetRef ref, String moduleId) =>
    isModuleWritable(_watch(ref), moduleId);

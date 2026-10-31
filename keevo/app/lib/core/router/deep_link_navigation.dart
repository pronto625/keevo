import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/entitlements/entitlements.dart';
import '../entitlements/entitlements_provider.dart';

/// Locations of the bottom-navigation tabs (the `ShellRoute` in
/// `app_router.dart`). They live in the shell's own navigator: pushing one
/// over a full-screen page (e.g. « Notifications ») adds a second shell page
/// with the same key → Flutter assertion `!keyReservation.contains(key)`
/// (red screen, J16). They must be opened with `go`.
const shellTabLocations = <String>{
  '/dashboard',
  '/pos',
  '/products',
  '/reports',
  '/settings',
};

/// True when [location] (query string allowed) is one of the shell tabs.
bool isShellTabLocation(String location) {
  final path = Uri.tryParse(location)?.path ?? location;
  final normalized = path.length > 1 && path.endsWith('/')
      ? path.substring(0, path.length - 1)
      : path;
  return shellTabLocations.contains(normalized);
}

/// Server deep links that do not match an app route as such (older
/// notifications are stored on the device): mapped to the screen showing
/// the same information.
///   * `/products/{id}` → `/products/{id}/edit` (product form, loaded by id)
///   * `/stock/history` → `/stock/overview`
///
/// Story 15.2 — a stock overview link (`/stock/history`, `/stock/overview`)
/// opens the free local « stock bas » list (`/stock/low`, every store) when
/// [entitlements] say `multi-store` is not readable — same destination as
/// `openLowStockView` (13.10b) instead of « module non activé ». Unknown
/// states keep the overview (the backend decides).
String normalizeDeepLink(String location, {Entitlements? entitlements}) {
  final target = _normalizeRoute(location);
  final e = entitlements;
  if (e != null && e.isKnown && !e.isReadable(kMultiStoreModuleId)) {
    final uri = Uri.tryParse(target);
    if (uri != null && uri.path == '/stock/overview') {
      return '/stock/low?all=true';
    }
  }
  return target;
}

String _normalizeRoute(String location) {
  final uri = Uri.tryParse(location);
  if (uri == null) return location;
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length == 2 &&
      segments[0] == 'products' &&
      !const {'new', 'import'}.contains(segments[1])) {
    return uri.replace(path: '/products/${segments[1]}/edit').toString();
  }
  if (segments.length == 2 &&
      segments[0] == 'stock' &&
      segments[1] == 'history') {
    return uri.replace(path: '/stock/overview').toString();
  }
  return location;
}

/// Opens a notification / push deep link — the ONE place both the
/// « Notifications » page and the FCM tap handlers go through.
///
/// A bottom-nav tab is opened with `go` (switches tab, no duplicate shell
/// page); any other screen is pushed so BACK returns where the user was.
///
/// [entitlements]: the module states (default: read from the router's
/// ProviderScope; none reachable → unknown).
void openDeepLink(GoRouter router, String location,
    {Entitlements? entitlements}) {
  final target = normalizeDeepLink(location.trim(),
      entitlements: entitlements ?? _entitlementsOf(router));
  if (target.isEmpty) return;
  if (isShellTabLocation(target)) {
    router.go(target);
  } else {
    router.push(target);
  }
}

Entitlements? _entitlementsOf(GoRouter router) {
  try {
    final context = router.routerDelegate.navigatorKey.currentContext;
    if (context == null) return null;
    return ProviderScope.containerOf(context, listen: false)
        .read(entitlementsProvider)
        .value;
  } catch (_) {
    return null;
  }
}

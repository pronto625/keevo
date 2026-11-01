import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';

import '../router/app_router.dart';
import 'entitlements_provider.dart';

/// Story 16.3 — asks the server for the module states again when the app comes
/// back to the foreground or the network comes back (tokens carry no module
/// list, and a token can live for weeks). Throttled by
/// [EntitlementsNotifier.refreshIfStale]; no polling timer.
class EntitlementsRefreshTrigger {
  /// `refreshIfStale(force:)`.
  final Future<void> Function({bool force}) _refresh;

  /// The last connectivity seen said offline.
  bool _wasOffline = false;

  EntitlementsRefreshTrigger(this._refresh);

  /// The app came back to the foreground.
  void onResumed() => unawaited(_refresh());

  /// A connectivity event: offline → online asks again (the first event at
  /// startup does not — the provider's own build already asks).
  void onConnectivity(List<ConnectivityResult> results) {
    final online = isOnline(results);
    // One forced attempt per offline → online edge (no 60 s throttle).
    if (online && _wasOffline) unawaited(_refresh(force: true));
    _wasOffline = !online;
  }
}

/// Mounted for the whole app by [listenEntitlements] (from `KeevoApp`): listens to the app lifecycle
/// (resume) and to the connectivity signal the sync trigger also listens to.
final entitlementsRefreshTriggerProvider = Provider<EntitlementsRefreshTrigger>(
  (ref) {
    final trigger = EntitlementsRefreshTrigger(
      ({bool force = false}) =>
          ref.read(entitlementsProvider.notifier).refreshIfStale(force: force),
    );
    ref.listen<AsyncValue<List<ConnectivityResult>>>(
      connectivityStreamProvider,
      (_, next) => next.whenData(trigger.onConnectivity),
      fireImmediately: true,
    );
    final lifecycle = AppLifecycleListener(onResume: trigger.onResumed);
    ref.onDispose(lifecycle.dispose);
    return trigger;
  },
);

/// Story 15.2 / 16.3 — the root listeners of the module states, called from
/// the `build` of the widget hosting the router (`KeevoApp`): the router
/// re-runs its module guard when the states change ([listenModuleGate]), and
/// the states are asked again on resume / network back
/// ([entitlementsRefreshTriggerProvider], kept mounted by this watch —
/// Riverpod 3 pauses a provider nobody listens to).
void listenEntitlements(WidgetRef ref, {ModuleGateRefresh? refresh}) {
  listenModuleGate(ref, refresh: refresh);
  ref.watch(entitlementsRefreshTriggerProvider);
}

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/providers.dart';
import 'connectivity_service.dart';
import 'sync_trigger_dispatcher.dart';
import 'sync_trigger_notifier.dart';

/// Concrete implementation: delegates to [SyncTriggerNotifier.triggerPush()]
/// behind an online guard, fire-and-forget via [Future.microtask].
///
/// Story 5.6 — AC6.
class RiverpodSyncTriggerDispatcher implements SyncTriggerDispatcher {
  final Ref _ref;
  final ConnectivityService _connectivity;

  const RiverpodSyncTriggerDispatcher(this._ref, this._connectivity);

  @override
  void triggerPushIfIdle() {
    // Fire-and-forget: NEVER await in calling code — this is purely background.
    // Scheduled from the root zone: a caller inside a drift transaction (draft
    // checkout wraps the sale in one) would otherwise hand that zone to the
    // push, which would then run on the transaction before it commits, or on
    // a transaction already closed. From the root zone the push waits for the
    // commit like any other query.
    Zone.root.run(() => Future.microtask(() async {
      try {
        if (await _connectivity.isOnline()) {
          _ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
        }
      } on StateError {
        // Ref stale — ProviderScope rebuilt (auth change / employee creation).
        // Sync will be triggered on the next write operation. Swallow silently.
      }
    }));
  }
}

/// Provider for [SyncTriggerDispatcher] — injected into repos.
final syncTriggerDispatcherProvider = Provider<SyncTriggerDispatcher>((ref) {
  return RiverpodSyncTriggerDispatcher(
    ref,
    ref.read(connectivityServiceProvider),
  );
});

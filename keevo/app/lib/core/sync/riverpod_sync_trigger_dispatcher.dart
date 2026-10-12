import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/providers.dart';
import 'connectivity_service.dart';
import 'sync_trigger_dispatcher.dart';
import 'sync_trigger_notifier.dart';

/// Concrete implementation: delegates to [SyncTriggerNotifier.triggerPush()]
/// behind an online guard, fire-and-forget via [Future.microtask].
///
/// Story 5.6 — AC6.
///
/// The captured [Ref] belongs to the provider that built this dispatcher.
/// Once that provider is disposed (ProviderScope rebuilt on auth change,
/// container disposed) [dispose] is called and the dispatcher becomes a
/// no-op: it never touches the stale [Ref] again.
class RiverpodSyncTriggerDispatcher implements SyncTriggerDispatcher {
  final Ref _ref;
  final ConnectivityService _connectivity;
  bool _disposed = false;

  RiverpodSyncTriggerDispatcher(this._ref, this._connectivity);

  /// Whether [dispose] was called.
  @visibleForTesting
  bool get isDisposed => _disposed;

  /// Turns [triggerPushIfIdle] into a no-op. Registered with `ref.onDispose`
  /// by [syncTriggerDispatcherProvider].
  void dispose() {
    _disposed = true;
  }

  @override
  void triggerPushIfIdle() {
    if (_disposed) return;
    // Fire-and-forget: NEVER await in calling code — this is purely background.
    // Scheduled from the root zone: a caller inside a drift transaction (draft
    // checkout wraps the sale in one) would otherwise hand that zone to the
    // push, which would then run on the transaction before it commits, or on
    // a transaction already closed. From the root zone the push waits for the
    // commit like any other query.
    Zone.root.run(() => Future.microtask(() async {
      // Re-checked here and after the connectivity await: the scope may be
      // disposed between scheduling and running.
      if (_disposed) return;
      try {
        if (await _connectivity.isOnline() && !_disposed) {
          _ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
        }
      } on StateError {
        // Belt and braces: Ref stale despite the disposed flag. Sync will be
        // triggered on the next write operation. Swallow silently.
      }
    }));
  }
}

/// Provider for [SyncTriggerDispatcher] — injected into repos.
final syncTriggerDispatcherProvider = Provider<SyncTriggerDispatcher>((ref) {
  final dispatcher = RiverpodSyncTriggerDispatcher(
    ref,
    ref.read(connectivityServiceProvider),
  );
  ref.onDispose(dispatcher.dispose);
  return dispatcher;
});

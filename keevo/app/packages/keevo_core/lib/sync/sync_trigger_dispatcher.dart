/// SyncTriggerDispatcher — abstraction for fire-and-forget background push.
///
/// Repos inject this interface to trigger offline-first sync without coupling
/// to Riverpod Ref. The concrete [RiverpodSyncTriggerDispatcher] wraps the
/// [SyncTriggerNotifier] behind a connectivity check.
///
/// Story 5.6 — AC6.
abstract interface class SyncTriggerDispatcher {
  /// Triggers a background push if the notifier is idle.
  ///
  /// Non-blocking: fire-and-forget. No-op if already syncing.
  /// Connectivity check is performed inside the implementation.
  void triggerPushIfIdle();
}

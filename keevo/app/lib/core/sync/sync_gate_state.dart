/// SyncGateState — temporal state of the offline access gate.
///
/// State pattern: each value maps to distinct UI behavior and write blocking.
/// Transitions are driven by [daysSinceLastSyncProvider] which reads
/// [kLastSyncAtKey] from SharedPreferences.
///
/// State thresholds:
///   0–4 days → open
///   5 days   → warning
///   6 days   → critical
///   ≥7 days  → blocked
enum SyncGateState {
  /// Days 0–4: normal operation. No restrictions, no banners.
  open,

  /// Day 5: yellow persistent banner. Writes still allowed.
  warning,

  /// Day 6: red persistent banner (not dismissable). Writes still allowed.
  critical,

  /// Days ≥ 7: writes BLOCKED. SyncRequiredModal opens on any write attempt.
  blocked;

  /// Derives [SyncGateState] from [daysSinceLastSync].
  static SyncGateState fromDaysSinceLastSync(int days) {
    if (days < 5) return SyncGateState.open;
    if (days == 5) return SyncGateState.warning;
    if (days == 6) return SyncGateState.critical;
    return SyncGateState.blocked;
  }

  /// Whether write operations are forbidden in this state.
  bool get isWriteBlocked => this == SyncGateState.blocked;

  /// Whether the offline gate banner should be shown.
  bool get showsBanner =>
      this == SyncGateState.warning || this == SyncGateState.critical;
}

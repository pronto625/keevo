/// SyncStatus — enum for connectivity and sync state.
///
/// State pattern: each value represents a distinct UI + behavioral state.
/// Open/Closed: adding a new state = add a new value + handle in the switch.
///
/// AC3: 4 states — online, syncing, offlineOk, offlineCritical.
enum SyncStatus {
  online,
  syncing,
  offlineOk,
  offlineCritical;

  /// Derives [SyncStatus] from the number of consecutive offline days.
  ///
  /// - [days] == 0 → [online] (device is connected)
  /// - [days] 1–4  → [offlineOk] (within safe window)
  /// - [days] ≥ 5  → [offlineCritical] (approaching 7-day limit)
  static SyncStatus fromDaysOffline(int days) {
    if (days == 0) return SyncStatus.online;
    if (days < 5) return SyncStatus.offlineOk;
    return SyncStatus.offlineCritical;
  }
}

import '../model/day_closure_model.dart';

/// DayClosureRepository — Interface for day closure operations.
///
/// Follows hexagonal architecture: domain depends on this abstraction,
/// data layer provides the implementation.
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
abstract interface class DayClosureRepository {
  /// Compute the summary of today's sales for a store.
  ///
  /// Aggregates from local Drift database (offline-first).
  /// [storeId] — Store to compute summary for.
  /// [employeeId] — Employee to filter sales for (optional for OWNER).
  Future<DayClosureSummary> computeTodaySummary(String storeId, String? employeeId);

  /// Save a closure record locally.
  ///
  /// Also enqueues for sync to backend.
  Future<void> saveClosureLocally(DayClosure closure);

  /// Check if a closure already exists for today.
  ///
  /// Returns true if the store has already been closed today.
  Future<bool> hasClosureToday(String storeId);

  /// Get the most recent closure for a store.
  ///
  /// Returns null if no closure exists.
  Future<DayClosure?> getLastClosure(String storeId);

  /// Get today's completed sales count for the badge.
  ///
  /// [storeId] — Store to count sales for.
  Future<int> getTodaySalesCount(String storeId);
}

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
  /// [employeeId] — `null` means **all sales of the store** (OWNER view and
  /// the day closure itself, which covers the whole store); online, the
  /// server-wide summary is fetched. A non-null value scopes the summary to
  /// the sales recorded by that employee.
  Future<DayClosureSummary> computeTodaySummary(String storeId, String? employeeId);

  /// Save a closure record locally.
  ///
  /// Pushed to the backend when online, else enqueued for sync. Returns the
  /// summary stored with the closure: the server's when it answered.
  /// Throws [DayAlreadyClosedException] when the server says the day was
  /// already closed (by another device).
  Future<DayClosureSummary> saveClosureLocally(DayClosure closure);

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

import '../model/dashboard_snapshot.dart';

/// DashboardRepository — port for dashboard data access.
///
/// Story 7.1 — hexagonal architecture: domain defines the contract,
/// data layer implements it (local Drift, optional remote enrichment).
abstract class DashboardRepository {
  Future<DashboardSnapshot> getDashboardSnapshot();
  Future<List<StoreOverview>> getStoreOverviews();
}

import 'package:flutter/foundation.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../domain/model/dashboard_snapshot.dart';
import '../../domain/repository/dashboard_repository.dart';
import '../datasource/local_dashboard_datasource.dart';
import '../datasource/remote_dashboard_datasource.dart';

/// DashboardRepositoryImpl — online-first with offline fallback.
///
/// Story 7.1 — tries backend GET /api/v1/dashboard/summary when online,
/// falls back to local Drift aggregation when offline or on error.
class DashboardRepositoryImpl implements DashboardRepository {
  final LocalDashboardDatasource _localDatasource;
  final RemoteDashboardDatasource _remoteDatasource;
  final ConnectivityService _connectivity;

  DashboardRepositoryImpl(
    this._localDatasource,
    this._remoteDatasource,
    this._connectivity,
  );

  @override
  Future<DashboardSnapshot> getDashboardSnapshot() async {
    try {
      if (await _connectivity.isOnline()) {
        return await _remoteDatasource.getDashboardSummary();
      }
    } catch (e) {
      debugPrint('[Dashboard] Remote fetch failed, falling back to local: $e');
    }
    return _localDatasource.getDashboardSnapshot();
  }

  @override
  Future<List<StoreOverview>> getStoreOverviews() {
    return _localDatasource.getStoreOverviews();
  }
}

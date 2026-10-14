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

  /// GET /dashboard/summary is OWNER-only: an employee session (403 at every
  /// sync otherwise) reads the local aggregation.
  final bool Function() _isOwnerSession;

  DashboardRepositoryImpl(
    this._localDatasource,
    this._remoteDatasource,
    this._connectivity, {
    bool Function()? isOwnerSession,
  }) : _isOwnerSession = isOwnerSession ?? _always;

  static bool _always() => true;

  @override
  Future<DashboardSnapshot> getDashboardSnapshot() async {
    try {
      if (_isOwnerSession() && await _connectivity.isOnline()) {
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

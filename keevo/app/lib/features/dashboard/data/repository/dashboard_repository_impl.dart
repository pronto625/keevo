import 'package:dio/dio.dart';
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

  /// Pause before the single retry of a transient remote failure.
  final Duration _retryDelay;

  DashboardRepositoryImpl(
    this._localDatasource,
    this._remoteDatasource,
    this._connectivity, {
    bool Function()? isOwnerSession,
    Duration retryDelay = const Duration(seconds: 2),
  })  : _isOwnerSession = isOwnerSession ?? _always,
        _retryDelay = retryDelay;

  static bool _always() => true;

  @override
  Future<DashboardSnapshot> getDashboardSnapshot() async {
    try {
      if (_isOwnerSession() && await _connectivity.isOnline()) {
        return await _fetchRemote();
      }
    } catch (e) {
      debugPrint('[Dashboard] Remote fetch failed, falling back to local: $e');
    }
    return _localDatasource.getDashboardSnapshot();
  }

  /// v1s-16-21: right after sign-in the summary races the initial full pull
  /// (busy server, 10 s timeout) — a transient failure is retried once
  /// before falling back to the device's figures, which are still
  /// incomplete at that point.
  Future<DashboardSnapshot> _fetchRemote() async {
    try {
      return await _remoteDatasource.getDashboardSummary();
    } on DioException catch (e) {
      if (!_isTransient(e)) rethrow;
      debugPrint('[Dashboard] Remote fetch failed, retrying once: $e');
      await Future<void>.delayed(_retryDelay);
      return _remoteDatasource.getDashboardSummary();
    }
  }

  static bool _isTransient(DioException e) {
    final status = e.response?.statusCode;
    return status == null || status == 408 || status >= 500;
  }

  @override
  Future<List<StoreOverview>> getStoreOverviews() {
    return _localDatasource.getStoreOverviews();
  }
}

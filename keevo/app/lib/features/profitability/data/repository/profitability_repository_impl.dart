import 'dart:developer' as dev;

import '../../../../core/sync/connectivity_service.dart';
import '../../domain/model/product_profitability_model.dart';
import '../../domain/repository/profitability_repository.dart';
import '../datasource/local_profitability_datasource.dart';
import '../datasource/remote_profitability_datasource.dart';

/// ProfitabilityRepositoryImpl — Backend-First-When-Online strategy.
///
/// Online: fetch from remote and return. (No write-back needed: local Drift
/// is already up-to-date via the sync engine.)
/// Offline: serve from local Drift aggregation queries.
///
/// Story 7.4 — Task 15.4.
class ProfitabilityRepositoryImpl implements ProfitabilityRepository {
  final LocalProfitabilityDatasource _local;
  final RemoteProfitabilityDatasource _remote;
  final ConnectivityService _connectivity;

  ProfitabilityRepositoryImpl({
    required LocalProfitabilityDatasource local,
    required RemoteProfitabilityDatasource remote,
    required ConnectivityService connectivity,
  })  : _local = local,
        _remote = remote,
        _connectivity = connectivity;

  // ── Product list ────────────────────────────────────────────────

  @override
  Future<List<ProductProfitabilityEntry>> getProductProfitability({
    required ProfitabilityParams params,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.fetchProducts(
          from: _fmt(params.from),
          to: _fmt(params.to),
          sort: params.sort.toQueryParam(),
          storeId: params.storeId,
        );
      } catch (e) {
        dev.log('Remote profitability failed, using local: $e',
            name: 'ProfitabilityRepository');
      }
    }
    return _local.getProductProfitability(
      from: params.from,
      to: params.to,
      sort: params.sort.toQueryParam(),
      storeId: params.storeId,
    );
  }

  // ── Product detail ──────────────────────────────────────────────

  @override
  Future<ProductProfitabilityDetail> getProductProfitabilityDetail({
    required String productId,
    required ProfitabilityParams params,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.fetchProductDetail(
          productId: productId,
          from: _fmt(params.from),
          to: _fmt(params.to),
        );
      } catch (e) {
        dev.log('Remote profitability detail failed, using local: $e',
            name: 'ProfitabilityRepository');
      }
    }
    final detail = await _local.getProductProfitabilityDetail(
      productId: productId,
      from: params.from,
      to: params.to,
    );
    if (detail == null) {
      throw Exception('Product not found: $productId');
    }
    return detail;
  }

  // ── Store performance ─────────────────────────────────────────────

  @override
  Future<List<StorePerformanceEntry>> getStorePerformance({
    required StorePerformanceParams params,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        return await _remote.fetchStores(
          from: _fmt(params.from),
          to: _fmt(params.to),
          metric: params.metric.toQueryParam(),
        );
      } catch (e) {
        dev.log('Remote store performance failed, using local: $e',
            name: 'ProfitabilityRepository');
      }
    }
    return _local.getStorePerformance(
      from: params.from,
      to: params.to,
      metric: params.metric.toQueryParam(),
    );
  }

  // ── Helpers ───────────────────────────────────────────────────

  static String _fmt(DateTime dt) =>
      '${dt.year.toString().padLeft(4, '0')}-'
      '${dt.month.toString().padLeft(2, '0')}-'
      '${dt.day.toString().padLeft(2, '0')}';
}

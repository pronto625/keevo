import 'dart:developer' as dev;

import 'package:dio/dio.dart';

import 'package:keevo_core/sync/connectivity_service.dart';
import '../../domain/model/product_profitability_model.dart';
import '../../domain/model/seller_leaderboard_model.dart';
import '../../domain/repository/profitability_repository.dart';
import '../datasource/local_profitability_datasource.dart';
import '../datasource/remote_profitability_datasource.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';

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
        // 13.10b: a premium refusal is shown, not hidden by local data.
        if (ModuleEntitlementException.of(e) != null) rethrow;
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
          storeId: params.storeId,
        );
      } on DioException catch (e) {
        // Re-throw API errors (4xx/5xx) — they are intentional domain
        // responses (e.g. 404 PRODUCT_NOT_FOUND). Only a transport failure
        // (timeout, unreachable host) falls back to the local Drift data.
        if (e.response != null) rethrow;
        dev.log('Remote profitability detail unreachable, using local: $e',
            name: 'ProfitabilityRepository');
      }
    }
    final detail = await _local.getProductProfitabilityDetail(
      productId: productId,
      from: params.from,
      to: params.to,
      storeId: params.storeId,
    );
    if (detail == null) {
      throw Exception('Aucune donnée disponible hors ligne pour ce produit.');
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
        // 13.10b: a premium refusal is shown, not hidden by local data.
        if (ModuleEntitlementException.of(e) != null) rethrow;
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

  // ── Seller leaderboard (v1s-14-2) ─────────────────────────────

  @override
  Future<SellerLeaderboardResult> getSellerLeaderboard({
    required SellerLeaderboardParams params,
  }) async {
    if (await _connectivity.isOnline()) {
      try {
        final entries = await _remote.fetchSellerLeaderboard(
          from: _fmt(params.from),
          to: _fmt(params.to),
          storeId: params.storeId,
        );
        return SellerLeaderboardResult(entries: entries, fromLocal: false);
      } on DioException catch (e) {
        // API answers (4xx/5xx, incl. 403) are rethrown; only a transport
        // failure falls back to the local Drift aggregation.
        if (e.response != null) rethrow;
        dev.log('Remote seller leaderboard unreachable, using local: $e',
            name: 'ProfitabilityRepository');
      }
    }
    final entries = await _local.getSellerLeaderboard(
      from: params.from,
      to: params.to,
      storeId: params.storeId,
    );
    return SellerLeaderboardResult(entries: entries, fromLocal: true);
  }

  // ── Helpers ───────────────────────────────────────────────────

  static String _fmt(DateTime dt) =>
      '${dt.year.toString().padLeft(4, '0')}-'
      '${dt.month.toString().padLeft(2, '0')}-'
      '${dt.day.toString().padLeft(2, '0')}';
}

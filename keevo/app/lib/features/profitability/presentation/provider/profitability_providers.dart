import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/di/core_providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_profitability_datasource.dart';
import '../../data/datasource/remote_profitability_datasource.dart';
import '../../data/repository/profitability_repository_impl.dart';
import '../../domain/model/product_profitability_model.dart';
import '../../domain/model/seller_leaderboard_model.dart';
import '../../domain/repository/profitability_repository.dart';
import '../widget/period_filter_chips.dart';

export '../../domain/model/product_profitability_model.dart'
    show
        ProfitabilityParams,
        StorePerformanceParams,
        SortOption,
        RankingMetric,
        ProductProfitabilityEntry,
        ProductProfitabilityDetail,
        StorePerformanceEntry,
        DailyMarginEntry;
export '../../domain/model/seller_leaderboard_model.dart'
    show
        SellerLeaderboardEntry,
        SellerLeaderboardParams,
        SellerLeaderboardResult;

// ── Infrastructure providers ──────────────────────────────────────────────────

final _localProfitabilityDatasourceProvider =
    Provider<LocalProfitabilityDatasource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalProfitabilityDatasource(db);
});

final _remoteProfitabilityDatasourceProvider =
    Provider<RemoteProfitabilityDatasource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteProfitabilityDatasource(dio);
});

final profitabilityRepositoryProvider =
    Provider<ProfitabilityRepository>((ref) {
  return ProfitabilityRepositoryImpl(
    local: ref.watch(_localProfitabilityDatasourceProvider),
    remote: ref.watch(_remoteProfitabilityDatasourceProvider),
    connectivity: ref.watch(connectivityServiceProvider),
  );
});

// ── State providers ───────────────────────────────────────────────────────────

/// Selected period — shared between Rentabilité + Boutiques tabs (session-only).
/// Read the concrete window with `.range()` at build time.
final selectedProfitabilityPeriodProvider =
    StateProvider<PeriodSelection>((ref) => PeriodSelection.last30);

/// Store filter of the profitability list, carried to the product detail
/// (the detail route only has the product id). null = all stores.
final profitabilityStoreFilterProvider = StateProvider<String?>((_) => null);

/// Selected sort option for product profitability list.
final selectedSortProvider = StateProvider<SortOption>(
  (_) => SortOption.marginPctDesc,
);

/// Selected ranking metric for store performance.
final selectedMetricProvider = StateProvider<RankingMetric>(
  (_) => RankingMetric.ca,
);

// ── Reactive data providers ───────────────────────────────────────────────────

// ── Local sort / ranking (Strategy mirror of the backend) ────────────────────

/// Sorts [entries] by [sort] without touching the data source (AC2: a sort
/// chip tap is local, no API call). Mirrors the backend strategies:
/// `marginPctDesc` keeps loss-making products last (AC1).
List<ProductProfitabilityEntry> applyProductSort(
  List<ProductProfitabilityEntry> entries,
  SortOption sort,
) {
  final sorted = List<ProductProfitabilityEntry>.from(entries);
  switch (sort) {
    case SortOption.marginXafDesc:
      sorted.sort((a, b) => b.grossMarginXaf.compareTo(a.grossMarginXaf));
    case SortOption.caDesc:
      sorted.sort((a, b) => b.totalRevenue.compareTo(a.totalRevenue));
    case SortOption.unitsDesc:
      sorted.sort((a, b) => b.unitsSold.compareTo(a.unitsSold));
    case SortOption.marginPctDesc:
      sorted.sort((a, b) {
        final byLoss = (a.isLoss ? 1 : 0).compareTo(b.isLoss ? 1 : 0);
        if (byLoss != 0) return byLoss;
        return b.marginPercent.compareTo(a.marginPercent);
      });
  }
  return sorted;
}

/// Re-ranks [stores] by [metric] locally (AC5: the metric toggle re-ranks
/// immediately, no API call) and renumbers `rank` from 1.
List<StorePerformanceEntry> rankStoresBy(
  List<StorePerformanceEntry> stores,
  RankingMetric metric,
) {
  final sorted = List<StorePerformanceEntry>.from(stores);
  switch (metric) {
    case RankingMetric.salesCount:
      sorted.sort((a, b) => b.salesCount.compareTo(a.salesCount));
    case RankingMetric.avgBasket:
      sorted.sort((a, b) => b.averageBasket.compareTo(a.averageBasket));
    case RankingMetric.ca:
      sorted.sort((a, b) => b.totalRevenue.compareTo(a.totalRevenue));
  }
  return [
    for (final (i, s) in sorted.indexed)
      StorePerformanceEntry(
        rank: i + 1,
        storeId: s.storeId,
        storeName: s.storeName,
        totalRevenue: s.totalRevenue,
        salesCount: s.salesCount,
        averageBasket: s.averageBasket,
        topProductName: s.topProductName,
        deltaPercent: s.deltaPercent,
      ),
  ];
}

/// Product profitability list provider (family by params).
///
/// Client-side sort is applied after retrieving data so that mock-based
/// tests (which return unsorted data) and the UI both behave consistently.
/// The pages fetch with the default sort and re-sort locally with
/// [applyProductSort] so that a sort change never re-fetches.
final productProfitabilityProvider = FutureProvider.autoDispose
    .family<List<ProductProfitabilityEntry>, ProfitabilityParams>(
        (ref, params) async {
  final repo = ref.watch(profitabilityRepositoryProvider);
  final raw = await repo.getProductProfitability(params: params);
  return applyProductSort(raw, params.sort);
});

/// Product profitability detail provider (family by productId + params).
final productProfitabilityDetailProvider = FutureProvider.autoDispose
    .family<ProductProfitabilityDetail, ({String productId, ProfitabilityParams params})>(
        (ref, args) async {
  final repo = ref.watch(profitabilityRepositoryProvider);
  return repo.getProductProfitabilityDetail(
    productId: args.productId,
    params: args.params,
  );
});

/// Store performance ranking provider (family by params).
final storePerformanceProvider = FutureProvider.autoDispose
    .family<List<StorePerformanceEntry>, StorePerformanceParams>(
        (ref, params) async {
  final repo = ref.watch(profitabilityRepositoryProvider);
  return repo.getStorePerformance(params: params);
});

// ── Seller leaderboard (FR44, v1s-14-2 — OWNER only) ─────────────────────────

/// Period of the « Équipe » tab — its own state so the Rentabilité/Boutiques
/// tabs keep their 30-day default. Defaults to today (Story 4.6).
final selectedLeaderboardPeriodProvider =
    StateProvider<PeriodSelection>((ref) => PeriodSelection.today);

/// Ranking metric of the « Équipe » tab (default CA).
final selectedLeaderboardMetricProvider = StateProvider<RankingMetric>(
  (_) => RankingMetric.ca,
);

/// Ranks [entries] by [metric] locally (the toggle never re-fetches) and
/// renumbers `rank` from 1. Same order as the backend strategies: metric
/// desc, then revenue desc, then name asc (case-insensitive), then sellerId.
List<SellerLeaderboardEntry> rankSellersBy(
  List<SellerLeaderboardEntry> entries,
  RankingMetric metric,
) {
  int primary(SellerLeaderboardEntry a, SellerLeaderboardEntry b) =>
      switch (metric) {
        RankingMetric.salesCount => b.salesCount.compareTo(a.salesCount),
        RankingMetric.avgBasket => b.averageBasket.compareTo(a.averageBasket),
        RankingMetric.ca => b.totalRevenue.compareTo(a.totalRevenue),
      };
  final sorted = List<SellerLeaderboardEntry>.from(entries)
    ..sort((a, b) {
      var c = primary(a, b);
      if (c != 0) return c;
      c = b.totalRevenue.compareTo(a.totalRevenue);
      if (c != 0) return c;
      c = a.sellerName.toLowerCase().compareTo(b.sellerName.toLowerCase());
      if (c != 0) return c;
      return a.sellerId.compareTo(b.sellerId);
    });
  return [for (final (i, e) in sorted.indexed) e.withRank(i + 1)];
}

/// Seller leaderboard provider (family by period + store). Unranked data;
/// the page ranks with [rankSellersBy] and shows the offline banner when
/// `fromLocal` is true.
final sellerLeaderboardProvider = FutureProvider.autoDispose
    .family<SellerLeaderboardResult, SellerLeaderboardParams>(
        (ref, params) async {
  final repo = ref.watch(profitabilityRepositoryProvider);
  return repo.getSellerLeaderboard(params: params);
});

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_profitability_datasource.dart';
import '../../data/datasource/remote_profitability_datasource.dart';
import '../../data/repository/profitability_repository_impl.dart';
import '../../domain/model/product_profitability_model.dart';
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

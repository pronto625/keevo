import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_profitability_datasource.dart';
import '../../data/datasource/remote_profitability_datasource.dart';
import '../../data/repository/profitability_repository_impl.dart';
import '../../domain/model/product_profitability_model.dart';
import '../../domain/repository/profitability_repository.dart';

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
final selectedProfitabilityPeriodProvider =
    StateProvider<({DateTime from, DateTime to})>((ref) {
  final now = DateTime.now();
  final to = DateTime(now.year, now.month, now.day);
  final from = to.subtract(const Duration(days: 29));
  return (from: from, to: to);
});

/// Selected sort option for product profitability list.
final selectedSortProvider = StateProvider<SortOption>(
  (_) => SortOption.marginPctDesc,
);

/// Selected ranking metric for store performance.
final selectedMetricProvider = StateProvider<RankingMetric>(
  (_) => RankingMetric.ca,
);

// ── Reactive data providers ───────────────────────────────────────────────────

/// Product profitability list provider (family by params).
///
/// Client-side sort is applied after retrieving data so that mock-based
/// tests (which return unsorted data) and the UI both behave consistently.
final productProfitabilityProvider = FutureProvider.autoDispose
    .family<List<ProductProfitabilityEntry>, ProfitabilityParams>(
        (ref, params) async {
  final repo = ref.watch(profitabilityRepositoryProvider);
  final raw = await repo.getProductProfitability(params: params);
  final sorted = List<ProductProfitabilityEntry>.from(raw);
  switch (params.sort) {
    case SortOption.marginXafDesc:
      sorted.sort((a, b) => b.grossMarginXaf.compareTo(a.grossMarginXaf));
    case SortOption.caDesc:
      sorted.sort((a, b) => b.totalRevenue.compareTo(a.totalRevenue));
    case SortOption.unitsDesc:
      sorted.sort((a, b) => b.unitsSold.compareTo(a.unitsSold));
    case SortOption.marginPctDesc:
      sorted.sort((a, b) => b.marginPercent.compareTo(a.marginPercent));
  }
  return sorted;
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

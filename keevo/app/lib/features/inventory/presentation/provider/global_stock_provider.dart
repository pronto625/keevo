import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/settable_notifier.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_multi_store_stock_datasource.dart';
import '../../data/datasource/remote_multi_store_stock_datasource.dart';
import '../../data/repository/multi_store_stock_repository_impl.dart';
import '../../domain/model/store_product_stock_model.dart';
import '../../domain/model/store_stock_summary_model.dart';
import '../../domain/repository/multi_store_stock_repository.dart';

part 'global_stock_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localMultiStoreStockDsProvider =
    Provider<LocalMultiStoreStockDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalMultiStoreStockDataSource(db);
});

final remoteMultiStoreStockDsProvider =
    Provider<RemoteMultiStoreStockDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteMultiStoreStockDataSource(dio: dio);
});

final multiStoreStockRepositoryProvider =
    Provider<MultiStoreStockRepository>((ref) {
  return MultiStoreStockRepositoryImpl(
    local: ref.watch(localMultiStoreStockDsProvider),
    remote: ref.watch(remoteMultiStoreStockDsProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

// ── Overview (all stores) ────────────────────────────────────────────────────

@riverpod
Future<List<StoreStockSummaryModel>> globalStockOverview(
    Ref ref) {
  return ref.watch(multiStoreStockRepositoryProvider).getOverview();
}

// ── Store detail (keyed by storeId) ─────────────────────────────────────────

@riverpod
Future<List<StoreProductStockModel>> storeStockDetail(
    Ref ref, String storeId) {
  return ref
      .watch(multiStoreStockRepositoryProvider)
      .getStoreStockDetail(storeId, sortLowFirst: true);
}

// ── Search (local-only, debounced in UI) ─────────────────────────────────────

// autoDispose: leaving the stock overview resets the query, like its other
// filters (the page's search field starts empty again).
final stockSearchQueryProvider =
    NotifierProvider.autoDispose<SettableNotifier<String>, String>(
  () => SettableNotifier(''),
);

@riverpod
Future<List<StoreProductStockModel>> stockSearchResults(
    Ref ref) {
  final query = ref.watch(stockSearchQueryProvider);
  if (query.trim().length < 2) return Future.value([]);
  return ref
      .watch(multiStoreStockRepositoryProvider)
      .searchAcrossStores(query);
}

// ── Navigation highlight (search result tap → expand matching card) ──────────

/// Set to a storeId when a search result tile is tapped.
/// The matching [StoreStockCard] will auto-expand and scroll into view, then
/// this is cleared back to null.
final highlightedStoreIdProvider =
    NotifierProvider<SettableNotifier<String?>, String?>(
  () => SettableNotifier(null),
);

/// Set to true when navigating from the dashboard low-stock badge.
/// [GlobalStockOverviewPage] writes this in initState; [_ProductList] reads it
/// when loading stock data; autoDispose resets it once the page is left.
final showLowStockOnlyProvider =
    NotifierProvider.autoDispose<SettableNotifier<bool>, bool>(
  () => SettableNotifier(false),
);



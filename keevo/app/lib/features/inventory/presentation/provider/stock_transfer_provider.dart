import 'package:keevo_core/di/core_providers.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../data/datasource/local_stock_transfer_datasource.dart';
import '../../data/datasource/remote_stock_transfer_datasource.dart';
import '../../data/repository/stock_transfer_repository_impl.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../../domain/repository/stock_transfer_repository.dart';

part 'stock_transfer_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localStockTransferDsProvider =
    Provider<LocalStockTransferDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalStockTransferDataSource(db);
});

final remoteStockTransferDsProvider =
    Provider<RemoteStockTransferDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteStockTransferDataSource(dio: dio);
});

final stockTransferRepositoryProvider =
    Provider<StockTransferRepository>((ref) {
  return StockTransferRepositoryImpl(
    local: ref.watch(localStockTransferDsProvider),
    remote: ref.watch(remoteStockTransferDsProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
  );
});

// ── Transfer history (paged, keyed by store + status) ─────────────────────

const kTransferHistoryPageSize = 20;

/// Loaded transfers of the history list (v1s-16-23).
class TransferHistoryState {
  final List<StockTransferModel> items;
  final bool hasMore;

  const TransferHistoryState({required this.items, required this.hasMore});
}

/// Transfer history, newest first, paged by offset.
///
/// [storeId] keeps transfers where the store is source OR destination,
/// [status] one status — both applied by the query (backend + local), never
/// on the client after a page limit (v1s-16-23). The first page loads on
/// build; [loadMore] appends the next one, never duplicating an id.
/// Offline-first: each page is read back from local storage (the repository
/// only uses the remote page to refresh the cache).
@riverpod
class TransferHistory extends _$TransferHistory {
  bool _loadingMore = false;

  /// Next page index to request.
  int _nextPage = 0;

  /// Bumped on every (re)build: a loadMore started before a refresh must not
  /// append to the refreshed list.
  int _generation = 0;

  bool get isLoadingMore => _loadingMore;

  @override
  Future<TransferHistoryState> build({String? storeId, String? status}) async {
    _generation++;
    _loadingMore = false;
    _nextPage = 0;
    final page = await _fetch(0);
    _nextPage = 1;
    return TransferHistoryState(
      items: _dedup(const [], page),
      hasMore: page.length >= kTransferHistoryPageSize,
    );
  }

  /// Appends the next page. Errors propagate to the caller (footer
  /// « Réessayer ») and leave the loaded list untouched.
  Future<void> loadMore() async {
    // A failed rebuild keeps the previous value: never page on from it.
    if (state.hasError) return;
    final current = state.valueOrNull;
    if (current == null || !current.hasMore || _loadingMore) return;
    final generation = _generation;
    final pageIndex = _nextPage;
    _loadingMore = true;
    try {
      final page = await _fetch(pageIndex);
      // A refresh happened while loading: this page belongs to a list that
      // no longer exists.
      if (generation != _generation || !identical(state.valueOrNull, current)) {
        return;
      }
      _nextPage = pageIndex + 1;
      state = AsyncData(TransferHistoryState(
        items: _dedup(current.items, page),
        hasMore: page.length >= kTransferHistoryPageSize,
      ));
    } finally {
      // A rebuild already reset the flag (and may own a newer loadMore).
      if (generation == _generation) _loadingMore = false;
    }
  }

  List<StockTransferModel> _dedup(
      List<StockTransferModel> current, List<StockTransferModel> page) {
    final seen = current.map((t) => t.id).toSet();
    return [...current, ...page.where((t) => seen.add(t.id))];
  }

  Future<List<StockTransferModel>> _fetch(int page) async {
    final transfers = await ref.read(stockTransferRepositoryProvider).getHistory(
          storeId: storeId,
          status: status,
          page: page,
          pageSize: kTransferHistoryPageSize,
        );

    // Enrich denormalized display fields from local providers (spec AC5).
    // Use ref.read (not ref.watch) to avoid AutoDispose race conditions: watching
    // an AutoDisposeFutureProvider inside an async body can cause
    // "Bad state: Future already completed" when the provider auto-disposes
    // mid-await (e.g. when the form bottom sheet closes its listeners).
    final stores = await ref.read(storeListNotifierProvider.future);
    final products = await ref.read(productListForPickerProvider.future);
    final storeMap = {for (final s in stores) s.id: s.name};
    final productMap = {for (final p in products) p.id: p.name};

    return transfers
        .map((t) => t.copyWith(
              sourceStoreName: t.sourceStoreName.isNotEmpty
                  ? t.sourceStoreName
                  : (storeMap[t.sourceStoreId] ?? ''),
              destinationStoreName: t.destinationStoreName.isNotEmpty
                  ? t.destinationStoreName
                  : (storeMap[t.destinationStoreId] ?? ''),
              productName: t.productName.isNotEmpty
                  ? t.productName
                  : (productMap[t.productId] ?? ''),
            ))
        .toList();
  }
}

// ── Execute transfer notifier ─────────────────────────────────────────────

@riverpod
class ExecuteTransferNotifier extends _$ExecuteTransferNotifier {
  @override
  FutureOr<StockTransferModel?> build() => null;

  Future<StockTransferModel> execute({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(() =>
        ref.read(stockTransferRepositoryProvider).executeTransfer(
              sourceStoreId: sourceStoreId,
              destinationStoreId: destinationStoreId,
              productId: productId,
              variantId: variantId,
              quantity: quantity,
              notes: notes,
            ));
    state = result;
    if (result.hasError) throw result.error!;
    return result.value!;
  }
}

// ── Complete transfer notifier ────────────────────────────────────────────

@riverpod
class CompleteTransferNotifier extends _$CompleteTransferNotifier {
  @override
  FutureOr<StockTransferModel?> build() => null;

  Future<StockTransferModel> complete(String transferId) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(() =>
        ref.read(stockTransferRepositoryProvider).completeTransfer(transferId));
    state = result;
    if (result.hasError) throw result.error!;
    return result.value!;
  }
}

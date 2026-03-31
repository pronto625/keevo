import '../../../../core/sync/sync_service.dart';
import '../../domain/model/store_product_stock_model.dart';
import '../../domain/model/store_stock_summary_model.dart';
import '../../domain/repository/multi_store_stock_repository.dart';
import '../datasource/local_multi_store_stock_datasource.dart';
import '../datasource/remote_multi_store_stock_datasource.dart';

/// MultiStoreStockRepositoryImpl — remote-first with local Drift fallback.
///
/// Strategy per method:
///   - [getOverview]: remote first, fall back to local aggregate query.
///   - [getStoreStockDetail]: remote first, upsert cache, fall back to local.
///   - [searchAcrossStores]: local only (instant debounced search).
/// Story 3.2.
class MultiStoreStockRepositoryImpl implements MultiStoreStockRepository {
  final LocalMultiStoreStockDataSource _local;
  final RemoteMultiStoreStockDataSource _remote;
  final SyncService _syncService;

  MultiStoreStockRepositoryImpl({
    required LocalMultiStoreStockDataSource local,
    required RemoteMultiStoreStockDataSource remote,
    required SyncService syncService,
  })  : _local = local,
        _remote = remote,
        _syncService = syncService;

  @override
  Future<List<StoreStockSummaryModel>> getOverview() async {
    // Guard: if offline ops are pending, return local data to prevent
    // stale remote values from overwriting correct offline decrements.
    if (await _syncService.hasPendingOperations()) {
      return _local.getStoreOverviews();
    }
    try {
      return await _remote.getOverview();
    } catch (_) {
      return _local.getStoreOverviews();
    }
  }

  @override
  Future<List<StoreProductStockModel>> getStoreStockDetail(
    String storeId, {
    int page = 0,
    int size = 25,
    bool sortLowFirst = true,
    bool lowOnly = false,
  }) async {
    // Guard: if offline ops are pending, return local data to prevent
    // stale remote values from overwriting correct offline decrements.
    if (await _syncService.hasPendingOperations()) {
      return _local.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
      );
    }
    try {
      final result = await _remote.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
        lowOnly: lowOnly,
      );
      await _local.upsertStockLevels(storeId, result.content);
      return result.content;
    } catch (_) {
      return _local.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
      );
    }
  }

  @override
  Future<List<StoreProductStockModel>> searchAcrossStores(String query) {
    return _local.searchAcrossStores(query);
  }
}

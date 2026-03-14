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

  MultiStoreStockRepositoryImpl({
    required LocalMultiStoreStockDataSource local,
    required RemoteMultiStoreStockDataSource remote,
  })  : _local = local,
        _remote = remote;

  @override
  Future<List<StoreStockSummaryModel>> getOverview() async {
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
  }) async {
    try {
      final result = await _remote.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
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

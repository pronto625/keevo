import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/sync/sync_service.dart';
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
    // Guard: while a pending or held operation (Story 13.8b: a sale held
    // because its module is off keeps its decrement) changed some stock
    // locally, the per-store totals are the local ones (server stock + local
    // deltas). Other queued operations (a client edit…) do not matter here.
    if ((await _syncService.unsyncedStockKeys()).isNotEmpty) {
      return _local.getStoreOverviews();
    }
    try {
      return await _remote.getOverview();
    } catch (e) {
      // 13.10b: a module refusal is shown, never hidden by local data.
      if (ModuleEntitlementException.of(e) != null) rethrow;
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
    // Story 13.8b: the rows of this store changed by a pending or held
    // operation keep their local quantity (server stock + local delta); the
    // others are refreshed from the server.
    final protectedKeys = (await _syncService.unsyncedStockKeys())
        .where((k) => k.endsWith('|$storeId'))
        .toSet();
    try {
      final result = await _remote.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
        lowOnly: lowOnly,
      );
      if (protectedKeys.isEmpty) {
        await _local.upsertStockLevels(storeId, result.content);
        return result.content;
      }
      await _local.upsertStockLevels(
          storeId,
          result.content
              .where((e) => !protectedKeys
                  .contains(unsyncedStockKey(e.productId, storeId)))
              .toList());
      return _local.getStoreStockDetail(
        storeId,
        page: page,
        size: size,
        sortLowFirst: sortLowFirst,
      );
    } catch (e) {
      // 13.10b: a module refusal is shown, never hidden by local data.
      if (ModuleEntitlementException.of(e) != null) rethrow;
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

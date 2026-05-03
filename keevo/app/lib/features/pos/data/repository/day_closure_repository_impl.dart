import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/day_closure_model.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';
import '../../domain/repository/day_closure_repository.dart';
import '../datasource/local_day_closure_datasource.dart';
import '../datasource/remote_day_closure_datasource.dart';

/// DayClosureRepositoryImpl — Backend-first implementation of DayClosureRepository.
///
/// Online: POST to backend first, then save locally (synced: true).
/// Offline: save locally (synced: false) + enqueue in sync_queue.
/// Story 4.4 + 5.1.
class DayClosureRepositoryImpl implements DayClosureRepository {
  final LocalDayClosureDataSource _localDataSource;
  final RemoteDayClosureDataSource _remoteDataSource;
  final ConnectivityService _connectivity;
  final SyncService _syncService;

  DayClosureRepositoryImpl({
    required LocalDayClosureDataSource localDataSource,
    required RemoteDayClosureDataSource remoteDataSource,
    required ConnectivityService connectivity,
    required SyncService syncService,
  })  : _localDataSource = localDataSource,
        _remoteDataSource = remoteDataSource,
        _connectivity = connectivity,
        _syncService = syncService;

  @override
  Future<DayClosureSummary> computeTodaySummary(
      String storeId, String? employeeId) async {
    // G2: For owner (no employeeId filter) fetch all sales from the server so
    // that sales created on other devices are included in the summary.
    if (employeeId == null && await _connectivity.isOnline()) {
      try {
        final filter = SalesHistoryFilter.today(storeId: storeId);
        final sales = await _remoteDataSource.fetchSalesHistory(filter);
        return _computeSummaryFromSales(sales);
      } catch (_) {
        // Remote unreachable — fall through to local DB
      }
    }
    return _localDataSource.computeTodaySummary(storeId, employeeId);
  }

  DayClosureSummary _computeSummaryFromSales(List<Sale> sales) {
    final completed =
        sales.where((s) => s.status == 'COMPLETED').toList();

    final totalRevenue =
        completed.fold<int>(0, (sum, s) => sum + s.totalAmount);
    final cashAmount = completed
        .where((s) => s.paymentMode == PaymentModeEnum.cash)
        .fold<int>(0, (sum, s) => sum + s.totalAmount);
    final momoAmount = totalRevenue - cashAmount;

    final qtys = <String, int>{};
    final names = <String, String>{};
    final revenues = <String, int>{};
    for (final sale in completed) {
      for (final item in sale.items) {
        qtys[item.productId] = (qtys[item.productId] ?? 0) + item.quantity;
        names[item.productId] = item.productName;
        revenues[item.productId] =
            (revenues[item.productId] ?? 0) + item.subtotal;
      }
    }

    String? topProductId;
    String? topProductName;
    int topProductQty = 0;
    int topProductRevenue = 0;
    for (final entry in qtys.entries) {
      if (entry.value > topProductQty) {
        topProductId = entry.key;
        topProductQty = entry.value;
        topProductName = names[entry.key];
        topProductRevenue = revenues[entry.key] ?? 0;
      }
    }

    return DayClosureSummary(
      totalSales: completed.length,
      totalRevenue: totalRevenue,
      cashAmount: cashAmount,
      momoAmount: momoAmount,
      topProductId: topProductId,
      topProductName: topProductName,
      topProductQty: topProductQty,
      topProductRevenue: topProductRevenue,
      pendingSalesCount: 0,
      pendingSalesTotal: 0,
    );
  }

  @override
  Future<void> saveClosureLocally(DayClosure closure) async {
    if (await _connectivity.isOnline()) {
      try {
        await _remoteDataSource.pushClosure(closure);
        await _localDataSource.insertClosure(closure, synced: true);
      } catch (_) {
        await _localDataSource.insertClosure(closure, synced: false);
        await _syncService.queueOperation(
          operation: 'CREATE_DAY_CLOSURE',
          payload: {'storeId': closure.storeId, 'id': closure.id},
          entityId: closure.id,
        );
      }
    } else {
      await _localDataSource.insertClosure(closure, synced: false);
      await _syncService.queueOperation(
        operation: 'CREATE_DAY_CLOSURE',
        payload: {'storeId': closure.storeId, 'id': closure.id},
        entityId: closure.id,
      );
    }
  }

  @override
  Future<bool> hasClosureToday(String storeId) {
    return _localDataSource.hasClosureToday(storeId);
  }

  @override
  Future<DayClosure?> getLastClosure(String storeId) {
    return _localDataSource.getLastClosure(storeId);
  }

  @override
  Future<int> getTodaySalesCount(String storeId) {
    return _localDataSource.getTodaySalesCount(storeId);
  }

  @override
  Future<int> getSalesCountAfter(String storeId, DateTime after) {
    return _localDataSource.getSalesCountAfter(storeId, after);
  }
}

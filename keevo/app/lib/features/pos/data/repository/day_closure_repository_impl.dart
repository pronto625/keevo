import 'dart:async';

import 'package:dio/dio.dart';

import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/exception/day_already_closed_exception.dart';
import '../../domain/model/day_closure_model.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';
import '../../domain/repository/day_closure_repository.dart';
import '../datasource/local_day_closure_datasource.dart';
import '../datasource/remote_day_closure_datasource.dart';

/// DayClosureRepositoryImpl — Backend-first implementation of DayClosureRepository.
///
/// Online: POST to backend first, then save locally (synced: true) with the
/// server's summary. Server unreachable or offline: save locally
/// (synced: false) + enqueue in sync_queue. A server refusal is surfaced.
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
        // Same window as the offline path: everything since the last closure
        // (or since midnight when the store was never closed).
        final now = DateTime.now();
        final lastClosure = await _localDataSource.getLastClosure(storeId);
        final effectiveStart =
            lastClosure?.closedAt ?? DateTime(now.year, now.month, now.day);
        final filter = SalesHistoryFilter.custom(
            storeId: storeId, from: effectiveStart, to: now);
        final sales = await _remoteDataSource.fetchAllSalesHistory(filter);
        return _computeSummaryFromSales(sales
            .where((s) => s.occurredAt.isAfter(effectiveStart))
            .toList());
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

    final pending =
        sales.where((s) => s.status == 'PENDING_VALIDATION').toList();

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
      pendingSalesCount: pending.length,
      pendingSalesTotal:
          pending.fold<int>(0, (sum, s) => sum + s.totalAmount),
    );
  }

  @override
  Future<DayClosureSummary> saveClosureLocally(DayClosure closure) async {
    if (await _connectivity.isOnline()) {
      try {
        final serverSummary = await _remoteDataSource.pushClosure(closure);
        // The server's summary is the one of record (it also counts sales
        // made offline before the previous closure but synced after it).
        final stored = serverSummary == null
            ? closure
            : DayClosure(
                id: closure.id,
                storeId: closure.storeId,
                actorId: closure.actorId,
                closedAt: closure.closedAt,
                summary: serverSummary,
                isAutomatic: closure.isAutomatic,
                synced: true,
              );
        await _localDataSource.insertClosure(stored, synced: true);
        return stored.summary;
      } on DioException catch (e) {
        if (e.response?.statusCode == 409) {
          // Another device already closed the day: nothing to store or queue,
          // and the pull brings that closure to this device.
          unawaited(_syncService.pull().catchError((_) {}));
          throw DayAlreadyClosedException();
        }
        // Any other answer from the server is a refusal the user must see;
        // only an unreachable server falls back to the offline queue.
        if (e.response != null) rethrow;
      }
    }
    await _localDataSource.insertClosure(closure, synced: false);
    await _syncService.queueOperation(
      operation: 'CREATE_DAY_CLOSURE',
      payload: _closurePayload(closure),
      entityId: closure.id,
    );
    return closure.summary;
  }

  /// The server reuses the local id and closes the day the closure was made
  /// (not the day the queued operation is finally pushed).
  Map<String, dynamic> _closurePayload(DayClosure closure) => {
        'storeId': closure.storeId,
        'id': closure.id,
        'closedAt': closure.closedAt.toUtc().toIso8601String(),
      };

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

}

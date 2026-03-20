import '../../../../core/sync/connectivity_service.dart';
import '../../../../core/sync/sync_service.dart';
import '../../domain/model/day_closure_model.dart';
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
      String storeId, String? employeeId) {
    return _localDataSource.computeTodaySummary(storeId, employeeId);
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
}

import '../../domain/model/day_closure_model.dart';
import '../../domain/repository/day_closure_repository.dart';
import '../datasource/local_day_closure_datasource.dart';
import '../datasource/remote_day_closure_datasource.dart';

/// DayClosureRepositoryImpl — Implementation of DayClosureRepository.
///
/// Offline-first: reads from local Drift, syncs to backend via queue.
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DayClosureRepositoryImpl implements DayClosureRepository {
  final LocalDayClosureDataSource _localDataSource;
  final RemoteDayClosureDataSource _remoteDataSource;

  DayClosureRepositoryImpl({
    required LocalDayClosureDataSource localDataSource,
    required RemoteDayClosureDataSource remoteDataSource,
  })  : _localDataSource = localDataSource,
        _remoteDataSource = remoteDataSource;

  @override
  Future<DayClosureSummary> computeTodaySummary(
      String storeId, String? employeeId) {
    return _localDataSource.computeTodaySummary(storeId, employeeId);
  }

  @override
  Future<void> saveClosureLocally(DayClosure closure) {
    return _localDataSource.insertClosure(closure);
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

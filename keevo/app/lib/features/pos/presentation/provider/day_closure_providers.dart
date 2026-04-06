import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_day_closure_datasource.dart';
import '../../data/datasource/remote_day_closure_datasource.dart';
import '../../data/repository/day_closure_repository_impl.dart';
import '../../domain/model/day_closure_model.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';
import '../../domain/repository/day_closure_repository.dart';
import '../../domain/usecase/close_day_usecase.dart';
import '../../domain/usecase/get_sales_history_usecase.dart';
import 'pos_providers.dart';

/// DayCloseButtonState — visual state of the day close button.
enum DayCloseButtonState {
  /// Button is available — user can close the day
  available,

  /// Day has already been closed — button disabled
  closed,

  /// No sales today — button available but no badge
  noSales,
}

// ─────────────────────────────────────────────────────────────────────────────
// Datasources

final localDayClosureDataSourceProvider = Provider<LocalDayClosureDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalDayClosureDataSource(db);
});

final remoteDayClosureDataSourceProvider = Provider<RemoteDayClosureDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteDayClosureDataSource(dio);
});

// ─────────────────────────────────────────────────────────────────────────────
// Repository

final dayClosureRepositoryProvider = Provider<DayClosureRepository>((ref) {
  final localDataSource = ref.watch(localDayClosureDataSourceProvider);
  final remoteDataSource = ref.watch(remoteDayClosureDataSourceProvider);
  return DayClosureRepositoryImpl(
    localDataSource: localDataSource,
    remoteDataSource: remoteDataSource,
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// Use cases

final closeDayUseCaseProvider = Provider<CloseDayUseCase>((ref) {
  final repository = ref.watch(dayClosureRepositoryProvider);
  return CloseDayUseCase(repository: repository);
});

final getSalesHistoryUseCaseProvider = Provider<GetSalesHistoryUseCase>((ref) {
  final repository = ref.watch(saleRepositoryProvider);
  return GetSalesHistoryUseCase(repository: repository);
});

// ─────────────────────────────────────────────────────────────────────────────
// State providers

/// Unclosed completed sales count for badge display.
///
/// If a closure was already done today, shows only sales made after it.
final todaySalesCountProvider = FutureProvider.family<int, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  final now = DateTime.now();
  final startOfDay = DateTime(now.year, now.month, now.day);

  final lastClosure = await repository.getLastClosure(storeId);
  if (lastClosure != null && lastClosure.closedAt.isAfter(startOfDay)) {
    return repository.getSalesCountAfter(storeId, lastClosure.closedAt);
  }
  return repository.getTodaySalesCount(storeId);
});

/// Day close button state: available, closed, or noSales.
///
/// If a closure exists today but new sales were made after it,
/// the button becomes available again so the user can close again.
final dayClosureStateProvider = FutureProvider.family<DayCloseButtonState, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  final now = DateTime.now();
  final startOfDay = DateTime(now.year, now.month, now.day);

  // Get last closure
  final lastClosure = await repository.getLastClosure(storeId);
  final hasClosureToday =
      lastClosure != null && lastClosure.closedAt.isAfter(startOfDay);

  if (hasClosureToday) {
    // Check for new completed sales AFTER the closure
    final newSalesCount =
        await repository.getSalesCountAfter(storeId, lastClosure!.closedAt);
    if (newSalesCount > 0) {
      return DayCloseButtonState.available;
    }
    return DayCloseButtonState.closed;
  }

  // No closure today — check if there are any sales
  final salesCount = await repository.getTodaySalesCount(storeId);
  if (salesCount == 0) {
    return DayCloseButtonState.noSales;
  }

  return DayCloseButtonState.available;
});

/// Today's summary for the bottom sheet preview.
final todaySummaryProvider = FutureProvider.family<DayClosureSummary, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  return repository.computeTodaySummary(storeId, null);
});

/// Last closure for the store — used to display the period label in Reports.
final lastClosureProvider = FutureProvider.family<DayClosure?, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  return repository.getLastClosure(storeId);
});

/// Sales history with date filter.
final salesHistoryProvider = FutureProvider.family<List<Sale>, SalesHistoryFilter>((ref, filter) async {
  final useCase = ref.watch(getSalesHistoryUseCaseProvider);
  return useCase.execute(filter);
});

// ─────────────────────────────────────────────────────────────────────────────
// Notifiers

/// CloseDayNotifier — handles the day closure action.
class CloseDayNotifier extends StateNotifier<AsyncValue<DayClosureSummary?>> {
  final CloseDayUseCase _closeDayUseCase;
  final Ref _ref;

  CloseDayNotifier(this._closeDayUseCase, this._ref) : super(const AsyncValue.data(null));

  Future<void> closeDay(String storeId, String actorId) async {
    state = const AsyncValue.loading();
    try {
      final summary = await _closeDayUseCase.execute(storeId: storeId, actorId: actorId);
      state = AsyncValue.data(summary);
      // Invalidate related providers to refresh UI
      _ref.invalidate(dayClosureStateProvider);
      _ref.invalidate(todaySalesCountProvider);
      _ref.invalidate(todaySummaryProvider);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
      rethrow;
    }
  }

  void reset() {
    state = const AsyncValue.data(null);
  }
}

final closeDayNotifierProvider = StateNotifierProvider<CloseDayNotifier, AsyncValue<DayClosureSummary?>>((ref) {
  final useCase = ref.watch(closeDayUseCaseProvider);
  return CloseDayNotifier(useCase, ref);
});

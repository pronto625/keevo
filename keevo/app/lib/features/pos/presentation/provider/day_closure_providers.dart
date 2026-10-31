import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/di/core_providers.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_guard.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../reports/presentation/provider/report_history_providers.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
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

/// DayClosureState — visual state of the day-closure UI.
///
/// Drives `_ClosureButton` in `reports_page.dart` (the production day-close
/// button) and the day-close gating in `pos_speed_dial.dart`.
enum DayClosureState {
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

/// Day close button state: available, closed, or noSales.
///
/// The backend accepts a single closure per store and calendar day, so once
/// today is closed the button stays closed until midnight — sales made after
/// the closure are rolled into the next day's closure.
final dayClosureStateProvider = FutureProvider.family<DayClosureState, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  final now = DateTime.now();
  final startOfDay = DateTime(now.year, now.month, now.day);

  final lastClosure = await repository.getLastClosure(storeId);
  if (lastClosure != null && lastClosure.closedAt.isAfter(startOfDay)) {
    return DayClosureState.closed;
  }

  // No closure today — check if there are any sales
  final salesCount = await repository.getTodaySalesCount(storeId);
  if (salesCount == 0) {
    return DayClosureState.noSales;
  }

  return DayClosureState.available;
});

/// Today's summary for the bottom sheet preview.
///
/// Key: `({String storeId, String? employeeId})`.
/// Pass `employeeId: null` for OWNER (all store sales) or
/// the current user's id for EMPLOYEE (scoped to their own sales).
final todaySummaryProvider =
    FutureProvider.family<DayClosureSummary, ({String storeId, String? employeeId})>(
        (ref, args) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  return repository.computeTodaySummary(args.storeId, args.employeeId);
});

/// Last closure for the store — used to display the period label in Reports.
final lastClosureProvider = FutureProvider.family<DayClosure?, String>((ref, storeId) async {
  final repository = ref.watch(dayClosureRepositoryProvider);
  return repository.getLastClosure(storeId);
});

/// Time of the store's last day closure (local cache), or null if none.
///
/// v1s-13-11 (PO decision D3): a COMPLETED sale made at or before this instant
/// is part of a closed day; cancelling/correcting it stays allowed (FR90) but
/// the already sent report is not regenerated, so the sale detail page warns.
final lastClosureAtProvider =
    FutureProvider.family<DateTime?, String>((ref, storeId) async {
  // Derived from [lastClosureProvider] so its invalidations (post-pull sync,
  // close-day) refresh this too.
  return (await ref.watch(lastClosureProvider(storeId).future))?.closedAt;
});

/// Sales history with date filter.
///
/// Uses [autoDispose] so that re-opening the Sales History page always
/// re-fetches from the backend (source of truth) instead of serving the
/// Riverpod-cached result from the previous visit. Without autoDispose an
/// employee would keep seeing a sale as PENDING_VALIDATION even after the
/// owner validated it — until the app was fully restarted.
final salesHistoryProvider =
    FutureProvider.autoDispose.family<List<Sale>, SalesHistoryFilter>(
        (ref, filter) async {
  final useCase = ref.watch(getSalesHistoryUseCaseProvider);
  return useCase.execute(filter);
});

// ─────────────────────────────────────────────────────────────────────────────
// Notifiers

/// CloseDayNotifier — handles the day closure action.
class CloseDayNotifier extends Notifier<AsyncValue<DayClosureSummary?>> {
  @override
  AsyncValue<DayClosureSummary?> build() {
    // Rebuilt (state reset) when the use case changes, as the former
    // provider was recreated.
    ref.watch(closeDayUseCaseProvider);
    return const AsyncValue.data(null);
  }

  /// Identity-based notification, as before the Riverpod 3 migration (not ==).
  @override
  bool updateShouldNotify(AsyncValue<DayClosureSummary?> previous,
          AsyncValue<DayClosureSummary?> next) =>
      !identical(previous, next);

  Future<void> closeDay(String storeId, String actorId) async {
    state = const AsyncValue.loading();
    try {
      // A50: no closure while the account is read-only.
      SyncGateGuard.assertAccountWritable(
          ref.read(tenantStatusClaimProvider).value);
      final summary = await ref
          .read(closeDayUseCaseProvider)
          .execute(storeId: storeId, actorId: actorId);
      // Riverpod 3: a disposed notifier throws on `state` / `ref`.
      if (!ref.mounted) return;
      state = AsyncValue.data(summary);
      // Invalidate related providers to refresh UI
      ref.invalidate(dayClosureStateProvider);
      ref.invalidate(lastClosureProvider);
      ref.invalidate(todaySummaryProvider);
      ref.invalidate(reportHistoryProvider);
    } catch (e, st) {
      if (ref.mounted) state = AsyncValue.error(e, st);
      rethrow;
    }
  }

  void reset() {
    state = const AsyncValue.data(null);
  }
}

final closeDayNotifierProvider =
    NotifierProvider<CloseDayNotifier, AsyncValue<DayClosureSummary?>>(
        CloseDayNotifier.new);

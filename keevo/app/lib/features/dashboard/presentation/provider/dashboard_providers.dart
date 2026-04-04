import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_dashboard_datasource.dart';
import '../../data/datasource/remote_dashboard_datasource.dart';
import '../../data/repository/dashboard_repository_impl.dart';
import '../../domain/model/dashboard_snapshot.dart';
import '../../domain/model/motivational_message_service.dart';
import '../../domain/repository/dashboard_repository.dart';

/// SharedPreferences key for motivational message dismissal date.
const kMotivationalDismissedDate = 'motivational_dismissed_date';

/// SharedPreferences key for user first name.
const kUserFirstNameKey = 'user_first_name';

/// SharedPreferences key for tenant name.
const kTenantNameKey = 'tenant_name';

/// Local Drift datasource for dashboard aggregation queries.
final localDashboardDatasourceProvider = Provider<LocalDashboardDatasource>((ref) {
  return LocalDashboardDatasource(ref.watch(appDatabaseProvider));
});

/// Remote dashboard datasource — fetches from backend API.
final remoteDashboardDatasourceProvider = Provider<RemoteDashboardDatasource>((ref) {
  return RemoteDashboardDatasource(dio: ref.watch(dioProvider));
});

/// Dashboard repository — online-first with offline fallback.
final dashboardRepositoryProvider = Provider<DashboardRepository>((ref) {
  return DashboardRepositoryImpl(
    ref.watch(localDashboardDatasourceProvider),
    ref.watch(remoteDashboardDatasourceProvider),
    ref.watch(connectivityServiceProvider),
  );
});

/// Dashboard snapshot — full aggregated data, auto-refresh.
final dashboardSnapshotProvider = FutureProvider<DashboardSnapshot>((ref) {
  return ref.watch(dashboardRepositoryProvider).getDashboardSnapshot();
});

/// Store overviews — derived from the dashboard snapshot (online-first).
/// The backend computes todayCA/yesterdayCA in WAT timezone; using the remote
/// snapshot avoids the device timezone mismatch that the local SQLite queries
/// would produce when the emulator/phone is on a different timezone.
final storeOverviewsProvider = FutureProvider<List<StoreOverview>>((ref) async {
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  return snapshot.storeOverviews;
});

// ── Chart period providers ──────────────────────────────────

/// Current chart period selection for the main dashboard.
final chartPeriodProvider = StateProvider<ChartPeriod>((ref) => ChartPeriod.daily);

/// Chart data for the selected period (all stores).
/// Online-first: uses the dashboard snapshot (remote → local fallback).
final chartDataProvider = FutureProvider<List<DailyCA>>((ref) async {
  final period = ref.watch(chartPeriodProvider);
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  switch (period) {
    case ChartPeriod.daily:
      return snapshot.dailyCALast30;
    case ChartPeriod.weekly:
      return snapshot.weeklyCA;
    case ChartPeriod.monthly:
      return snapshot.monthlyCA;
    case ChartPeriod.yearly:
      return snapshot.yearlyCA;
  }
});

// ── Per-store providers (for store detail page) ─────────────

/// Current chart period for a specific store detail.
final storeChartPeriodProvider = StateProvider.family<ChartPeriod, String>(
  (ref, storeId) => ChartPeriod.daily,
);

/// Chart data for a specific store.
/// When the remote snapshot has no per-store chart data (offline fallback),
/// queries the local datasource directly.
final storeChartDataProvider = FutureProvider.family<List<DailyCA>, String>((ref, storeId) async {
  final period = ref.watch(storeChartPeriodProvider(storeId));
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  final store = snapshot.storeOverviews
      .where((s) => s.storeId == storeId)
      .firstOrNull;
  if (store == null) return [];

  final List<DailyCA> remoteData;
  switch (period) {
    case ChartPeriod.daily:
      remoteData = store.dailyCA;
    case ChartPeriod.weekly:
      remoteData = store.weeklyCA;
    case ChartPeriod.monthly:
      remoteData = store.monthlyCA;
    case ChartPeriod.yearly:
      remoteData = store.yearlyCA;
  }

  if (remoteData.isNotEmpty) return remoteData;

  // Offline / local fallback: StoreOverview chart lists are empty.
  final local = ref.watch(localDashboardDatasourceProvider);
  switch (period) {
    case ChartPeriod.daily:
      return local.getStoreDailyCA(storeId);
    case ChartPeriod.weekly:
      return local.getStoreWeeklyCA(storeId);
    case ChartPeriod.monthly:
      return local.getStoreMonthlyCA(storeId);
    case ChartPeriod.yearly:
      return local.getStoreYearlyCA(storeId);
  }
});

/// Top products for a specific store this week.
final storeTopProductsProvider = FutureProvider.family<List<TopProduct>, String>((ref, storeId) {
  return ref.watch(localDashboardDatasourceProvider).getStoreTopProducts(storeId);
});

/// Worst products for a specific store this week.
final storeWorstProductsProvider = FutureProvider.family<List<TopProduct>, String>((ref, storeId) {
  return ref.watch(localDashboardDatasourceProvider).getStoreWorstProducts(storeId);
});

/// Low stock products for a specific store.
final storeLowStockProductsProvider = FutureProvider.family<List<LowStockProduct>, String>((ref, storeId) {
  return ref.watch(localDashboardDatasourceProvider).getStoreLowStockProducts(storeId);
});

/// Current user first name from SharedPreferences.
final currentUserFirstNameProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kUserFirstNameKey);
});

/// Current tenant name from SharedPreferences.
final currentTenantNameProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kTenantNameKey);
});

/// Whether the motivational message has been dismissed today.
final isMotivationalDismissedProvider = Provider<bool>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  final dismissedDate = prefs.getString(kMotivationalDismissedDate);
  if (dismissedDate == null) return false;
  final today = DateFormat('yyyy-MM-dd').format(DateTime.now());
  return dismissedDate == today;
});

/// Motivational message — returns null if dismissed today.
final motivationalMessageProvider = Provider<String?>((ref) {
  if (ref.watch(isMotivationalDismissedProvider)) return null;

  final firstName = ref.watch(currentUserFirstNameProvider) ?? '';
  final snapshot = ref.watch(dashboardSnapshotProvider);

  return snapshot.whenOrNull(
    data: (data) => MotivationalMessageService.getMessage(
      firstName: firstName.isNotEmpty ? firstName : 'Patron',
      yesterdayCA: data.yesterdayCA,
    ),
  );
});

/// Dismiss motivational message for today.
Future<void> dismissMotivationalMessage(SharedPreferences prefs) async {
  final today = DateFormat('yyyy-MM-dd').format(DateTime.now());
  await prefs.setString(kMotivationalDismissedDate, today);
}

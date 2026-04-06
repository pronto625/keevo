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

/// Number of days to display when period == daily (7 / 14 / 30).
final chartDaysProvider = StateProvider<int>((ref) => 7);

/// Chart data for the selected period (all stores).
/// Online-first: uses the dashboard snapshot (remote → local fallback).
/// Daily data is always zero-filled so every day appears in the chart.
final chartDataProvider = FutureProvider<List<DailyCA>>((ref) async {
  final period = ref.watch(chartPeriodProvider);
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  switch (period) {
    case ChartPeriod.daily:
      final days = ref.watch(chartDaysProvider);
      // Zero-fill so days with no sales still appear as empty bars.
      final all = _zeroFillDailyCA(snapshot.dailyCALast30, 30);
      return all.length > days ? all.sublist(all.length - days) : all;
    case ChartPeriod.weekly:
      return snapshot.weeklyCA;
    case ChartPeriod.monthly:
      return snapshot.monthlyCA;
    case ChartPeriod.yearly:
      return snapshot.yearlyCA;
  }
});

/// Fills in missing dates so the list always covers [totalDays] consecutive days
/// ending today, with amount = 0 for days the backend didn't return.
List<DailyCA> _zeroFillDailyCA(List<DailyCA> data, int totalDays) {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final start = today.subtract(Duration(days: totalDays - 1));
  final map = <String, int>{};
  for (final d in data) {
    final key =
        '${d.date.year}-${d.date.month.toString().padLeft(2, '0')}-${d.date.day.toString().padLeft(2, '0')}';
    map[key] = d.amount;
  }
  return List.generate(totalDays, (i) {
    final d = start.add(Duration(days: i));
    final key =
        '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
    return DailyCA(date: d, amount: map[key] ?? 0);
  });
}

/// Top 5 best-selling products this week — toutes boutiques confondues.
/// Online-first: uses backend data from snapshot; falls back to local DB.
final topProductsProvider = FutureProvider<List<TopProduct>>((ref) async {
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  if (snapshot.weeklyTopProducts.isNotEmpty) return snapshot.weeklyTopProducts;
  return ref.watch(localDashboardDatasourceProvider).getWeeklyTopProducts();
});

/// Top 5 least-selling products this week — toutes boutiques confondues.
/// Online-first: uses backend data from snapshot; falls back to local DB.
final worstProductsProvider = FutureProvider<List<TopProduct>>((ref) async {
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  if (snapshot.weeklyWorstProducts.isNotEmpty) return snapshot.weeklyWorstProducts;
  return ref.watch(localDashboardDatasourceProvider).getWeeklyWorstProducts();
});

// ── Per-store providers (for store detail page) ─────────────

/// Current chart period for a specific store detail.
final storeChartPeriodProvider = StateProvider.family<ChartPeriod, String>(
  (ref, storeId) => ChartPeriod.daily,
);

/// Day range for a specific store's daily chart (7 / 14 / 30).
final storeChartDaysProvider = StateProvider.family<int, String>(
  (ref, storeId) => 7,
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

  final local = ref.watch(localDashboardDatasourceProvider);

  switch (period) {
    case ChartPeriod.daily:
      final fullData = store.dailyCA.isNotEmpty
          ? store.dailyCA
          : await local.getStoreDailyCA(storeId);
      final days = ref.watch(storeChartDaysProvider(storeId));
      return fullData.length > days ? fullData.sublist(fullData.length - days) : fullData;
    case ChartPeriod.weekly:
      return store.weeklyCA.isNotEmpty
          ? store.weeklyCA
          : await local.getStoreWeeklyCA(storeId);
    case ChartPeriod.monthly:
      return store.monthlyCA.isNotEmpty
          ? store.monthlyCA
          : await local.getStoreMonthlyCA(storeId);
    case ChartPeriod.yearly:
      return store.yearlyCA.isNotEmpty
          ? store.yearlyCA
          : await local.getStoreYearlyCA(storeId);
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

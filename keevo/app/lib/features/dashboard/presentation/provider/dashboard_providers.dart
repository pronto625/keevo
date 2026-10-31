import 'dart:async';

import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_dashboard_datasource.dart';
import '../../data/datasource/remote_dashboard_datasource.dart';
import '../../data/repository/dashboard_repository_impl.dart';
import '../../domain/model/dashboard_snapshot.dart';
import '../../domain/model/motivational_message_service.dart';
import '../../domain/repository/dashboard_repository.dart';

export 'package:keevo_core/storage/app_constants.dart' show kUserFirstNameKey;

/// SharedPreferences key for motivational message dismissal date.
const kMotivationalDismissedDate = 'motivational_dismissed_date';


/// SharedPreferences key for tenant name.
const kTenantNameKey = 'tenant_name';

/// Clock for the dashboard chart slots (v1s-16-19) — overridable in tests.
final dashboardClockProvider =
    Provider<DateTime Function()>((ref) => DateTime.now);

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
    isOwnerSession: () => ref.read(isOwnerSessionProvider),
  );
});

/// Identity of the signed-in session (account + role), null when signed out.
///
/// v1s-16-21: the snapshot is cached for the whole process, so without this
/// a sign-in served the figures computed for the previous session (another
/// account, or before the role was known — the device's figures). Sign-in,
/// token renewal and sign-out invalidate [currentUserIdProvider] and
/// [currentUserRoleProvider]; the key is then re-read, and the snapshot only
/// reloads when it actually changed (not on a mere renewal).
final dashboardSessionKeyProvider = Provider<String?>((ref) {
  ref.watch(currentUserIdProvider);
  final role = ref.watch(currentUserRoleProvider);
  if (role == null) return null;
  final owner =
      ref.watch(sharedPreferencesProvider).getString(kLocalDataOwnerKey);
  return '${owner ?? ''}|$role';
});

/// Pause before re-asking the server when the snapshot had to fall back to
/// the device's figures in an owner session — overridable in tests.
final dashboardServerRetryDelayProvider =
    Provider<Duration>((ref) => const Duration(seconds: 20));

/// Automatic re-fetches allowed per session while the server stays
/// unreachable (then: next sync, connectivity back, or « Actualiser »).
const kDashboardServerRetries = 3;

class _ServerRetryBudget {
  int used = 0;
}

final _dashboardServerRetryBudgetProvider = Provider<_ServerRetryBudget>((ref) {
  ref.watch(dashboardSessionKeyProvider);
  return _ServerRetryBudget();
});

/// Dashboard snapshot — full aggregated data, auto-refresh.
///
/// Scoped to the session ([dashboardSessionKeyProvider]). In an owner session
/// a snapshot computed on the device (summary unreachable) is only a stopgap:
/// the server is asked again when the connection comes back and, a few
/// times, after [dashboardServerRetryDelayProvider] — the server totals
/// replace the device's without « Actualiser ».
final dashboardSnapshotProvider = FutureProvider<DashboardSnapshot>((ref) async {
  ref.watch(dashboardSessionKeyProvider);
  final budget = ref.watch(_dashboardServerRetryBudgetProvider);
  // Everything ref-related is read before the await (a dependency may change
  // meanwhile, and ref is then unusable until the rebuild).
  final isOwner = ref.read(isOwnerSessionProvider);
  final connectivity = ref.read(connectivityServiceProvider);
  final retryDelay = ref.read(dashboardServerRetryDelayProvider);
  final refetch = _ServerRefetch();
  ref.onDispose(refetch.cancel);

  final snapshot =
      await ref.watch(dashboardRepositoryProvider).getDashboardSnapshot();

  if (!snapshot.computedOnDevice) {
    budget.used = 0;
  } else if (isOwner) {
    refetch.schedule(
      connectivity: connectivity,
      delay: budget.used < kDashboardServerRetries ? retryDelay : null,
      onDelay: () => budget.used++,
      refetch: ref.invalidateSelf,
    );
  }
  // The value stays cached until invalidated: remember when it was fetched.
  return snapshot.asOf != null ? snapshot : snapshot.withAsOf(DateTime.now());
});

/// Re-asks the server for a snapshot that fell back to the device's figures:
/// when the connection comes back, and once after [delay] (null = no timer).
class _ServerRefetch {
  Timer? _timer;
  StreamSubscription<bool>? _sub;
  bool _disposed = false;

  void schedule({
    required ConnectivityService connectivity,
    required Duration? delay,
    required void Function() onDelay,
    required void Function() refetch,
  }) {
    if (_disposed) return;
    var sawOffline = false;
    _sub = connectivity.onlineStream.listen(
      (online) {
        // Only a real comeback: the stream may first replay the current state.
        if (!online) {
          sawOffline = true;
        } else if (sawOffline) {
          refetch();
        }
      },
      onError: (Object _) {},
    );
    // The stream only reports changes: a snapshot fetched while already
    // offline would never see the `false` that arms the comeback refetch.
    connectivity.isOnline().then((online) {
      if (!online) sawOffline = true;
    }, onError: (Object _) {});
    if (delay != null) {
      _timer = Timer(delay, () {
        onDelay();
        refetch();
      });
    }
  }

  void cancel() {
    _disposed = true;
    _timer?.cancel();
    _sub?.cancel();
  }
}

/// Store overviews — derived from the dashboard snapshot (online-first).
/// The backend computes todayCA/yesterdayCA in WAT timezone; using the remote
/// snapshot avoids the device timezone mismatch that the local SQLite queries
/// would produce when the emulator/phone is on a different timezone.
final storeOverviewsProvider = FutureProvider<List<StoreOverview>>((ref) async {
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  return snapshot.storeOverviews;
});

// ── « Chiffre d'affaires total » ────────────────────────────

/// SharedPreferences key of the period chosen on the « Chiffre d'affaires
/// total » card (per device).
const kDashboardRevenuePeriodKey = 'dashboard_revenue_period';

/// Selected period of the « Chiffre d'affaires total » card — « Mois » by
/// default, persisted per device.
class RevenuePeriodNotifier extends Notifier<RevenuePeriod> {
  @override
  RevenuePeriod build() {
    final prefs = ref.watch(sharedPreferencesProvider);
    return RevenuePeriod.fromKey(prefs.getString(kDashboardRevenuePeriodKey)) ??
        RevenuePeriod.month;
  }

  void select(RevenuePeriod period) {
    ref.read(sharedPreferencesProvider)
        .setString(kDashboardRevenuePeriodKey, period.key);
    state = period;
  }
}

final revenuePeriodProvider =
    NotifierProvider<RevenuePeriodNotifier, RevenuePeriod>(
        RevenuePeriodNotifier.new);

/// Figure shown by the « Chiffre d'affaires total » card.
class PeriodRevenueView {
  final RevenuePeriod period;
  final PeriodRevenue revenue;

  /// [DashboardSnapshot.asOf] of the snapshot this figure comes from: the
  /// card shows it only next to that snapshot, never a previous one's.
  final DateTime? asOf;

  /// True when computed from this device's database (offline, or server
  /// without the figure): it only covers the sales synced to this device,
  /// and the card says so.
  final bool onDeviceOnly;

  const PeriodRevenueView({
    required this.period,
    required this.revenue,
    required this.onDeviceOnly,
    this.asOf,
  });
}

/// « Chiffre d'affaires total » for the selected period. Server figure from
/// the dashboard summary when online; otherwise the device's aggregation,
/// flagged [PeriodRevenueView.onDeviceOnly].
final periodRevenueProvider = FutureProvider<PeriodRevenueView>((ref) async {
  final period = ref.watch(revenuePeriodProvider);
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  final fromSnapshot = snapshot.periodRevenue[period];
  if (fromSnapshot != null) {
    return PeriodRevenueView(
      period: period,
      revenue: fromSnapshot,
      onDeviceOnly: snapshot.computedOnDevice,
      asOf: snapshot.asOf,
    );
  }
  final local = await ref
      .watch(localDashboardDatasourceProvider)
      .getPeriodRevenue(period);
  return PeriodRevenueView(
      period: period, revenue: local, onDeviceOnly: true, asOf: snapshot.asOf);
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
  final now = ref.watch(dashboardClockProvider)();
  switch (period) {
    case ChartPeriod.daily:
      final days = ref.watch(chartDaysProvider);
      // Zero-fill so days with no sales still appear as empty bars.
      final all = _zeroFillDailyCA(snapshot.dailyCALast30, 30, now);
      return all.length > days ? all.sublist(all.length - days) : all;
    case ChartPeriod.weekly:
      return zeroFillWeeklyCA(snapshot.weeklyCA, now);
    case ChartPeriod.monthly:
      return zeroFillMonthlyCA(snapshot.monthlyCA, now);
    case ChartPeriod.yearly:
      return zeroFillYearlyCA(snapshot.yearlyCA, now);
  }
});

String _dayKey(DateTime d) =>
    '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
String _monthKey(DateTime d) =>
    '${d.year}-${d.month.toString().padLeft(2, '0')}';
String _yearKey(DateTime d) => '${d.year}';

/// Maps [data] onto [slots] by [key] (amounts of equal keys are summed);
/// slots without data get 0. Gives the remote (online) series the same slot
/// shapes as the local datasource (v1s-16-19).
List<DailyCA> _zeroFill(
    List<DailyCA> data, List<DateTime> slots, String Function(DateTime) key) {
  final map = <String, int>{};
  for (final d in data) {
    map.update(key(d.date), (v) => v + d.amount, ifAbsent: () => d.amount);
  }
  return [for (final s in slots) DailyCA(date: s, amount: map[key(s)] ?? 0)];
}

/// 12 weekly slots (local Mondays, oldest → newest), keyed by the Monday.
@visibleForTesting
List<DailyCA> zeroFillWeeklyCA(List<DailyCA> data, DateTime now) => _zeroFill(
    data, LocalDashboardDatasource.weeklySlots(now), _dayKey);

/// 12 monthly slots (1st of month), keyed `YYYY-MM`.
@visibleForTesting
List<DailyCA> zeroFillMonthlyCA(List<DailyCA> data, DateTime now) => _zeroFill(
    data,
    List.generate(12, (i) => DateTime(now.year, now.month - 11 + i, 1)),
    _monthKey);

/// 5 yearly slots (1 January), keyed `YYYY`.
@visibleForTesting
List<DailyCA> zeroFillYearlyCA(List<DailyCA> data, DateTime now) => _zeroFill(
    data, List.generate(5, (i) => DateTime(now.year - 4 + i)), _yearKey);

/// Fills in missing dates so the list always covers [totalDays] consecutive days
/// ending today, with amount = 0 for days the backend didn't return.
/// Calendar-field arithmetic (no `Duration`): DST-safe.
List<DailyCA> _zeroFillDailyCA(List<DailyCA> data, int totalDays, DateTime now) =>
    _zeroFill(
        data,
        List.generate(totalDays,
            (i) => DateTime(now.year, now.month, now.day - (totalDays - 1 - i))),
        _dayKey);

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
  final now = ref.watch(dashboardClockProvider)();

  // Remote series are sparse (only periods with sales): zero-fill them onto
  // the same slots as the local datasource (v1s-16-19).
  switch (period) {
    case ChartPeriod.daily:
      final fullData = store.dailyCA.isNotEmpty
          ? _zeroFillDailyCA(store.dailyCA, 30, now)
          : await local.getStoreDailyCA(storeId);
      final days = ref.watch(storeChartDaysProvider(storeId));
      return fullData.length > days ? fullData.sublist(fullData.length - days) : fullData;
    case ChartPeriod.weekly:
      return store.weeklyCA.isNotEmpty
          ? zeroFillWeeklyCA(store.weeklyCA, now)
          : await local.getStoreWeeklyCA(storeId);
    case ChartPeriod.monthly:
      return store.monthlyCA.isNotEmpty
          ? zeroFillMonthlyCA(store.monthlyCA, now)
          : await local.getStoreMonthlyCA(storeId);
    case ChartPeriod.yearly:
      return store.yearlyCA.isNotEmpty
          ? zeroFillYearlyCA(store.yearlyCA, now)
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

/// 13.10b — free local « stock bas » list (Drift) of a store, or of every
/// store when null. Re-read each time the list opens.
final localLowStockProductsProvider = FutureProvider.autoDispose
    .family<List<LowStockProduct>, String?>((ref, storeId) {
  return ref
      .watch(localDashboardDatasourceProvider)
      .getLowStockProducts(storeId: storeId);
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

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/dashboard/data/datasource/local_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/data/datasource/remote_dashboard_datasource.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockLocal extends Mock implements LocalDashboardDatasource {}

class _MockDio extends Mock implements Dio {}

DashboardSnapshot _snapshot({
  Map<RevenuePeriod, PeriodRevenue> periodRevenue = const {},
  bool computedOnDevice = false,
}) =>
    DashboardSnapshot(
      todayCA: 0,
      yesterdayCA: 0,
      dayBeforeYesterdayCA: 0,
      trendPercent: 0,
      totalTransactionsMonth: 0,
      averageBasketMonth: 0,
      prevMonthTransactions: 0,
      prevMonthAverageBasket: 0,
      lowStockCount: 0,
      weeklyTopProducts: const [],
      weeklyWorstProducts: const [],
      dailyCALast30: const [],
      storeOverviews: const [],
      todaySalesCount: 0,
      periodRevenue: periodRevenue,
      computedOnDevice: computedOnDevice,
    );

const _server = {
  RevenuePeriod.week: PeriodRevenue(amount: 10000, salesCount: 4),
  RevenuePeriod.month: PeriodRevenue(amount: 50000, salesCount: 20),
  RevenuePeriod.year: PeriodRevenue(amount: 400000, salesCount: 150),
  RevenuePeriod.allTime: PeriodRevenue(amount: 900000, salesCount: 400),
};

void main() {
  setUpAll(() => registerFallbackValue(RevenuePeriod.month));

  Future<ProviderContainer> container(DashboardSnapshot snapshot,
      {Map<String, Object> prefs = const {},
      LocalDashboardDatasource? local}) async {
    SharedPreferences.setMockInitialValues(prefs);
    final sp = await SharedPreferences.getInstance();
    final c = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(sp),
      dashboardSnapshotProvider.overrideWith((_) async => snapshot),
      if (local != null) localDashboardDatasourceProvider.overrideWithValue(local),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  group('revenuePeriodProvider', () {
    test('defaults to « Mois »', () async {
      final c = await container(_snapshot());
      expect(c.read(revenuePeriodProvider), RevenuePeriod.month);
    });

    test('restores and persists the period chosen on this device', () async {
      final c = await container(_snapshot(),
          prefs: {kDashboardRevenuePeriodKey: 'year'});
      expect(c.read(revenuePeriodProvider), RevenuePeriod.year);

      c.read(revenuePeriodProvider.notifier).select(RevenuePeriod.allTime);

      expect(c.read(revenuePeriodProvider), RevenuePeriod.allTime);
      final sp = await SharedPreferences.getInstance();
      expect(sp.getString(kDashboardRevenuePeriodKey), 'allTime');
    });

    test('an unknown stored value falls back to « Mois »', () async {
      final c = await container(_snapshot(),
          prefs: {kDashboardRevenuePeriodKey: 'garbage'});
      expect(c.read(revenuePeriodProvider), RevenuePeriod.month);
    });
  });

  group('periodRevenueProvider', () {
    test('online: server figure of the selected period, not flagged', () async {
      final c = await container(_snapshot(periodRevenue: _server));

      var view = await c.read(periodRevenueProvider.future);
      expect(view.period, RevenuePeriod.month);
      expect(view.revenue, _server[RevenuePeriod.month]);
      expect(view.onDeviceOnly, isFalse);

      c.read(revenuePeriodProvider.notifier).select(RevenuePeriod.allTime);
      view = await c.read(periodRevenueProvider.future);
      expect(view.revenue, _server[RevenuePeriod.allTime]);
      expect(view.onDeviceOnly, isFalse);
    });

    test('offline snapshot: device figure flagged « données de cet appareil »',
        () async {
      final c = await container(
          _snapshot(periodRevenue: _server, computedOnDevice: true));

      final view = await c.read(periodRevenueProvider.future);
      expect(view.revenue, _server[RevenuePeriod.month]);
      expect(view.onDeviceOnly, isTrue);
    });

    test('server without period totals: computed on the device, flagged',
        () async {
      final local = _MockLocal();
      when(() => local.getPeriodRevenue(any())).thenAnswer(
          (_) async => const PeriodRevenue(amount: 1234, salesCount: 2));
      final c = await container(_snapshot(), local: local);

      final view = await c.read(periodRevenueProvider.future);
      expect(view.revenue, const PeriodRevenue(amount: 1234, salesCount: 2));
      expect(view.onDeviceOnly, isTrue);
      verify(() => local.getPeriodRevenue(RevenuePeriod.month)).called(1);
    });
  });

  group('RemoteDashboardDatasource periodTotals', () {
    Future<DashboardSnapshot> fetch(Map<String, dynamic> data) async {
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>(any())).thenAnswer((_) async =>
          Response(
              requestOptions: RequestOptions(path: '/'),
              data: {'data': data}));
      return RemoteDashboardDatasource(dio: dio).getDashboardSummary();
    }

    test('maps every period', () async {
      final s = await fetch({
        'periodTotals': {
          'week': {'amount': 10000, 'salesCount': 4},
          'month': {'amount': 50000, 'salesCount': 20},
          'year': {'amount': 400000, 'salesCount': 150},
          'allTime': {'amount': 900000, 'salesCount': 400},
        },
      });
      expect(s.periodRevenue, _server);
      expect(s.computedOnDevice, isFalse);
    });

    test('older server without periodTotals: empty map', () async {
      final s = await fetch({'todayCA': 5});
      expect(s.periodRevenue, isEmpty);
      expect(s.todayCA, 5);
    });
  });
}

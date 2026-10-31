// v1s-16-21 — « Chiffre d'affaires total » à 0 au premier affichage après la
// connexion : le résumé est lié à la session et le repli sur les chiffres de
// l'appareil (propriétaire) redemande le serveur sans « Actualiser ».
import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/domain/repository/dashboard_repository.dart';
import 'package:keevo/features/dashboard/presentation/provider/dashboard_providers.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockRepo extends Mock implements DashboardRepository {}

class _FakeConnectivity implements ConnectivityService {
  final controller = StreamController<bool>.broadcast();
  bool online = true;

  @override
  Future<bool> isOnline() async => online;

  @override
  Stream<bool> get onlineStream => controller.stream;
}

DashboardSnapshot _snap(PeriodRevenue allTime,
        {bool onDevice = false, int lowStock = 0, int today = 0}) =>
    DashboardSnapshot(
      todayCA: today,
      yesterdayCA: 0,
      dayBeforeYesterdayCA: 0,
      trendPercent: 0,
      totalTransactionsMonth: 0,
      averageBasketMonth: 0,
      prevMonthTransactions: 0,
      prevMonthAverageBasket: 0,
      lowStockCount: lowStock,
      weeklyTopProducts: const [],
      weeklyWorstProducts: const [],
      dailyCALast30: const [],
      storeOverviews: const [],
      todaySalesCount: 0,
      periodRevenue: {for (final p in RevenuePeriod.values) p: allTime},
      computedOnDevice: onDevice,
    );

const _zero = PeriodRevenue(amount: 0, salesCount: 0);
const _server = PeriodRevenue(amount: 93500, salesCount: 6);

void main() {
  late _MockRepo repo;
  late _FakeConnectivity connectivity;
  late List<DashboardSnapshot> answers;

  setUp(() {
    repo = _MockRepo();
    connectivity = _FakeConnectivity();
    answers = [];
    when(() => repo.getDashboardSnapshot()).thenAnswer((_) async =>
        answers.length > 1 ? answers.removeAt(0) : answers.first);
  });

  tearDown(() => connectivity.controller.close());

  Future<(ProviderContainer, SharedPreferences)> build(
      {Map<String, Object> prefs = const {},
      Duration retryDelay = const Duration(hours: 1)}) async {
    SharedPreferences.setMockInitialValues(prefs);
    final sp = await SharedPreferences.getInstance();
    var userId = 'u1';
    final c = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(sp),
      currentUserIdProvider.overrideWith((_) async => userId),
      dashboardRepositoryProvider.overrideWithValue(repo),
      connectivityServiceProvider.overrideWithValue(connectivity),
      dashboardServerRetryDelayProvider.overrideWithValue(retryDelay),
    ]);
    addTearDown(c.dispose);
    return (c, sp);
  }

  /// What SessionClaimsApplier.apply / SignOutService do to the providers.
  void applySession(ProviderContainer c) {
    c.invalidate(currentUserRoleProvider);
    c.invalidate(currentUserIdProvider);
  }

  group('session scoping', () {
    test('sign-in reloads a snapshot cached before the session was known',
        () async {
      final (c, sp) = await build();
      // Before sign-in (no role): the device's figures, still empty.
      answers = [_snap(_zero, onDevice: true), _snap(_server, lowStock: 2)];
      final before = await c.read(periodRevenueProvider.future);
      expect(before.revenue, _zero);
      expect(before.onDeviceOnly, isTrue);

      await sp.setString(kLocalDataOwnerKey, 'owner@t1');
      await sp.setString(kUserRoleKey, 'OWNER');
      applySession(c);

      final after = await c.read(periodRevenueProvider.future);
      expect(after.revenue, _server);
      expect(after.onDeviceOnly, isFalse);
      // Same snapshot for every figure of the page (stock bas, CA du jour…).
      expect((await c.read(dashboardSnapshotProvider.future)).lowStockCount, 2);
      verify(() => repo.getDashboardSnapshot()).called(2);
    });

    test('another owner signing in (no sign-out in between) reloads',
        () async {
      final (c, sp) = await build(prefs: {
        kUserRoleKey: 'OWNER',
        kLocalDataOwnerKey: 'a@t1',
      });
      answers = [_snap(const PeriodRevenue(amount: 1, salesCount: 1)),
        _snap(_server)];
      expect((await c.read(periodRevenueProvider.future)).revenue.amount, 1);

      await sp.setString(kLocalDataOwnerKey, 'b@t2');
      applySession(c);

      expect((await c.read(periodRevenueProvider.future)).revenue, _server);
    });

    test('a token renewal of the same session does not refetch', () async {
      final (c, _) = await build(prefs: {
        kUserRoleKey: 'OWNER',
        kLocalDataOwnerKey: 'a@t1',
      });
      answers = [_snap(_server)];
      await c.read(dashboardSnapshotProvider.future);

      applySession(c);
      await c.read(dashboardSnapshotProvider.future);
      await Future<void>.delayed(Duration.zero);

      verify(() => repo.getDashboardSnapshot()).called(1);
    });
  });

  group('owner session on the device fallback', () {
    const owner = {kUserRoleKey: 'OWNER', kLocalDataOwnerKey: 'a@t1'};

    test('asks the server again after the delay: server totals, no label',
        () async {
      final (c, _) = await build(
          prefs: owner, retryDelay: const Duration(milliseconds: 10));
      answers = [_snap(_zero, onDevice: true), _snap(_server)];
      final sub = c.listen(periodRevenueProvider, (_, __) {});
      addTearDown(sub.close);

      final first = await c.read(periodRevenueProvider.future);
      expect(first.onDeviceOnly, isTrue);

      await Future<void>.delayed(const Duration(milliseconds: 50));
      final view = await c.read(periodRevenueProvider.future);
      expect(view.revenue, _server);
      expect(view.onDeviceOnly, isFalse);
    });

    test('asks the server again when the connection comes back', () async {
      final (c, _) = await build(prefs: owner);
      answers = [_snap(_zero, onDevice: true), _snap(_server)];
      final sub = c.listen(dashboardSnapshotProvider, (_, __) {});
      addTearDown(sub.close);
      await c.read(dashboardSnapshotProvider.future);

      // Replay of the current state: no refetch.
      connectivity.controller.add(true);
      await Future<void>.delayed(Duration.zero);
      verify(() => repo.getDashboardSnapshot()).called(1);

      connectivity.controller.add(false);
      connectivity.controller.add(true);
      await Future<void>.delayed(Duration.zero);
      final snap = await c.read(dashboardSnapshotProvider.future);
      expect(snap.computedOnDevice, isFalse);
      expect(snap.periodRevenue[RevenuePeriod.allTime], _server);
    });

    test('fetched while already offline: the first comeback asks the server',
        () async {
      final (c, _) = await build(prefs: owner);
      connectivity.online = false;
      answers = [_snap(_zero, onDevice: true), _snap(_server)];
      final sub = c.listen(dashboardSnapshotProvider, (_, __) {});
      addTearDown(sub.close);
      await c.read(dashboardSnapshotProvider.future);
      await Future<void>.delayed(Duration.zero);

      // No `false` event: the device was offline before the fetch.
      connectivity.online = true;
      connectivity.controller.add(true);
      await Future<void>.delayed(Duration.zero);
      final snap = await c.read(dashboardSnapshotProvider.future);
      expect(snap.computedOnDevice, isFalse);
      expect(snap.periodRevenue[RevenuePeriod.allTime], _server);
    });

    test('server still unreachable: a bounded number of automatic retries',
        () async {
      final (c, _) = await build(
          prefs: owner, retryDelay: const Duration(milliseconds: 5));
      answers = [_snap(_zero, onDevice: true)];
      final sub = c.listen(dashboardSnapshotProvider, (_, __) {});
      addTearDown(sub.close);
      await c.read(dashboardSnapshotProvider.future);

      await Future<void>.delayed(const Duration(milliseconds: 200));
      verify(() => repo.getDashboardSnapshot())
          .called(1 + kDashboardServerRetries);
    });

    test('employee session: the device figures are the expected source',
        () async {
      final (c, _) = await build(
          prefs: {kUserRoleKey: 'EMPLOYEE', kLocalDataOwnerKey: 'e@t1'},
          retryDelay: const Duration(milliseconds: 5));
      answers = [_snap(_zero, onDevice: true)];
      final sub = c.listen(dashboardSnapshotProvider, (_, __) {});
      addTearDown(sub.close);
      await c.read(dashboardSnapshotProvider.future);

      await Future<void>.delayed(const Duration(milliseconds: 50));
      verify(() => repo.getDashboardSnapshot()).called(1);
    });
  });
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/seller_leaderboard_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';
import 'package:keevo/features/profitability/presentation/widget/offline_data_banner.dart';
import 'package:keevo/features/profitability/presentation/widget/period_filter_chips.dart';

class MockProfitabilityRepository extends Mock
    implements ProfitabilityRepository {}

/// v1s-14-2 — « Équipe » tab: rows, local re-rank, empty and error states,
/// offline banner, period chips and reconnect re-fetch.
void main() {
  setUpAll(() {
    registerFallbackValue(
        SellerLeaderboardParams(from: DateTime(2025), to: DateTime(2025)));
  });

  const awa = SellerLeaderboardEntry(
      rank: 0,
      sellerId: 'u1',
      sellerName: 'Awa Ndiaye',
      owner: false,
      salesCount: 10,
      totalRevenue: 500000,
      averageBasket: 50000);
  const bello = SellerLeaderboardEntry(
      rank: 0,
      sellerId: 'u2',
      sellerName: 'Bello Diallo',
      owner: false,
      salesCount: 60,
      totalRevenue: 300000,
      averageBasket: 5000);
  const owner = SellerLeaderboardEntry(
      rank: 0,
      sellerId: 'o1',
      sellerName: 'Propriétaire',
      owner: true,
      salesCount: 1,
      totalRevenue: 1000,
      averageBasket: 1000);

  Widget build(MockProfitabilityRepository repo,
          {String? storeId, Stream<SyncStatus>? status}) =>
      ProviderScope(
        overrides: [
          profitabilityRepositoryProvider.overrideWithValue(repo),
          syncStatusProvider.overrideWith(
              (_) => status ?? Stream.value(SyncStatus.online)),
        ],
        child: MaterialApp(home: SellerLeaderboardPage(storeId: storeId)),
      );

  void stub(MockProfitabilityRepository repo,
          Future<List<SellerLeaderboardEntry>> Function() answer,
          {bool fromLocal = false}) =>
      when(() => repo.getSellerLeaderboard(params: any(named: 'params')))
          .thenAnswer((_) async => SellerLeaderboardResult(
              entries: await answer(), fromLocal: fromLocal));

  List<SellerLeaderboardParams> captured(MockProfitabilityRepository repo) =>
      verify(() =>
              repo.getSellerLeaderboard(params: captureAny(named: 'params')))
          .captured
          .cast<SellerLeaderboardParams>();

  testWidgets('renders ranked rows (CA by default), today and store scope',
      (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [bello, owner, awa]);

    await tester.pumpWidget(build(repo, storeId: 's1'));
    await tester.pumpAndSettle();

    expect(find.text('Awa Ndiaye'), findsOneWidget);
    expect(find.text('Bello Diallo'), findsOneWidget);
    expect(find.text('Propriétaire'), findsOneWidget);
    expect(find.text('#1'), findsOneWidget);
    expect(find.text('#3'), findsOneWidget);
    expect(tester.getTopLeft(find.text('Awa Ndiaye')).dy,
        lessThan(tester.getTopLeft(find.text('Bello Diallo')).dy));

    final captured = verify(() =>
            repo.getSellerLeaderboard(params: captureAny(named: 'params')))
        .captured
        .single as SellerLeaderboardParams;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    expect(captured.from, today);
    expect(captured.to, today);
    expect(captured.storeId, 's1');
  });

  testWidgets('metric toggle re-ranks locally without re-fetching',
      (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa, bello]);

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ventes'));
    await tester.pumpAndSettle();

    expect(tester.getTopLeft(find.text('Bello Diallo')).dy,
        lessThan(tester.getTopLeft(find.text('Awa Ndiaye')).dy));
    verify(() => repo.getSellerLeaderboard(params: any(named: 'params')))
        .called(1);
  });

  testWidgets('empty → « Aucune vente sur la période »', (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => []);

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();

    expect(find.text('Aucune vente sur la période'), findsOneWidget);
  });

  testWidgets('error → AppErrorWidget, retry re-fetches', (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => throw Exception('boom'));

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();

    expect(find.byType(AppErrorWidget), findsOneWidget);

    stub(repo, () async => [awa]);
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(find.text('Awa Ndiaye'), findsOneWidget);
  });

  testWidgets('offline device → offline banner', (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa], fromLocal: true);

    await tester.pumpWidget(
        build(repo, status: Stream.value(SyncStatus.offlineOk)));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsOneWidget);
    expect(
        find.text(
            'Mode hors ligne — données de la dernière synchronisation affichées'),
        findsOneWidget);
  });

  testWidgets('offline device, fromLocal false → banner still shown',
      (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa]);

    await tester.pumpWidget(
        build(repo, status: Stream.value(SyncStatus.offlineOk)));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsOneWidget);
  });

  testWidgets('online but Drift data served (backend unreachable) → banner',
      (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa], fromLocal: true);

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsOneWidget);
    expect(find.text('Awa Ndiaye'), findsOneWidget);
  });

  testWidgets('online and remote data → no banner', (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa]);

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();

    expect(find.byType(OfflineDataBanner), findsNothing);
  });

  testWidgets('« 7 jours » re-fetches with the new from, Rentabilité period '
      'untouched', (tester) async {
    final repo = MockProfitabilityRepository();
    stub(repo, () async => [awa]);

    await tester.pumpWidget(build(repo));
    await tester.pumpAndSettle();
    final container = ProviderScope.containerOf(
        tester.element(find.byType(SellerLeaderboardPage)));
    final profitabilityBefore =
        container.read(selectedProfitabilityPeriodProvider);

    await tester.tap(find.text('7 jours'));
    await tester.pumpAndSettle();

    final calls = captured(repo);
    expect(calls, hasLength(2));
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    expect(calls.first.from, today);
    expect(calls.last.from, today.subtract(const Duration(days: 6)));
    expect(calls.last.to, today);
    expect(container.read(selectedLeaderboardPeriodProvider).preset,
        PeriodPreset.last7);
    expect(container.read(selectedProfitabilityPeriodProvider),
        same(profitabilityBefore));
  });

  testWidgets('offline → online transition re-fetches', (tester) async {
    final repo = MockProfitabilityRepository();
    final status = StreamController<SyncStatus>();
    addTearDown(status.close);
    stub(repo, () async => [awa], fromLocal: true);

    await tester.pumpWidget(build(repo, status: status.stream));
    status.add(SyncStatus.offlineOk);
    await tester.pumpAndSettle();
    expect(find.byType(OfflineDataBanner), findsOneWidget);

    stub(repo, () async => [awa, bello]);
    status.add(SyncStatus.online);
    await tester.pumpAndSettle();

    expect(captured(repo), hasLength(2));
    expect(find.text('Bello Diallo'), findsOneWidget);
    expect(find.byType(OfflineDataBanner), findsNothing);
  });
}

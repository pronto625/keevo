import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/features/profitability/domain/repository/profitability_repository.dart';
import 'package:keevo/features/profitability/presentation/page/seller_leaderboard_page.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

class MockProfitabilityRepository extends Mock
    implements ProfitabilityRepository {}

/// v1s-14-2 — « Équipe » tab: rows, local re-rank, empty and error states.
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

  Widget build(MockProfitabilityRepository repo, {String? storeId}) =>
      ProviderScope(
        overrides: [profitabilityRepositoryProvider.overrideWithValue(repo)],
        child: MaterialApp(home: SellerLeaderboardPage(storeId: storeId)),
      );

  void stub(MockProfitabilityRepository repo,
          Future<List<SellerLeaderboardEntry>> Function() answer) =>
      when(() => repo.getSellerLeaderboard(params: any(named: 'params')))
          .thenAnswer((_) => answer());

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
}

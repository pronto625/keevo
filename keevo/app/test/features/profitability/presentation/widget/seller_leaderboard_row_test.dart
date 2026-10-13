import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/theme/app_theme.dart';
import 'package:keevo/features/profitability/domain/model/seller_leaderboard_model.dart';
import 'package:keevo/features/profitability/presentation/widget/seller_leaderboard_row.dart';

/// v1s-14-2 — one row of the « Équipe » leaderboard.
void main() {
  SellerLeaderboardEntry entry({
    int rank = 1,
    String name = 'Awa Ndiaye',
    bool owner = false,
    int count = 3,
  }) =>
      SellerLeaderboardEntry(
          rank: rank,
          sellerId: 'u$rank',
          sellerName: name,
          owner: owner,
          salesCount: count,
          totalRevenue: 15000,
          averageBasket: 5000);

  Future<void> pump(WidgetTester tester, SellerLeaderboardEntry e,
          {Brightness brightness = Brightness.light}) =>
      tester.pumpWidget(MaterialApp(
        theme: ThemeData(brightness: brightness),
        home: Scaffold(body: SellerLeaderboardRow(entry: e)),
      ));

  BoxDecoration badge(WidgetTester tester, int rank) {
    final container = tester.widget<Container>(find
        .ancestor(of: find.text('#$rank'), matching: find.byType(Container))
        .first);
    return container.decoration! as BoxDecoration;
  }

  for (final (rank, medal) in [
    (1, AppTheme.medalGold),
    (2, AppTheme.medalSilver),
    (3, AppTheme.medalBronze),
  ]) {
    testWidgets('rank $rank → medal token badge', (tester) async {
      await pump(tester, entry(rank: rank));
      final d = badge(tester, rank);
      expect(d.color, medal.withValues(alpha: 0.16));
      expect((d.border! as Border).top.color, medal);
    });
  }

  testWidgets('rank 4+ → soft tint, no medal border', (tester) async {
    await pump(tester, entry(rank: 4));
    final d = badge(tester, 4);
    expect(d.color, AppTheme.softTint(Brightness.light));
    expect(d.border, isNull);
  });

  testWidgets('owner with an employee name → « Propriétaire » tag',
      (tester) async {
    await pump(tester, entry(name: 'Jean Kamga', owner: true));
    expect(find.text('Jean Kamga'), findsOneWidget);
    expect(find.text('Propriétaire'), findsOneWidget);
  });

  testWidgets('owner already named « Propriétaire » → no duplicate tag',
      (tester) async {
    await pump(tester, entry(name: 'Propriétaire', owner: true));
    expect(find.text('Propriétaire'), findsOneWidget);
  });

  testWidgets('non-owner → no tag', (tester) async {
    await pump(tester, entry());
    expect(find.text('Propriétaire'), findsNothing);
  });

  testWidgets('singular / plural « vente(s) »', (tester) async {
    await pump(tester, entry(count: 1));
    expect(find.textContaining('1 vente ·'), findsOneWidget);

    await pump(tester, entry(count: 0));
    expect(find.textContaining('0 vente ·'), findsOneWidget);

    await pump(tester, entry(count: 2));
    expect(find.textContaining('2 ventes ·'), findsOneWidget);
  });

  testWidgets('rank 1 with 0 sales → neutral tint, no medal', (tester) async {
    await pump(tester, entry(rank: 1, count: 0));
    final d = badge(tester, 1);
    expect(d.color, AppTheme.softTint(Brightness.light));
    expect(d.border, isNull);
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/profitability/presentation/provider/profitability_providers.dart';

SellerLeaderboardEntry _e(String id, String name, int count, int revenue) =>
    SellerLeaderboardEntry(
      rank: 0,
      sellerId: id,
      sellerName: name,
      owner: false,
      salesCount: count,
      totalRevenue: revenue,
      averageBasket: count == 0 ? 0 : revenue ~/ count,
    );

/// v1s-14-2 — local ranking, same order as the backend strategies.
void main() {
  final awa = _e('a', 'Awa', 5, 10000);
  final bello = _e('b', 'Bello', 2, 50000);
  final chantal = _e('c', 'Chantal', 1, 30000);

  List<String> names(List<SellerLeaderboardEntry> l) =>
      l.map((e) => e.sellerName).toList();

  test('CA desc, ranks renumbered from 1', () {
    final r = rankSellersBy([awa, chantal, bello], RankingMetric.ca);
    expect(names(r), ['Bello', 'Chantal', 'Awa']);
    expect(r.map((e) => e.rank), [1, 2, 3]);
  });

  test('sales count desc', () {
    expect(names(rankSellersBy([bello, chantal, awa], RankingMetric.salesCount)),
        ['Awa', 'Bello', 'Chantal']);
  });

  test('average basket desc', () {
    expect(names(rankSellersBy([awa, bello, chantal], RankingMetric.avgBasket)),
        ['Chantal', 'Bello', 'Awa']);
  });

  test('ties: revenue desc, then name (case-insensitive), then sellerId', () {
    expect(
        names(rankSellersBy(
            [_e('1', 'Awa', 2, 1000), _e('2', 'Bello', 2, 9000)],
            RankingMetric.salesCount)),
        ['Bello', 'Awa']);
    expect(
        names(rankSellersBy([
          _e('1', 'bello', 0, 0),
          _e('2', 'Awa', 0, 0),
          _e('3', 'chantal', 0, 0),
        ], RankingMetric.ca)),
        ['Awa', 'bello', 'chantal']);
    final r = rankSellersBy(
        [_e('z', 'X', 1, 1), _e('a', 'X', 1, 1)], RankingMetric.ca);
    expect(r.map((e) => e.sellerId), ['a', 'z']);
  });

  test('empty → empty', () {
    expect(rankSellersBy([], RankingMetric.ca), isEmpty);
  });
}

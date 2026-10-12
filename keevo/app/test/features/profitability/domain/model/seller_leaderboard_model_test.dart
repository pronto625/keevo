import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/profitability/domain/model/seller_leaderboard_model.dart';

void main() {
  group('SellerLeaderboardEntry.fromJson', () {
    test('maps the backend DTO', () {
      final e = SellerLeaderboardEntry.fromJson({
        'rank': 1,
        'sellerId': 'u1',
        'sellerName': 'Awa Ndiaye',
        'owner': false,
        'salesCount': 3,
        'totalRevenue': 15000,
        'averageBasket': 5000,
      });
      expect(e.rank, 1);
      expect(e.sellerId, 'u1');
      expect(e.sellerName, 'Awa Ndiaye');
      expect(e.owner, isFalse);
      expect(e.salesCount, 3);
      expect(e.totalRevenue, 15000);
      expect(e.averageBasket, 5000);
    });

    test('owner flag and missing optional fields', () {
      final e = SellerLeaderboardEntry.fromJson({
        'sellerId': 'u2',
        'sellerName': 'Propriétaire',
        'owner': true,
      });
      expect(e.owner, isTrue);
      expect(e.rank, 0);
      expect(e.salesCount, 0);
      expect(e.totalRevenue, 0);
      expect(e.averageBasket, 0);
    });
  });

  test('SellerLeaderboardParams value equality (family key)', () {
    final a = SellerLeaderboardParams(
        from: DateTime(2026, 10, 12), to: DateTime(2026, 10, 12), storeId: 's1');
    final b = SellerLeaderboardParams(
        from: DateTime(2026, 10, 12), to: DateTime(2026, 10, 12), storeId: 's1');
    final c = SellerLeaderboardParams(
        from: DateTime(2026, 10, 12), to: DateTime(2026, 10, 12));
    expect(a, b);
    expect(a.hashCode, b.hashCode);
    expect(a == c, isFalse);
  });
}

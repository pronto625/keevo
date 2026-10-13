import '../../domain/model/product_profitability_model.dart';
import '../../domain/model/seller_leaderboard_model.dart';

/// Abstract domain repository for profitability data.
///
/// Story 7.4 — Task 15.3.
abstract class ProfitabilityRepository {
  Future<List<ProductProfitabilityEntry>> getProductProfitability({
    required ProfitabilityParams params,
  });

  Future<ProductProfitabilityDetail> getProductProfitabilityDetail({
    required String productId,
    required ProfitabilityParams params,
  });

  Future<List<StorePerformanceEntry>> getStorePerformance({
    required StorePerformanceParams params,
  });

  /// FR44 seller leaderboard (v1s-14-2) — unranked order is not guaranteed;
  /// callers rank with `rankSellersBy`. `fromLocal` tells whether the Drift
  /// aggregation was served (offline or backend unreachable).
  Future<SellerLeaderboardResult> getSellerLeaderboard({
    required SellerLeaderboardParams params,
  });
}

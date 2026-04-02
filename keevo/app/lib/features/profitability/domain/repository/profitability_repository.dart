import '../../domain/model/product_profitability_model.dart';

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
}

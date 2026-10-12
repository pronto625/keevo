import 'package:dio/dio.dart';

import '../../domain/model/product_profitability_model.dart';
import '../../domain/model/seller_leaderboard_model.dart';

/// RemoteProfitabilityDatasource — fetches profitability data from the backend.
///
/// Follows the same pattern as RemoteReportHistoryDatasource (Story 7.2).
class RemoteProfitabilityDatasource {
  final Dio _dio;

  RemoteProfitabilityDatasource(this._dio);

  static const _basePath = '/api/v1/reporting/profitability';

  Future<List<ProductProfitabilityEntry>> fetchProducts({
    required String from,
    required String to,
    String sort = 'MARGIN_PCT_DESC',
    String? storeId,
  }) async {
    final queryParams = <String, dynamic>{
      'from': from,
      'to': to,
      'sort': sort,
      if (storeId != null) 'storeId': storeId,
    };

    final response = await _dio.get<Map<String, dynamic>>(
      '$_basePath/products',
      queryParameters: queryParams,
    );
    final items = (response.data!['data'] as List<dynamic>)
        .map((e) =>
            ProductProfitabilityEntry.fromJson(e as Map<String, dynamic>))
        .toList();
    return items;
  }

  Future<ProductProfitabilityDetail> fetchProductDetail({
    required String productId,
    required String from,
    required String to,
    String? storeId,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$_basePath/products/$productId',
      queryParameters: {
        'from': from,
        'to': to,
        if (storeId != null) 'storeId': storeId,
      },
    );
    return ProductProfitabilityDetail.fromJson(
        response.data!['data'] as Map<String, dynamic>);
  }

  Future<List<StorePerformanceEntry>> fetchStores({
    required String from,
    required String to,
    String metric = 'CA',
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$_basePath/stores',
      queryParameters: {'from': from, 'to': to, 'metric': metric},
    );
    final items = (response.data!['data'] as List<dynamic>)
        .map((e) =>
            StorePerformanceEntry.fromJson(e as Map<String, dynamic>))
        .toList();
    return items;
  }

  /// FR44 seller leaderboard (v1s-14-2, OWNER only). Fetched with the default
  /// metric: the app re-ranks locally.
  Future<List<SellerLeaderboardEntry>> fetchSellerLeaderboard({
    required String from,
    required String to,
    String? storeId,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/reporting/leaderboard',
      queryParameters: {
        'from': from,
        'to': to,
        if (storeId != null) 'storeId': storeId,
      },
    );
    return (response.data!['data'] as List<dynamic>)
        .map((e) =>
            SellerLeaderboardEntry.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}

import 'package:dio/dio.dart';

import '../../domain/model/store_product_stock_model.dart';
import '../../domain/model/store_stock_summary_model.dart';

/// RemoteMultiStoreStockDataSource — fetches multi-store stock data from the backend.
/// Story 3.2.
class RemoteMultiStoreStockDataSource {
  final Dio _dio;

  RemoteMultiStoreStockDataSource({required Dio dio}) : _dio = dio;

  Future<List<StoreStockSummaryModel>> getOverview() async {
    final resp = await _dio.get<Map<String, dynamic>>('/api/v1/stock/overview');
    final List<dynamic> data = resp.data!['data'] as List;
    return data
        .map((e) => StoreStockSummaryModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<({List<StoreProductStockModel> content, bool hasMore})>
      getStoreStockDetail(
    String storeId, {
    int page = 0,
    int size = 25,
    bool sortLowFirst = true,
    bool lowOnly = false,
  }) async {
    final resp = await _dio.get<Map<String, dynamic>>(
      '/api/v1/stock/stores/$storeId/products',
      queryParameters: {
        'page': page,
        'size': size,
        'sortLowFirst': sortLowFirst,
        'lowOnly': lowOnly,
      },
    );
    final pageData = resp.data!['data'] as Map<String, dynamic>;
    final List<dynamic> contentJson = pageData['content'] as List;
    final content = contentJson
        .map((e) =>
            StoreProductStockModel.fromJson(e as Map<String, dynamic>))
        .toList();
    final hasMore = !(pageData['last'] as bool? ?? true);
    return (content: content, hasMore: hasMore);
  }
}

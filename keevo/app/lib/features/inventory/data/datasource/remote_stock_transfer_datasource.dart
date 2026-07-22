import 'package:dio/dio.dart';

import '../../domain/model/stock_transfer_model.dart';

/// RemoteStockTransferDataSource — HTTP adapter for /api/v1/stock/transfers.
///
/// Story 3.3.
class RemoteStockTransferDataSource {
  final Dio _dio;

  const RemoteStockTransferDataSource({required Dio dio}) : _dio = dio;

  // Dart's DateTime.parse handles at most 6 fractional digits (microseconds).
  // Spring Boot returns nanosecond precision (9 digits) → FormatException.
  // Truncate to 6 digits before parsing.
  static Map<String, dynamic> _sanitize(Map<String, dynamic> json) {
    final ts = json['occurredAt'];
    if (ts is! String) return json;
    final fixed = ts.replaceFirstMapped(
      RegExp(r'(\.\d{6})\d+'),
      (m) => m.group(1)!,
    );
    if (fixed == ts) return json;
    return {...json, 'occurredAt': fixed};
  }

  /// POST /api/v1/stock/transfers
  Future<StockTransferModel> executeTransfer({
    required String sourceStoreId,
    required String destinationStoreId,
    required String productId,
    String? variantId,
    required int quantity,
    String? notes,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/stock/transfers',
      data: {
        'sourceStoreId': sourceStoreId,
        'destinationStoreId': destinationStoreId,
        'productId': productId,
        if (variantId != null) 'variantId': variantId,
        'quantity': quantity,
        if (notes != null) 'notes': notes,
      },
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return StockTransferModel.fromJson(_sanitize(data));
  }

  /// POST /api/v1/stock/transfers/{id}/complete
  Future<StockTransferModel> completeTransfer(String transferId) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/stock/transfers/$transferId/complete',
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return StockTransferModel.fromJson(_sanitize(data));
  }

  /// GET /api/v1/stock/transfers
  Future<List<StockTransferModel>> getHistory({
    String? sourceStoreId,
    String? destinationStoreId,
    int page = 0,
    int pageSize = 20,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/stock/transfers',
      queryParameters: {
        'page': page,
        'size': pageSize,
        if (sourceStoreId != null) 'source': sourceStoreId,
        if (destinationStoreId != null) 'destination': destinationStoreId,
      },
    );
    final body = response.data!['data'] as Map<String, dynamic>;
    final content = body['content'] as List<dynamic>;
    return content
        .map((e) => StockTransferModel.fromJson(
              _sanitize(e as Map<String, dynamic>)))
        .toList();
  }
}

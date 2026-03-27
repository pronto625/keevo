import 'package:dio/dio.dart';

/// QuickAddResult — result data from the quick-add endpoint.
class QuickAddResult {
  final String productId;
  final String productName;
  final String sku;
  final String categoryId;
  final int physicalQty;
  final String inventoryCountId;
  final String stockLevelId;

  const QuickAddResult({
    required this.productId,
    required this.productName,
    required this.sku,
    required this.categoryId,
    required this.physicalQty,
    required this.inventoryCountId,
    required this.stockLevelId,
  });

  factory QuickAddResult.fromJson(Map<String, dynamic> json) {
    return QuickAddResult(
      productId: json['productId'] as String,
      productName: json['productName'] as String,
      sku: json['sku'] as String,
      categoryId: json['categoryId'] as String,
      physicalQty: json['physicalQty'] as int,
      inventoryCountId: json['inventoryCountId'] as String,
      stockLevelId: json['stockLevelId'] as String,
    );
  }
}

/// RemoteQuickAddDatasource — POST /api/v1/inventory/sessions/{id}/quick-add.
/// Story 6.2.a.
class RemoteQuickAddDatasource {
  final Dio _dio;

  const RemoteQuickAddDatasource({required Dio dio}) : _dio = dio;

  /// Quick-add a product during inventory counting.
  /// Returns the result on 201; throws DioException on 409 (dedup) or other errors.
  Future<QuickAddResult> quickAdd({
    required String sessionId,
    required String name,
    required String categoryId,
    required int physicalQty,
    int? sellingPrice,
  }) async {
    final body = <String, dynamic>{
      'name': name,
      'categoryId': categoryId,
      'physicalQty': physicalQty,
    };
    if (sellingPrice != null) {
      body['sellingPrice'] = sellingPrice;
    }
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/inventory/sessions/$sessionId/quick-add',
      data: body,
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return QuickAddResult.fromJson(data);
  }
}

import 'package:dio/dio.dart';

import '../../domain/model/stock_level_model.dart';
import '../../domain/model/stock_movement_model.dart';

/// RemoteStockDataSource — HTTP adapter for /api/v1/products/{id}/stock.
///
/// Uses the authenticated [Dio] instance (via AuthInterceptor).
///
/// Story 2.3.
class RemoteStockDataSource {
  final Dio _dio;

  const RemoteStockDataSource({required Dio dio}) : _dio = dio;

  /// GET /api/v1/products/{productId}/stock
  Future<List<StockLevelModel>> getLevels(String productId) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/products/$productId/stock',
    );
    final data = (response.data!['data'] as List<dynamic>);
    return data
        .map((e) => StockLevelModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// POST /api/v1/products/{productId}/stock/entry
  Future<StockMovementModel> recordEntry({
    required String productId,
    String? variantId,
    required String storeId,
    required int quantity,
    String? notes,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/products/$productId/stock/entry',
      data: {
        'storeId': storeId,
        if (variantId != null) 'variantId': variantId,
        'quantity': quantity,
        if (notes != null) 'notes': notes,
      },
    );
    return _movementFromResponse(response.data!);
  }

  /// POST /api/v1/products/{productId}/stock/adjust
  Future<StockMovementModel> adjustStock({
    required String productId,
    String? variantId,
    required String storeId,
    required int newQuantity,
    required String notes,
  }) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/products/$productId/stock/adjust',
      data: {
        'storeId': storeId,
        if (variantId != null) 'variantId': variantId,
        'newQuantity': newQuantity,
        'notes': notes,
      },
    );
    return _movementFromResponse(response.data!);
  }

  /// PATCH /api/v1/products/{productId}/threshold
  Future<void> setThreshold({
    required String productId,
    required int minimumThreshold,
  }) async {
    await _dio.patch<void>(
      '/api/v1/products/$productId/threshold',
      data: {'minimumThreshold': minimumThreshold},
    );
  }

  /// GET /api/v1/products/{productId}/stock/history
  Future<List<StockMovementModel>> getHistory({
    required String productId,
    String? storeId,
    String? movementType,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int pageSize = 20,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/products/$productId/stock/history',
      queryParameters: {
        'page': page,
        'size': pageSize,
        if (storeId != null) 'storeId': storeId,
        if (movementType != null) 'type': movementType,
        // toUtc() ensures the 'Z' suffix is present so Spring can parse
        // the value as java.time.Instant (ISO_DATE_TIME without timezone fails).
        if (from != null) 'from': from.toUtc().toIso8601String(),
        if (to != null) 'to': to.toUtc().toIso8601String(),
      },
    );
    final content = (response.data!['data']['content'] as List<dynamic>);
    return content
        .map((e) => _movementFromJson(e as Map<String, dynamic>))
        .toList();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  StockMovementModel _movementFromResponse(Map<String, dynamic> body) =>
      _movementFromJson(body['data'] as Map<String, dynamic>);

  StockMovementModel _movementFromJson(Map<String, dynamic> json) =>
      StockMovementModel(
        id: json['id'] as String,
        productId: json['productId'] as String,
        variantId: json['variantId'] as String?,
        storeId: json['storeId'] as String,
        movementType: json['movementType'] as String,
        quantityBefore: (json['quantityBefore'] as int?) ?? 0,
        quantityDelta: (json['quantityChange'] as int?) ?? 0,
        quantityAfter: (json['quantityAfter'] as int?) ?? 0,
        actorId: json['actorId'] as String,
        notes: json['notes'] as String?,
        occurredAt: DateTime.parse(json['occurredAt'] as String),
      );
}

import 'package:dio/dio.dart';

import '../../domain/model/day_closure_model.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';

/// RemoteDayClosureDataSource — API operations for day closures.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class RemoteDayClosureDataSource {
  final Dio _dio;

  RemoteDayClosureDataSource(this._dio);

  /// Push a day closure to the backend.
  ///
  /// POST /api/v1/day-closures
  Future<void> pushClosure(DayClosure closure) async {
    final payload = {
      'storeId': closure.storeId,
    };

    await _dio.post('/api/v1/day-closures', data: payload);
  }

  /// Fetch sales history from the backend.
  ///
  /// GET /api/v1/sales/history?storeId=X&from=Y&to=Z&page=0&size=50
  Future<List<Sale>> fetchSalesHistory(SalesHistoryFilter filter,
      {int page = 0, int size = 50}) async {
    final response = await _dio.get(
      '/api/v1/sales/history',
      queryParameters: {
        'storeId': filter.storeId,
        'from': _formatDate(filter.from),
        'to': _formatDate(filter.to),
        'page': page,
        'size': size,
      },
    );

    final data = response.data;

    if (data == null || data['data'] == null || data['data']['content'] == null) {
      return [];
    }

    final content = data['data']['content'] as List;
    return content.map((json) => _mapToSale(json)).toList();
  }

  /// Format date as YYYY-MM-DD for API query parameter.
  String _formatDate(DateTime date) {
    return '${date.year.toString().padLeft(4, '0')}-'
        '${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')}';
  }

  /// Map JSON response to Sale model.
  Sale _mapToSale(Map<String, dynamic> json) {
    final itemsJson = json['items'] as List? ?? [];
    final items = itemsJson.map((i) => SaleItemModel(
          id: i['id'] ?? '',
          productId: i['productId'] ?? '',
          productName: i['productName'] ?? '',
          catalogueUnitPrice: i['catalogueUnitPrice'] ?? i['unitPrice'] ?? 0,
          appliedUnitPrice: i['unitPrice'] ?? 0,
          quantity: i['quantity'] ?? 0,
          subtotal: (i['unitPrice'] ?? 0) * (i['quantity'] ?? 0),
          variantId: i['variantId'],
        )).toList();

    return Sale(
      id: json['id'] ?? '',
      storeId: json['storeId'] ?? '',
      employeeId: json['employeeId'] ?? '',
      items: items,
      totalAmount: json['totalAmount'] ?? 0,
      discountAmount: json['discountAmount'] ?? 0,
      paymentMode: _parsePaymentMode(json['paymentMode']),
      clientId: json['clientId'],
      status: json['status'] ?? 'COMPLETED',
      occurredAt: json['occurredAt'] != null
          ? DateTime.parse(json['occurredAt'])
          : DateTime.now(),
      createdAt: json['createdAt'] != null
          ? DateTime.parse(json['createdAt'])
          : DateTime.now(),
    );
  }

  /// Parse payment mode from API string.
  PaymentModeEnum _parsePaymentMode(String? mode) {
    switch (mode) {
      case 'CASH':
        return PaymentModeEnum.cash;
      case 'MOBILE_MONEY':
        return PaymentModeEnum.mobileMoney;
      default:
        return PaymentModeEnum.cash;
    }
  }
}

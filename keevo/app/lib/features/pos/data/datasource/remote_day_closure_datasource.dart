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
  /// POST /api/v1/day-closures. Returns the summary the server computed, which
  /// is authoritative: it also counts sales made offline before the previous
  /// closure but synced after it. Null when the response carries no summary.
  Future<DayClosureSummary?> pushClosure(DayClosure closure) async {
    // id + closedAt: the server keeps the local closure id (no duplicate row
    // after pull) and closes the day the closure was made.
    final payload = {
      'storeId': closure.storeId,
      'id': closure.id,
      'closedAt': closure.closedAt.toUtc().toIso8601String(),
    };

    final response = await _dio.post('/api/v1/day-closures', data: payload);
    final data = response.data;
    final body = data is Map ? data['data'] : null;
    final summary = body is Map ? body['summary'] : null;
    if (summary is! Map) return null;
    final topProductId = summary['topProductId'] as String?;
    return DayClosureSummary(
      totalSales: summary['totalSales'] as int? ?? 0,
      totalRevenue: summary['totalRevenue'] as int? ?? 0,
      cashAmount: summary['cashAmount'] as int? ?? 0,
      momoAmount: summary['momoAmount'] as int? ?? 0,
      topProductId: topProductId,
      topProductName: summary['topProductName'] as String?,
      topProductQty: summary['topProductQty'] as int? ?? 0,
      // The server does not return the top product's revenue: keep the local
      // figure when it is about the same product.
      topProductRevenue: topProductId != null &&
              topProductId == closure.summary.topProductId
          ? closure.summary.topProductRevenue
          : 0,
      pendingSalesCount: summary['pendingSalesCount'] as int? ?? 0,
      pendingSalesTotal: summary['pendingSalesTotal'] as int? ?? 0,
    );
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

  /// Fetch every page of the sales history for [filter].
  ///
  /// The backend caps a page at 100 items (newest first), so a busy day spans
  /// several pages; stopping at page 0 would silently drop the oldest sales.
  Future<List<Sale>> fetchAllSalesHistory(SalesHistoryFilter filter,
      {int pageSize = 100}) async {
    // Keyed by id: pages are offset-based and newest first, so a sale
    // recorded between two page requests shifts the next page by one and its
    // first row repeats the previous page's last one.
    final all = <String, Sale>{};
    for (var page = 0;; page++) {
      final batch = await fetchSalesHistory(filter, page: page, size: pageSize);
      for (final sale in batch) {
        all.putIfAbsent(sale.id, () => sale);
      }
      if (batch.length < pageSize) break;
    }
    return all.values.toList();
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
          catalogueUnitPrice: i['catalogueUnitPrice'] ??
              i['appliedUnitPrice'] ?? i['unitPrice'] ?? 0,
          appliedUnitPrice: i['appliedUnitPrice'] ?? i['unitPrice'] ?? 0,
          quantity: i['quantity'] ?? 0,
          subtotal: i['subtotal'] ??
              (i['appliedUnitPrice'] ?? i['unitPrice'] ?? 0) *
                  (i['quantity'] ?? 0),
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

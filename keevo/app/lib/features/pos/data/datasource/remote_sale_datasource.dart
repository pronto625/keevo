import 'package:dio/dio.dart';
import 'package:intl/intl.dart';

import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';

/// RemoteSaleDataSource — pushes sales to the backend via REST.
///
/// Clean adapter: throws on failure — the repository layer handles fallback.
class RemoteSaleDataSource {
  final Dio _dio;

  const RemoteSaleDataSource(this._dio);

  /// POST /api/v1/sales — expects HTTP 201.
  /// Throws [DioException] on network error or non-2xx response.
  Future<void> pushSale(Sale sale) async {
    await _dio.post(
      '/api/v1/sales',
      data: {
        'saleId': sale.id,
        'storeId': sale.storeId,
        'paymentMode': sale.paymentMode.value,
        'mobileMoneyRef': sale.mobileMoneyRef,
        'clientId': sale.clientId,
        'discountAmount': sale.discountAmount,
        if (sale.status == 'PENDING_VALIDATION') 'status': sale.status,
        'items': sale.items
            .map((i) => {
                  'itemId': i.id,
                  'productId': i.productId,
                  'variantId': i.variantId,
                  'productName': i.productName,
                  'catalogueUnitPrice': i.catalogueUnitPrice,
                  'appliedUnitPrice': i.appliedUnitPrice,
                  'quantity': i.quantity,
                })
            .toList(),
      },
    );
  }

  /// POST /api/v1/sales/{id}/validate — OWNER-only.
  Future<void> validateSale(String saleId, String justification,
      {Map<String, String>? productIdRemappings,
      Map<String, int>? initialStockEntries}) async {
    await _dio.post(
      '/api/v1/sales/$saleId/validate',
      data: {
        'justification': justification,
        if (productIdRemappings != null && productIdRemappings.isNotEmpty)
          'productIdRemappings': productIdRemappings,
        if (initialStockEntries != null && initialStockEntries.isNotEmpty)
          'initialStockEntries': initialStockEntries,
      },
    );
  }

  /// POST /api/v1/sales/{id}/cancel — OWNER-only.
  Future<void> cancelSale(String saleId, String justification) async {
    await _dio.post(
      '/api/v1/sales/$saleId/cancel',
      data: {'justification': justification},
    );
  }

  static final _dateFormat = DateFormat('yyyy-MM-dd');

  /// GET /api/v1/sales/history — fetch sales history from backend.
  Future<List<Sale>> getSalesHistory(SalesHistoryFilter filter) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/sales/history',
      queryParameters: {
        'storeId': filter.storeId,
        'from': _dateFormat.format(filter.from),
        'to': _dateFormat.format(filter.to),
        if (filter.employeeId != null) 'employeeId': filter.employeeId,
        'page': 0,
        'size': 100,
      },
    );

    final data = response.data!['data'] as Map<String, dynamic>;
    final content = data['content'] as List<dynamic>;

    return content.map((item) {
      final json = item as Map<String, dynamic>;
      final items = (json['items'] as List<dynamic>?)
              ?.map((i) {
                final ij = i as Map<String, dynamic>;
                return SaleItemModel(
                  id: ij['id'] as String? ?? '',
                  productId: ij['productId'] as String? ?? '',
                  variantId: ij['variantId'] as String?,
                  productName: ij['productName'] as String? ?? '',
                  catalogueUnitPrice:
                      (ij['catalogueUnitPrice'] as num?)?.toInt() ?? 0,
                  appliedUnitPrice:
                      (ij['appliedUnitPrice'] as num?)?.toInt() ?? 0,
                  quantity: (ij['quantity'] as num?)?.toInt() ?? 0,
                  subtotal: (ij['subtotal'] as num?)?.toInt() ?? 0,
                );
              })
              .toList() ??
          [];

      return Sale(
        id: json['id'] as String? ?? '',
        storeId: json['storeId'] as String? ?? '',
        employeeId: json['employeeId'] as String? ?? '',
        clientId: json['clientId'] as String?,
        paymentMode: PaymentModeEnum.values.firstWhere(
          (m) => m.value == (json['paymentMode'] as String? ?? 'CASH'),
          orElse: () => PaymentModeEnum.cash,
        ),
        totalAmount: (json['totalAmount'] as num?)?.toInt() ?? 0,
        discountAmount: (json['discountAmount'] as num?)?.toInt() ?? 0,
        status: json['status'] as String? ?? 'COMPLETED',
        items: items,
        occurredAt: DateTime.tryParse(json['occurredAt'] as String? ?? '') ??
            DateTime.now(),
        createdAt: DateTime.tryParse(json['createdAt'] as String? ?? '') ??
            DateTime.now(),
      );
    }).toList();
  }
}

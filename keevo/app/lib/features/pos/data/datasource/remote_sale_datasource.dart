import 'package:dio/dio.dart';

import '../../domain/model/sale_model.dart';

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
}

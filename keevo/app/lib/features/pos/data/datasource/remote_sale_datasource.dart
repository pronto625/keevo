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
        'items': sale.items
            .map((i) => {
                  'productId': i.productId,
                  'variantId': i.variantId,
                  'productName': i.productName,
                  'appliedUnitPrice': i.appliedUnitPrice,
                  'quantity': i.quantity,
                })
            .toList(),
      },
    );
  }
}

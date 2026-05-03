import 'package:uuid/uuid.dart';

import '../model/cart_item.dart';
import '../model/payment_mode_enum.dart';
import '../model/sale_model.dart';
import '../repository/sale_repository.dart';

/// RecordSaleUseCase — orchestrates local sale recording.
///
/// Builds a [Sale] from cart items + metadata, delegates to [SaleRepository].
class RecordSaleUseCase {
  final SaleRepository _repository;

  const RecordSaleUseCase(this._repository);

  Future<Sale> execute({
    required List<CartItem> cart,
    required PaymentModeEnum mode,
    required String storeId,
    required String employeeId,
    String? clientId,
    String? mobileRef,
    int discountAmount = 0,
    String status = 'COMPLETED',
    List<String> originalDraftProductIds = const [],
    Map<String, int> initialStockEntries = const {},
  }) async {
    final now = DateTime.now();
    final saleId = const Uuid().v4();

    final items = cart.map((c) => SaleItemModel(
      id: const Uuid().v4(),
      productId: c.productId,
      variantId: c.variantId,
      productName: c.productName,
      catalogueUnitPrice: c.unitPrice,
      appliedUnitPrice: c.appliedUnitPrice,
      quantity: c.quantity,
      subtotal: c.subtotal,
    )).toList();

    final subtotal = items.fold(0, (sum, i) => sum + i.subtotal);

    final sale = Sale(
      id: saleId,
      storeId: storeId,
      employeeId: employeeId,
      clientId: clientId,
      paymentMode: mode,
      mobileMoneyRef: mobileRef,
      totalAmount: subtotal - discountAmount,
      discountAmount: discountAmount,
      status: status,
      items: items,
      occurredAt: now,
      createdAt: now,
      originalDraftProductIds: originalDraftProductIds,
      initialStockEntries: initialStockEntries,
    );

    await _repository.recordSale(sale);
    return sale;
  }
}

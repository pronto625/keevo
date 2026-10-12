import 'package:uuid/uuid.dart';

import '../model/cart_item.dart';
import '../model/payment_mode_enum.dart';
import '../model/sale_model.dart';
import '../model/sale_status.dart';
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
    String status = SaleStatus.completedValue,
    List<String> originalDraftProductIds = const [],
    Map<String, int> initialStockEntries = const {},
    Map<String, String> initialStockMovementIds = const {},
    bool promoteDraftProducts = true,
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
    final discount = discountAmount.clamp(0, subtotal);

    final sale = Sale(
      id: saleId,
      storeId: storeId,
      employeeId: employeeId,
      clientId: clientId,
      paymentMode: mode,
      mobileMoneyRef: mobileRef,
      totalAmount: subtotal - discount,
      discountAmount: discount,
      status: status,
      items: items,
      occurredAt: now,
      createdAt: now,
      originalDraftProductIds: originalDraftProductIds,
      initialStockEntries: initialStockEntries,
      initialStockMovementIds: initialStockMovementIds,
      promoteDraftProducts: promoteDraftProducts,
    );

    await _repository.recordSale(sale);
    return sale;
  }
}

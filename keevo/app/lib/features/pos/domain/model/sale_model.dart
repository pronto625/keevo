import 'payment_mode_enum.dart';

/// SaleItemModel — snapshot of a line item at sale time.
class SaleItemModel {
  final String id;
  final String productId;
  final String? variantId;
  final String productName;
  final int catalogueUnitPrice;
  final int appliedUnitPrice;
  final int quantity;
  final int subtotal;

  const SaleItemModel({
    required this.id,
    required this.productId,
    this.variantId,
    required this.productName,
    required this.catalogueUnitPrice,
    required this.appliedUnitPrice,
    required this.quantity,
    required this.subtotal,
  });
}

/// Sale — domain model representing a completed POS transaction.
class Sale {
  final String id;
  final String storeId;
  final String employeeId;
  final String? clientId;
  final PaymentModeEnum paymentMode;
  final String? mobileMoneyRef;
  final int totalAmount;
  final int discountAmount;
  final String status;
  final List<SaleItemModel> items;
  final DateTime occurredAt;
  final DateTime createdAt;

  const Sale({
    required this.id,
    required this.storeId,
    required this.employeeId,
    this.clientId,
    required this.paymentMode,
    this.mobileMoneyRef,
    required this.totalAmount,
    this.discountAmount = 0,
    this.status = 'COMPLETED',
    required this.items,
    required this.occurredAt,
    required this.createdAt,
  });
}

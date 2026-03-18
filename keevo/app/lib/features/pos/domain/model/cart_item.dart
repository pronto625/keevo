/// CartItem — pure Dart model for POS cart entries.
///
/// [appliedUnitPrice] equals [unitPrice] in Story 4.1;
/// future stories may allow overrides (discount, negotiated price).
class CartItem {
  final String id; // product id or variant id (unique key in cart)
  final String productId;
  final String? variantId;
  final String productName;
  final String? photoUrl;
  final int unitPrice; // catalogue price (XAF)
  final int appliedUnitPrice; // = unitPrice in 4.1
  final int quantity;
  final String productStatus; // 'ACTIVE' or 'DRAFT' (Story 4.3)

  CartItem({
    required this.id,
    required this.productId,
    this.variantId,
    required this.productName,
    this.photoUrl,
    required this.unitPrice,
    required this.appliedUnitPrice,
    required this.quantity,
    this.productStatus = 'ACTIVE',
  }) : assert(quantity > 0, 'quantity must be > 0');

  int get subtotal => appliedUnitPrice * quantity;

  bool get isPriceOverridden => appliedUnitPrice != unitPrice;

  bool get isDraft => productStatus == 'DRAFT';

  CartItem copyWith({int? quantity, int? appliedUnitPrice, String? productStatus}) {
    return CartItem(
      id: id,
      productId: productId,
      variantId: variantId,
      productName: productName,
      photoUrl: photoUrl,
      unitPrice: unitPrice,
      appliedUnitPrice: appliedUnitPrice ?? this.appliedUnitPrice,
      quantity: quantity ?? this.quantity,
      productStatus: productStatus ?? this.productStatus,
    );
  }
}

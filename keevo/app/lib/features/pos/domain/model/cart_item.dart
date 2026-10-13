import '../../../catalog/domain/model/product_status.dart';

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
  final ProductStatus productStatus; // Story 4.3
  /// Stock the POS showed when the product was added; null = unknown (draft).
  /// The cart never goes above it — the sale would be refused at checkout.
  final int? availableStock;

  CartItem({
    required this.id,
    required this.productId,
    this.variantId,
    required this.productName,
    this.photoUrl,
    required this.unitPrice,
    required this.appliedUnitPrice,
    required this.quantity,
    this.productStatus = ProductStatus.active,
    this.availableStock,
  }) : assert(quantity > 0, 'quantity must be > 0');

  /// Message shown when the cart refuses to go above [availableStock].
  String get stockLimitMessage =>
      'Stock disponible atteint pour « $productName » ($availableStock).';

  /// Whether [qty] fits the known stock (always true when it is unknown).
  bool allows(int qty) => availableStock == null || qty <= availableStock!;

  int get subtotal => appliedUnitPrice * quantity;

  bool get isPriceOverridden => appliedUnitPrice != unitPrice;

  bool get isDraft => productStatus == ProductStatus.draft;

  CartItem copyWith({int? quantity, int? appliedUnitPrice, ProductStatus? productStatus}) {
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
      availableStock: availableStock,
    );
  }
}

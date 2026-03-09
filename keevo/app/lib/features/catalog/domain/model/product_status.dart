/// ProductStatus — lifecycle status of a product.
///
/// - [active]: fully created, visible in POS catalogue
/// - [draft]: created on-the-fly during a POS sale (Story 2.4)
enum ProductStatus {
  active,
  draft;

  /// Convert to backend string value.
  String get value => name.toUpperCase();

  /// Parse from backend JSON string.
  static ProductStatus fromString(String s) =>
      ProductStatus.values.firstWhere(
        (e) => e.value == s.toUpperCase(),
        orElse: () => ProductStatus.active,
      );
}

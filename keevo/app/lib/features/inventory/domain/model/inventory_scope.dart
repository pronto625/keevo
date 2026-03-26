/// InventoryScope — scope of an inventory counting session.
/// Story 6.1.
enum InventoryScope {
  full,
  partial;

  String toJson() => name.toUpperCase();

  static InventoryScope fromJson(String value) {
    switch (value.toUpperCase()) {
      case 'FULL':
        return InventoryScope.full;
      case 'PARTIAL':
        return InventoryScope.partial;
      default:
        throw ArgumentError('Unknown InventoryScope: $value');
    }
  }
}

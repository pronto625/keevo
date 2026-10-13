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

/// French label of an inventory scope code (`FULL` → « Complet »); an
/// unknown code is humanized, never shown raw (v1s-16-20).
String inventoryScopeLabel(String scope) {
  switch (scope.toUpperCase()) {
    case 'FULL':
      return 'Complet';
    case 'PARTIAL':
      return 'Partiel';
    case 'CATEGORY':
      return 'Par catégorie';
  }
  final s = scope.replaceAll('_', ' ').trim().toLowerCase();
  return s.isEmpty ? scope : s[0].toUpperCase() + s.substring(1);
}

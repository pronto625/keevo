/// StoreType — enum identifying whether a location is a retail store or a warehouse.
///
/// Story 3.1 — AC3: only one WAREHOUSE per tenant is allowed.
enum StoreType {
  store,
  warehouse;

  /// Human-readable French label for UI display.
  String get displayName => switch (this) {
        StoreType.store => 'Boutique',
        StoreType.warehouse => 'Warehouse',
      };

  /// Icon code point for quick visual differentiation in the UI.
  String get icon => switch (this) {
        StoreType.store => '🏪',
        StoreType.warehouse => '🏭',
      };

  /// Parse a backend-serialised uppercase string ('STORE' | 'WAREHOUSE').
  /// Falls back to [StoreType.store] for unknown values.
  static StoreType fromString(String value) => StoreType.values.firstWhere(
        (e) => e.name.toUpperCase() == value.toUpperCase(),
        orElse: () => StoreType.store,
      );
}

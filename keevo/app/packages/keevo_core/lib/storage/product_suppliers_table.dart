import 'package:drift/drift.dart';

/// ProductSuppliers — many-to-many join table between products and suppliers.
///
/// Mirror of the `product_suppliers` table in the PostgreSQL tenant schema.
/// Composite primary key: (supplierId, productId).
/// Schema v6: added for Story 2.5 — Gestion Clients & Fournisseurs.
class ProductSuppliers extends Table {
  TextColumn get supplierId => text()();
  TextColumn get productId => text()();

  @override
  Set<Column> get primaryKey => {supplierId, productId};
}

import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/supplier_model.dart';

/// LocalSupplierDataSource — Drift-backed offline-first supplier store (Story 2.5).
class LocalSupplierDataSource {
  final AppDatabase _db;

  LocalSupplierDataSource(this._db);

  Future<List<SupplierModel>> getAll({bool includeArchived = false}) async {
    final rows = await (_db.select(_db.suppliers)
          ..where((s) => includeArchived
              ? const Constant(true)
              : s.archived.equals(false))
          ..orderBy([(s) => OrderingTerm.asc(s.name)]))
        .get();
    final allLinks = await _db.select(_db.productSuppliers).get();
    final linksBySupplier = <String, List<String>>{};
    for (final l in allLinks) {
      linksBySupplier.putIfAbsent(l.supplierId, () => []).add(l.productId);
    }
    return rows
        .map((r) => _toModel(r, productIds: linksBySupplier[r.id] ?? []))
        .toList();
  }

  Future<List<SupplierModel>> search(String query) async {
    final q = '%${query.toLowerCase()}%';
    final rows = await (_db.select(_db.suppliers)
          ..where((s) =>
              s.archived.equals(false) & s.name.lower().like(q))
          ..orderBy([(s) => OrderingTerm.asc(s.name)]))
        .get();
    final allLinks = await _db.select(_db.productSuppliers).get();
    final linksBySupplier = <String, List<String>>{};
    for (final l in allLinks) {
      linksBySupplier.putIfAbsent(l.supplierId, () => []).add(l.productId);
    }
    return rows
        .map((r) => _toModel(r, productIds: linksBySupplier[r.id] ?? []))
        .toList();
  }

  Future<SupplierModel?> getById(String id) async {
    final row = await (_db.select(_db.suppliers)
          ..where((s) => s.id.equals(id)))
        .getSingleOrNull();
    if (row == null) return null;
    final productIds = await _getProductIds(id);
    return _toModel(row, productIds: productIds);
  }

  Future<List<String>> _getProductIds(String supplierId) async {
    final links = await (_db.select(_db.productSuppliers)
          ..where((ps) => ps.supplierId.equals(supplierId)))
        .get();
    return links.map((l) => l.productId).toList();
  }

  Future<SupplierModel> upsert(SupplierModel model) async {
    await _db.into(_db.suppliers).insertOnConflictUpdate(
          SuppliersCompanion(
            id: Value(model.id),
            name: Value(model.name),
            phone: Value(model.phone),
            email: Value(model.email),
            archived: Value(model.archived),
            createdAt: Value(model.createdAt),
            updatedAt: Value(model.updatedAt),
          ),
        );
    // Sync product links.
    await (_db.delete(_db.productSuppliers)
          ..where((ps) => ps.supplierId.equals(model.id)))
        .go();
    for (final pid in model.productIds) {
      await _db.into(_db.productSuppliers).insertOnConflictUpdate(
            ProductSuppliersCompanion(
              supplierId: Value(model.id),
              productId: Value(pid),
            ),
          );
    }
    return model;
  }

  Future<void> archive(String id) async {
    await (_db.update(_db.suppliers)..where((s) => s.id.equals(id)))
        .write(const SuppliersCompanion(archived: Value(true)));
  }

  SupplierModel _toModel(Supplier row, {List<String> productIds = const []}) =>
      SupplierModel(
        id: row.id,
        name: row.name,
        phone: row.phone,
        email: row.email,
        archived: row.archived,
        productIds: productIds,
        createdAt: row.createdAt,
        updatedAt: row.updatedAt,
      );
}

import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';

/// LocalStoreDataSource — Drift-backed offline-first store (Story 3.1).
class LocalStoreDataSource {
  final AppDatabase _db;

  LocalStoreDataSource(this._db);

  Future<List<StoreModel>> getAll({bool includeInactive = false}) async {
    final rows = await (_db.select(_db.stores)
          ..where((s) =>
              includeInactive ? const Constant(true) : s.isActive.equals(true))
          ..orderBy([(s) => OrderingTerm.asc(s.createdAt)]))
        .get();
    return rows.map(_toModel).toList();
  }

  Future<StoreModel?> getById(String id) async {
    final row = await (_db.select(_db.stores)
          ..where((s) => s.id.equals(id)))
        .getSingleOrNull();
    return row == null ? null : _toModel(row);
  }

  Future<StoreModel> upsert(StoreModel model) async {
    await _db.into(_db.stores).insertOnConflictUpdate(
          StoresCompanion(
            id: Value(model.id),
            name: Value(model.name),
            tenantId: const Value(''), // set by backend; local stub
            type: Value(model.type.name.toUpperCase()),
            address: Value(model.address),
            phone: Value(model.phone),
            isActive: Value(model.isActive),
            createdAt: Value(model.createdAt),
            updatedAt: Value(model.updatedAt),
          ),
        );
    return model;
  }

  Future<void> deactivate(String id) async {
    await (_db.update(_db.stores)..where((s) => s.id.equals(id)))
        .write(const StoresCompanion(isActive: Value(false)));
  }

  StoreModel _toModel(Store row) => StoreModel(
        id: row.id,
        name: row.name,
        type: StoreType.fromString(row.type),
        address: row.address,
        phone: row.phone,
        isActive: row.isActive,
        createdAt: row.createdAt,
        updatedAt: row.updatedAt,
      );
}

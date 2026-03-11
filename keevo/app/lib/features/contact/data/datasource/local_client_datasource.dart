import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/client_model.dart';

/// LocalClientDataSource — Drift-backed offline-first client store (Story 2.5).
class LocalClientDataSource {
  final AppDatabase _db;

  LocalClientDataSource(this._db);

  Future<List<ClientModel>> getAll({bool includeArchived = false}) async {
    final rows = await (_db.select(_db.clients)
          ..where((c) => includeArchived
              ? const Constant(true)
              : c.archived.equals(false))
          ..orderBy([(c) => OrderingTerm.asc(c.name)]))
        .get();
    return rows.map(_toModel).toList();
  }

  Future<List<ClientModel>> search(String query) async {
    final q = '%${query.toLowerCase()}%';
    final rows = await (_db.select(_db.clients)
          ..where((c) =>
              c.archived.equals(false) &
              (c.name.lower().like(q) | c.phone.lower().like(q)))
          ..orderBy([(c) => OrderingTerm.asc(c.name)]))
        .get();
    return rows.map(_toModel).toList();
  }

  Future<ClientModel?> getById(String id) async {
    final row = await (_db.select(_db.clients)
          ..where((c) => c.id.equals(id)))
        .getSingleOrNull();
    return row != null ? _toModel(row) : null;
  }

  Future<ClientModel> upsert(ClientModel model) async {
    await _db.into(_db.clients).insertOnConflictUpdate(
          ClientsCompanion(
            id: Value(model.id),
            name: Value(model.name),
            phone: Value(model.phone),
            email: Value(model.email),
            notes: Value(model.notes),
            archived: Value(model.archived),
            createdAt: Value(model.createdAt),
            updatedAt: Value(model.updatedAt),
          ),
        );
    return model;
  }

  Future<void> archive(String id) async {
    await (_db.update(_db.clients)..where((c) => c.id.equals(id)))
        .write(const ClientsCompanion(archived: Value(true)));
  }

  ClientModel _toModel(Client row) => ClientModel(
        id: row.id,
        name: row.name,
        phone: row.phone,
        email: row.email,
        notes: row.notes,
        archived: row.archived,
        createdAt: row.createdAt,
        updatedAt: row.updatedAt,
      );
}

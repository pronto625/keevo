import 'dart:convert';

import 'package:drift/drift.dart';
import 'package:flutter/foundation.dart';

import '../storage/app_database.dart';

/// Story 15.5b — one record of a module package as stored on the device
/// (`module_records`, see `ModuleRecords`).
@immutable
class ModuleRecord {
  final String module;
  final String entity;
  final String id;
  final Map<String, dynamic> json;
  final DateTime updatedAt;

  /// Written on this device and not confirmed by the server yet.
  final bool pending;

  const ModuleRecord({
    required this.module,
    required this.entity,
    required this.id,
    required this.json,
    required this.updatedAt,
    this.pending = false,
  });
}

/// Story 15.5b — the local store of the records of module packages, on the
/// app's single [AppDatabase] connection (so a record written inside a
/// transaction commits together with the sync operation queued with it).
///
/// A module reads and writes only its own `module` rows; the app shell
/// writes the rows a pull brings through the module's registered merger
/// (`KeevoModuleUi.pulledEntities`, [replaceUnlessPending]).
class ModuleRecordStore {
  final AppDatabase _db;

  ModuleRecordStore(this._db);

  /// The database, for a caller that wraps a write and its queued sync
  /// operation in one transaction.
  AppDatabase get database => _db;

  static ModuleRecord _record(ModuleRecordData row) {
    Map<String, dynamic> json;
    try {
      final decoded = jsonDecode(row.json);
      json = decoded is Map<String, dynamic> ? decoded : <String, dynamic>{};
    } on FormatException {
      json = <String, dynamic>{};
    }
    return ModuleRecord(
      module: row.module,
      entity: row.entity,
      id: row.id,
      json: json,
      updatedAt: row.updatedAt,
      pending: row.pending,
    );
  }

  SimpleSelectStatement<$ModuleRecordsTable, ModuleRecordData> _query(
    String module,
    String entity,
  ) =>
      _db.select(_db.moduleRecords)
        ..where((t) => t.module.equals(module) & t.entity.equals(entity));

  /// The record [id], null when absent.
  Future<ModuleRecord?> get(String module, String entity, String id) async {
    final row = await (_query(
      module,
      entity,
    )..where((t) => t.id.equals(id))).getSingleOrNull();
    return row == null ? null : _record(row);
  }

  /// Every record of [entity], most recently changed first.
  Future<List<ModuleRecord>> list(String module, String entity) async {
    final rows = await (_query(
      module,
      entity,
    )..orderBy([(t) => OrderingTerm.desc(t.updatedAt)])).get();
    return rows.map(_record).toList();
  }

  /// [list], emitted again on every change of the table.
  Stream<List<ModuleRecord>> watch(String module, String entity) =>
      (_query(module, entity)..orderBy([(t) => OrderingTerm.desc(t.updatedAt)]))
          .watch()
          .map((rows) => rows.map(_record).toList());

  /// The record [id], emitted again on every change (null once deleted).
  Stream<ModuleRecord?> watchOne(String module, String entity, String id) =>
      (_query(module, entity)..where((t) => t.id.equals(id)))
          .watchSingleOrNull()
          .map((row) => row == null ? null : _record(row));

  /// Inserts or replaces the record.
  Future<void> put(
    String module,
    String entity,
    String id,
    Map<String, dynamic> json, {
    DateTime? updatedAt,
    bool pending = false,
  }) => _db
      .into(_db.moduleRecords)
      .insertOnConflictUpdate(
        ModuleRecordsCompanion.insert(
          module: module,
          entity: entity,
          id: id,
          json: jsonEncode(json),
          updatedAt: updatedAt ?? DateTime.now(),
          pending: Value(pending),
        ),
      );

  /// Deletes the record; false when there was none.
  Future<bool> delete(String module, String entity, String id) async {
    final count =
        await (_db.delete(_db.moduleRecords)..where(
              (t) =>
                  t.module.equals(module) &
                  t.entity.equals(entity) &
                  t.id.equals(id),
            ))
            .go();
    return count > 0;
  }

  /// The record [id] of any module and entity (the app shell's view: it
  /// knows a queued operation's entity id, not its module), null when absent.
  Future<ModuleRecord?> findById(String id) async {
    final row =
        await (_db.select(_db.moduleRecords)
              ..where((t) => t.id.equals(id))
              ..limit(1))
            .getSingleOrNull();
    return row == null ? null : _record(row);
  }
}

/// Story 15.5b — merges the rows a pull brought for one entity of a module
/// into the local store. [protectedIds]: ids with a sync operation still
/// queued on the device (pending or held) — their local copy is kept, and
/// merged by a later pull once those operations are applied (same rule as
/// the app's own entities).
typedef ModuleEntityMerger =
    Future<void> Function(
      ModuleRecordStore store,
      List<Map<String, dynamic>> rows,
      Set<String> protectedIds,
    );

/// The usual merger: each pulled row (with an `id`, and an ISO-8601
/// `updatedAt` when known) replaces the local copy — no longer pending —
/// unless its id is protected (an operation of it still queued). Story 15.5b:
/// a tombstone (`{id, deleted: true}`, e.g. a draft deleted on another
/// device) removes the local copy under the same rule; a row older than a
/// confirmed (not pending) local copy is skipped — it would roll the record
/// back (a pending copy is always replaced once unprotected: its time is the
/// device's clock, not comparable).
ModuleEntityMerger replaceUnlessPending({
  required String module,
  required String entity,
}) => (store, rows, protectedIds) async {
  for (final row in rows) {
    final id = row['id'];
    if (id is! String || id.isEmpty || protectedIds.contains(id)) continue;
    if (row['deleted'] == true) {
      await store.delete(module, entity, id);
      continue;
    }
    final raw = row['updatedAt'];
    final updated = raw is String ? DateTime.tryParse(raw)?.toLocal() : null;
    if (updated != null) {
      final local = await store.get(module, entity, id);
      if (local != null &&
          !local.pending &&
          updated.isBefore(local.updatedAt)) {
        continue;
      }
    }
    await store.put(module, entity, id, row, updatedAt: updated);
  }
};

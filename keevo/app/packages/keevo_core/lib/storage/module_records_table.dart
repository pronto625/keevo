import 'package:drift/drift.dart';

/// Story 15.5b — local copy of the records of a module package (e.g. the
/// invoices of `keevo_invoicing`), one generic table for every module: a
/// module cannot add its own Drift table to [AppDatabase] (it lives in
/// `keevo_core`, which knows no module), so it stores its records here as
/// JSON, keyed by (module, entity, id).
///
/// Columns:
///   module    — module id of the backend manifests (`invoicing`)
///   entity    — record type inside the module (`invoices`)
///   id        — record id (UUID generated on the device or by the server)
///   json      — the record, in the shape the module reads (for invoices: the
///               API's `InvoiceResponseDto`)
///   updatedAt — last change (server `updatedAt`, or the local write time)
///   pending   — written on this device and not confirmed by the server yet
///               (an offline creation or change whose sync operation is
///               queued); a pulled server row replaces it once no operation
///               of the record is queued any more.
@DataClassName('ModuleRecordData')
class ModuleRecords extends Table {
  TextColumn get module => text()();
  TextColumn get entity => text()();
  TextColumn get id => text()();
  TextColumn get json => text()();
  DateTimeColumn get updatedAt => dateTime()();
  BoolColumn get pending => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {module, entity, id};
}

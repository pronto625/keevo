import 'package:drift/drift.dart';

/// Clients — tenant contact book: customer records.
///
/// Mirror of the `clients` table in the PostgreSQL tenant schema.
/// [id] is a UUID string generated server-side.
/// Schema v6: added for Story 2.5 — Gestion Clients & Fournisseurs.
class Clients extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get phone => text()();
  TextColumn get email => text().nullable()();
  TextColumn get notes => text().nullable()();

  /// Soft-delete flag — archived clients are hidden by default.
  BoolColumn get archived => boolean().withDefault(const Constant(false))();

  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

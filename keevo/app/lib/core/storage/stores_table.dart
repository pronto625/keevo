import 'package:drift/drift.dart';

/// Stores — physical retail locations.
class Stores extends Table {
  TextColumn get id => text()();
  TextColumn get name => text()();
  TextColumn get tenantId => text()();
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();
  DateTimeColumn get createdAt => dateTime()();
  DateTimeColumn get updatedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

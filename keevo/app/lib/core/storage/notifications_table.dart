import 'package:drift/drift.dart';

/// Drift table for local notification storage — Story 8.0.
class Notifications extends Table {
  TextColumn get id => text()();
  TextColumn get type => text()();
  TextColumn get title => text()();
  TextColumn get body => text()();
  TextColumn get deepLink => text().nullable()();
  BoolColumn get isRead => boolean().withDefault(const Constant(false))();
  DateTimeColumn get receivedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

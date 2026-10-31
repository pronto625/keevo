import 'package:drift/drift.dart';

/// Drift table for local sync event history (Story 5.5 — AC3).
/// Stores the last 50 sync events for display in SyncSettingsPage > Historique tab.
class SyncEvents extends Table {
  TextColumn get id => text()();
  TextColumn get type => text()(); // 'PUSH' | 'PULL' | 'DIAGNOSTIC'
  TextColumn get status => text()(); // 'SUCCESS' | 'FAILED' | 'WARN' | 'OK'
  IntColumn get operationCount =>
      integer().withDefault(const Constant(0))();
  TextColumn get errorMessage => text().nullable()();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

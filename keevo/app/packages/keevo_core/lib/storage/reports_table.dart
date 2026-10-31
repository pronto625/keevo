import 'package:drift/drift.dart';

/// Drift table definition for reports — Story 7.2.
///
/// Stores end-of-day reports synced from backend.
/// Mirrors the backend `reports` table schema.
class Reports extends Table {
  TextColumn get id => text()();
  TextColumn get tenantId => text()();
  TextColumn get storeId => text()();
  TextColumn get actorId => text().nullable()();
  TextColumn get storeName => text().nullable()();
  TextColumn get reportType => text()(); // 'DAILY' | 'DAILY_COMBINED' | 'WEEKLY'
  DateTimeColumn get reportDate => dateTime()();
  TextColumn get content => text()();
  TextColumn get deliveryStatus => text()(); // 'PENDING' | 'SENT' | 'FAILED' | 'IN_APP_ONLY'
  IntColumn get deliveryAttempts => integer().withDefault(const Constant(0))();
  DateTimeColumn get lastAttemptAt => dateTime().nullable()();
  IntColumn get totalRevenue => integer().withDefault(const Constant(0))();
  IntColumn get totalSales => integer().withDefault(const Constant(0))();
  BoolColumn get isAutomatic => boolean().withDefault(const Constant(false))();
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

import 'package:drift/drift.dart';

/// DayClosures — Records of daily closure events.
///
/// Each closure captures the summary of sales for a store on a given day.
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DayClosures extends Table {
  /// Primary key — UUID generated locally
  TextColumn get id => text()();

  /// Store for which the closure was performed
  TextColumn get storeId => text()();

  /// User who performed the closure (may be SYSTEM for auto-close)
  TextColumn get actorId => text()();

  /// Timestamp of the closure
  DateTimeColumn get closedAt => dateTime()();

  /// Number of COMPLETED sales included in the closure
  IntColumn get totalSales => integer()();

  /// Total revenue in XAF (COMPLETED sales only)
  IntColumn get totalRevenue => integer()();

  /// Product ID with highest quantity sold (nullable)
  TextColumn get topProductId => text().nullable()();

  /// Product name with highest quantity sold (nullable)
  TextColumn get topProductName => text().nullable()();

  /// Quantity sold of top product
  IntColumn get topProductQty => integer().withDefault(const Constant(0))();

  /// Cash payment total in XAF
  IntColumn get cashAmount => integer()();

  /// Mobile Money payment total in XAF
  IntColumn get momoAmount => integer()();

  /// Number of pending sales at closure time
  IntColumn get pendingSalesCount => integer()();

  /// Total amount of pending sales in XAF (not included in totalRevenue)
  IntColumn get pendingSalesTotal => integer()();

  /// Whether this was an automatic closure (scheduler) vs manual
  BoolColumn get isAutomatic =>
      boolean().withDefault(const Constant(false))();

  /// Sync status — false until pushed to backend
  BoolColumn get synced => boolean().withDefault(const Constant(false))();

  /// When synced to backend (nullable)
  DateTimeColumn get syncedAt => dateTime().nullable()();

  /// Record creation timestamp
  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}

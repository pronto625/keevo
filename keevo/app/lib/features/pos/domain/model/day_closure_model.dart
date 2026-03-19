/// DayClosureSummary — Aggregate statistics for a day's sales.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DayClosureSummary {
  /// Number of COMPLETED sales
  final int totalSales;

  /// Total revenue in XAF (COMPLETED sales only)
  final int totalRevenue;

  /// Cash payment total in XAF
  final int cashAmount;

  /// Mobile Money payment total in XAF
  final int momoAmount;

  /// Product ID with highest quantity sold (nullable)
  final String? topProductId;

  /// Product name with highest quantity sold (nullable)
  final String? topProductName;

  /// Quantity sold of top product
  final int topProductQty;

  /// Number of pending sales (not included in totalRevenue)
  final int pendingSalesCount;

  /// Total amount of pending sales in XAF
  final int pendingSalesTotal;

  const DayClosureSummary({
    required this.totalSales,
    required this.totalRevenue,
    required this.cashAmount,
    required this.momoAmount,
    this.topProductId,
    this.topProductName,
    required this.topProductQty,
    required this.pendingSalesCount,
    required this.pendingSalesTotal,
  });

  /// Whether there are pending sales to display in the summary
  bool get hasPendingSales => pendingSalesCount > 0;

  /// Whether there are any sales today (completed or pending)
  bool get hasSales => totalSales > 0 || pendingSalesCount > 0;

  /// Factory for an empty summary (no sales)
  factory DayClosureSummary.empty() => const DayClosureSummary(
        totalSales: 0,
        totalRevenue: 0,
        cashAmount: 0,
        momoAmount: 0,
        topProductQty: 0,
        pendingSalesCount: 0,
        pendingSalesTotal: 0,
      );

  @override
  String toString() => 'DayClosureSummary('
      'totalSales: $totalSales, totalRevenue: $totalRevenue, '
      'cashAmount: $cashAmount, momoAmount: $momoAmount, '
      'topProductName: $topProductName, topProductQty: $topProductQty, '
      'pendingSalesCount: $pendingSalesCount, pendingSalesTotal: $pendingSalesTotal)';
}

/// DayClosure — A daily closure record with its summary.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DayClosure {
  /// UUID generated locally or received from backend
  final String id;

  /// Store for which the closure was performed
  final String storeId;

  /// User who performed the closure
  final String actorId;

  /// Timestamp of the closure
  final DateTime closedAt;

  /// Aggregate summary of the day's sales
  final DayClosureSummary summary;

  /// Whether this was an automatic closure (scheduler) vs manual
  final bool isAutomatic;

  /// Whether synced to backend
  final bool synced;

  const DayClosure({
    required this.id,
    required this.storeId,
    required this.actorId,
    required this.closedAt,
    required this.summary,
    required this.isAutomatic,
    this.synced = false,
  });

  @override
  String toString() => 'DayClosure(id: $id, storeId: $storeId, closedAt: $closedAt, '
      'isAutomatic: $isAutomatic, summary: $summary)';
}

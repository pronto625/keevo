/// StockFilter — optional filter criteria for stock movement history queries.
///
/// All fields are optional except [from] and [to] which default to the last
/// 7 days so the history page shows a meaningful initial window.
///
/// Story 2.3.
class StockFilter {
  /// Filter by movement type (e.g. 'STOCK_ENTRY', 'ADJUSTMENT').
  /// Null means "all types".
  final String? movementType;

  /// Inclusive start of the date range. Defaults to 7 days ago.
  final DateTime from;

  /// Inclusive end of the date range. Defaults to now.
  final DateTime to;

  /// Filter to a specific store. Null means "all stores".
  final String? storeId;

  StockFilter({
    this.movementType,
    DateTime? from,
    DateTime? to,
    this.storeId,
  })  : from = from ?? DateTime.now().subtract(const Duration(days: 7)),
        to = to ?? DateTime.now();

  /// Returns a copy with the given fields overridden.
  StockFilter copyWith({
    String? movementType,
    DateTime? from,
    DateTime? to,
    String? storeId,
    bool clearMovementType = false,
    bool clearStoreId = false,
  }) {
    return StockFilter(
      movementType: clearMovementType ? null : (movementType ?? this.movementType),
      from: from ?? this.from,
      to: to ?? this.to,
      storeId: clearStoreId ? null : (storeId ?? this.storeId),
    );
  }

  @override
  String toString() =>
      'StockFilter(movementType: $movementType, from: $from, to: $to, storeId: $storeId)';
}

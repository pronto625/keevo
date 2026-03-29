/// DateFilterType — Filter presets for sales history.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
enum DateFilterType {
  /// Today only
  today,

  /// Last 7 days
  thisWeek,

  /// Last 30 days
  thisMonth,

  /// Custom date range via DateRangePicker
  custom,
}

/// SalesHistoryFilter — Value object for sales history queries.
///
/// Used by [salesHistoryProvider] to query local/remote sales data.
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class SalesHistoryFilter {
  /// Store to filter sales for
  final String storeId;

  /// Employee to filter sales for (own sales for EMPLOYEE role).
  /// Null means all employees (OWNER sees all store sales).
  final String? employeeId;

  /// Start of date range (inclusive)
  final DateTime from;

  /// End of date range (inclusive, end of day)
  final DateTime to;

  /// The filter preset type
  final DateFilterType filterType;

  const SalesHistoryFilter({
    required this.storeId,
    this.employeeId,
    required this.from,
    required this.to,
    required this.filterType,
  });

  /// Factory for "today" filter
  factory SalesHistoryFilter.today({
    required String storeId,
    String? employeeId,
  }) {
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day);
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59);
    return SalesHistoryFilter(
      storeId: storeId,
      employeeId: employeeId,
      from: startOfDay,
      to: endOfDay,
      filterType: DateFilterType.today,
    );
  }

  /// Factory for "this week" filter (last 7 days)
  factory SalesHistoryFilter.thisWeek({
    required String storeId,
    String? employeeId,
  }) {
    final now = DateTime.now();
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59);
    final startOfWeek = endOfDay.subtract(const Duration(days: 6));
    return SalesHistoryFilter(
      storeId: storeId,
      employeeId: employeeId,
      from: DateTime(startOfWeek.year, startOfWeek.month, startOfWeek.day),
      to: endOfDay,
      filterType: DateFilterType.thisWeek,
    );
  }

  /// Factory for "this month" filter (last 30 days)
  factory SalesHistoryFilter.thisMonth({
    required String storeId,
    String? employeeId,
  }) {
    final now = DateTime.now();
    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59);
    final startOfMonth = endOfDay.subtract(const Duration(days: 29));
    return SalesHistoryFilter(
      storeId: storeId,
      employeeId: employeeId,
      from: DateTime(startOfMonth.year, startOfMonth.month, startOfMonth.day),
      to: endOfDay,
      filterType: DateFilterType.thisMonth,
    );
  }

  /// Factory for custom date range
  factory SalesHistoryFilter.custom({
    required String storeId,
    String? employeeId,
    required DateTime from,
    required DateTime to,
  }) {
    return SalesHistoryFilter(
      storeId: storeId,
      employeeId: employeeId,
      from: DateTime(from.year, from.month, from.day),
      to: DateTime(to.year, to.month, to.day, 23, 59, 59),
      filterType: DateFilterType.custom,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SalesHistoryFilter &&
          runtimeType == other.runtimeType &&
          storeId == other.storeId &&
          employeeId == other.employeeId &&
          from == other.from &&
          to == other.to &&
          filterType == other.filterType;

  @override
  int get hashCode => Object.hash(storeId, employeeId, from, to, filterType);

  @override
  String toString() => 'SalesHistoryFilter('
      'storeId: $storeId, employeeId: $employeeId, '
      'from: $from, to: $to, filterType: $filterType)';
}

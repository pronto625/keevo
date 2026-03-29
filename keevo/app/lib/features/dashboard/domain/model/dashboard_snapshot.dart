/// DashboardSnapshot — Aggregated dashboard data built from local Drift queries.
///
/// GoF Builder: assembled by LocalDashboardDatasource from multiple async queries.
/// Story 7.1 — AC2, AC3, AC4, AC7, AC8.
class DashboardSnapshot {
  final int todayCA;
  final int yesterdayCA;
  final int dayBeforeYesterdayCA;
  final double trendPercent;
  final int totalTransactionsMonth;
  final int averageBasketMonth;
  final int prevMonthTransactions;
  final int prevMonthAverageBasket;
  final int lowStockCount;
  final List<TopProduct> weeklyTopProducts;
  final List<TopProduct> weeklyWorstProducts;
  final List<DailyCA> dailyCALast30;
  final List<DailyCA> weeklyCA;
  final List<DailyCA> monthlyCA;
  final List<DailyCA> yearlyCA;
  final List<StoreOverview> storeOverviews;
  final int todaySalesCount;

  const DashboardSnapshot({
    required this.todayCA,
    required this.yesterdayCA,
    required this.dayBeforeYesterdayCA,
    required this.trendPercent,
    required this.totalTransactionsMonth,
    required this.averageBasketMonth,
    required this.prevMonthTransactions,
    required this.prevMonthAverageBasket,
    required this.lowStockCount,
    required this.weeklyTopProducts,
    required this.weeklyWorstProducts,
    required this.dailyCALast30,
    this.weeklyCA = const [],
    this.monthlyCA = const [],
    this.yearlyCA = const [],
    required this.storeOverviews,
    required this.todaySalesCount,
  });

  /// Trend percent comparing today vs yesterday CA.
  /// Positive = up, negative = down, 0 = no data.
  double get heroTrendPercent {
    if (yesterdayCA == 0) return 0;
    return ((todayCA - yesterdayCA) / yesterdayCA) * 100;
  }

  /// Trend percent for transactions: this month vs previous month.
  double get transactionsTrend {
    if (prevMonthTransactions == 0) return 0;
    return ((totalTransactionsMonth - prevMonthTransactions) /
            prevMonthTransactions) *
        100;
  }

  /// Trend percent for average basket: this month vs previous month.
  double get averageBasketTrend {
    if (prevMonthAverageBasket == 0) return 0;
    return ((averageBasketMonth - prevMonthAverageBasket) /
            prevMonthAverageBasket) *
        100;
  }

  /// Last 7 days of daily CA for sparkline (most recent last).
  List<DailyCA> get last7DaysCA {
    if (dailyCALast30.length <= 7) return dailyCALast30;
    return dailyCALast30.sublist(dailyCALast30.length - 7);
  }
}

/// TopProduct — best-selling product for the week.
class TopProduct {
  final String productId;
  final String name;
  final String? photoUrl;
  final int unitsSold;
  final int revenue;
  final double sharePercent;

  const TopProduct({
    required this.productId,
    required this.name,
    this.photoUrl,
    required this.unitsSold,
    required this.revenue,
    required this.sharePercent,
  });
}

/// DailyCA — one day's total chiffre d'affaires.
class DailyCA {
  final DateTime date;
  final int amount;

  const DailyCA({required this.date, required this.amount});
}

/// StoreOverview — per-store status summary for dashboard.
class StoreOverview {
  final String storeId;
  final String storeName;
  final int todayCA;
  final int yesterdayCA;
  final int employeeCount;
  final StoreStatusLevel statusLevel;
  final List<DailyCA> dailyCA;
  final List<DailyCA> weeklyCA;
  final List<DailyCA> monthlyCA;
  final List<DailyCA> yearlyCA;

  const StoreOverview({
    required this.storeId,
    required this.storeName,
    required this.todayCA,
    required this.yesterdayCA,
    required this.employeeCount,
    required this.statusLevel,
    this.dailyCA = const [],
    this.weeklyCA = const [],
    this.monthlyCA = const [],
    this.yearlyCA = const [],
  });
}

/// Store health status based on CA comparison.
enum StoreStatusLevel {
  stable,    // todayCA >= yesterdayCA * 0.8
  attention, // todayCA < yesterdayCA * 0.8 && todayCA >= yesterdayCA * 0.5
  enBaisse,  // todayCA < yesterdayCA * 0.5
}

/// Compute store status from today vs yesterday CA.
StoreStatusLevel computeStoreStatus(int todayCA, int yesterdayCA) {
  if (yesterdayCA == 0) return StoreStatusLevel.stable;
  if (todayCA >= yesterdayCA * 0.8) return StoreStatusLevel.stable;
  if (todayCA >= yesterdayCA * 0.5) return StoreStatusLevel.attention;
  return StoreStatusLevel.enBaisse;
}

/// Chart aggregation period for sales evolution.
enum ChartPeriod { daily, weekly, monthly, yearly }

/// LowStockProduct — product with stock below threshold.
class LowStockProduct {
  final String productId;
  final String name;
  final int quantity;
  final int threshold;

  const LowStockProduct({
    required this.productId,
    required this.name,
    required this.quantity,
    required this.threshold,
  });
}

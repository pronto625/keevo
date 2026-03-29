import 'package:dio/dio.dart';

import '../../domain/model/dashboard_snapshot.dart';

/// RemoteDashboardDatasource — HTTP adapter for GET /api/v1/dashboard/summary.
///
/// Fetches aggregated dashboard data from the backend when online.
/// Story 7.1, Task 2 — optional online enrichment.
class RemoteDashboardDatasource {
  final Dio _dio;

  RemoteDashboardDatasource({required Dio dio}) : _dio = dio;

  /// Fetches the full dashboard summary from backend.
  /// Throws on network error — caller should catch and fallback to local.
  Future<DashboardSnapshot> getDashboardSummary() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/dashboard/summary',
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return _mapToSnapshot(data);
  }

  DashboardSnapshot _mapToSnapshot(Map<String, dynamic> json) {
    final topProducts = (json['weeklyTopProducts'] as List<dynamic>?)
            ?.map((e) => _mapTopProduct(e as Map<String, dynamic>))
            .toList() ??
        [];

    final worstProducts = (json['weeklyWorstProducts'] as List<dynamic>?)
            ?.map((e) => _mapTopProduct(e as Map<String, dynamic>))
            .toList() ??
        [];

    final dailyCA = (json['dailyCALast30'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];

    final weeklyCA = (json['weeklyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];

    final monthlyCA = (json['monthlyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];

    final yearlyCA = (json['yearlyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];

    final storeOverviews = (json['storeOverviews'] as List<dynamic>?)
            ?.map((e) => _mapStoreOverview(e as Map<String, dynamic>))
            .toList() ??
        [];

    return DashboardSnapshot(
      todayCA: (json['todayCA'] as num?)?.toInt() ?? 0,
      yesterdayCA: (json['yesterdayCA'] as num?)?.toInt() ?? 0,
      dayBeforeYesterdayCA:
          (json['dayBeforeYesterdayCA'] as num?)?.toInt() ?? 0,
      trendPercent: (json['heroTrendPercent'] as num?)?.toDouble() ?? 0.0,
      totalTransactionsMonth:
          (json['totalTransactionsMonth'] as num?)?.toInt() ?? 0,
      averageBasketMonth: (json['averageBasketMonth'] as num?)?.toInt() ?? 0,
      prevMonthTransactions:
          (json['prevMonthTransactions'] as num?)?.toInt() ?? 0,
      prevMonthAverageBasket:
          (json['prevMonthAverageBasket'] as num?)?.toInt() ?? 0,
      lowStockCount: (json['lowStockCount'] as num?)?.toInt() ?? 0,
      weeklyTopProducts: topProducts,
      weeklyWorstProducts: worstProducts,
      dailyCALast30: dailyCA,
      weeklyCA: weeklyCA,
      monthlyCA: monthlyCA,
      yearlyCA: yearlyCA,
      storeOverviews: storeOverviews,
      todaySalesCount: (json['todaySalesCount'] as num?)?.toInt() ?? 0,
    );
  }

  TopProduct _mapTopProduct(Map<String, dynamic> json) {
    return TopProduct(
      productId: json['productId'] as String? ?? '',
      name: json['productName'] as String? ?? '',
      unitsSold: (json['unitsSold'] as num?)?.toInt() ?? 0,
      revenue: (json['revenue'] as num?)?.toInt() ?? 0,
      sharePercent: (json['sharePercent'] as num?)?.toDouble() ?? 0.0,
    );
  }

  DailyCA _mapDailyCA(Map<String, dynamic> json) {
    return DailyCA(
      date: DateTime.tryParse(json['date'] as String? ?? '') ?? DateTime.now(),
      amount: (json['amount'] as num?)?.toInt() ?? 0,
    );
  }

  StoreOverview _mapStoreOverview(Map<String, dynamic> json) {
    final statusStr = json['statusLevel'] as String? ?? 'STABLE';
    final statusLevel = _parseStatusLevel(statusStr);

    final dailyCA = (json['dailyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];
    final weeklyCA = (json['weeklyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];
    final monthlyCA = (json['monthlyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];
    final yearlyCA = (json['yearlyCA'] as List<dynamic>?)
            ?.map((e) => _mapDailyCA(e as Map<String, dynamic>))
            .toList() ??
        [];

    return StoreOverview(
      storeId: json['storeId'] as String? ?? '',
      storeName: json['storeName'] as String? ?? '',
      todayCA: (json['todayCA'] as num?)?.toInt() ?? 0,
      yesterdayCA: (json['yesterdayCA'] as num?)?.toInt() ?? 0,
      employeeCount: (json['employeeCount'] as num?)?.toInt() ?? 0,
      statusLevel: statusLevel,
      dailyCA: dailyCA,
      weeklyCA: weeklyCA,
      monthlyCA: monthlyCA,
      yearlyCA: yearlyCA,
    );
  }

  static StoreStatusLevel _parseStatusLevel(String status) {
    switch (status) {
      case 'ATTENTION':
        return StoreStatusLevel.attention;
      case 'EN_BAISSE':
        return StoreStatusLevel.enBaisse;
      case 'STABLE':
      default:
        return StoreStatusLevel.stable;
    }
  }
}

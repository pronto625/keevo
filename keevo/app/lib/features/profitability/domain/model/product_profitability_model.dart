import 'package:flutter/material.dart';

/// SortOption — mirrors SortOption enum in backend.
enum SortOption {
  marginPctDesc,
  marginXafDesc,
  caDesc,
  unitsDesc;

  String toQueryParam() {
    switch (this) {
      case SortOption.marginPctDesc:
        return 'MARGIN_PCT_DESC';
      case SortOption.marginXafDesc:
        return 'MARGIN_XAF_DESC';
      case SortOption.caDesc:
        return 'CA_DESC';
      case SortOption.unitsDesc:
        return 'UNITS_DESC';
    }
  }
}

/// RankingMetric — mirrors RankingMetric enum in backend.
enum RankingMetric {
  ca,
  salesCount,
  avgBasket;

  String toQueryParam() {
    switch (this) {
      case RankingMetric.ca:
        return 'CA';
      case RankingMetric.salesCount:
        return 'SALES_COUNT';
      case RankingMetric.avgBasket:
        return 'AVG_BASKET';
    }
  }
}

/// ProfitabilityParams — query parameters for product profitability providers.
class ProfitabilityParams {
  final DateTime from;
  final DateTime to;
  final SortOption sort;
  final String? storeId;

  const ProfitabilityParams({
    required this.from,
    required this.to,
    this.sort = SortOption.marginPctDesc,
    this.storeId,
  });

  ProfitabilityParams copyWith({
    DateTime? from,
    DateTime? to,
    SortOption? sort,
    String? storeId,
  }) =>
      ProfitabilityParams(
        from: from ?? this.from,
        to: to ?? this.to,
        sort: sort ?? this.sort,
        storeId: storeId ?? this.storeId,
      );

  @override
  bool operator ==(Object other) =>
      other is ProfitabilityParams &&
      other.from == from &&
      other.to == to &&
      other.sort == sort &&
      other.storeId == storeId;

  @override
  int get hashCode => Object.hash(from, to, sort, storeId);
}

/// StorePerformanceParams — query parameters for store performance providers.
class StorePerformanceParams {
  final DateTime from;
  final DateTime to;
  final RankingMetric metric;

  const StorePerformanceParams({
    required this.from,
    required this.to,
    this.metric = RankingMetric.ca,
  });

  StorePerformanceParams copyWith({
    DateTime? from,
    DateTime? to,
    RankingMetric? metric,
  }) =>
      StorePerformanceParams(
        from: from ?? this.from,
        to: to ?? this.to,
        metric: metric ?? this.metric,
      );

  @override
  bool operator ==(Object other) =>
      other is StorePerformanceParams &&
      other.from == from &&
      other.to == to &&
      other.metric == metric;

  @override
  int get hashCode => Object.hash(from, to, metric);
}

/// MarginLevel — mirrors MarginLevel enum in backend and
/// _MarginLevel in pricing_calculator_widget.dart.
///
/// Color thresholds MUST match PricingCalculatorWidget:
///   LOSS / LOW  → Colors.red
///   MODERATE    → Colors.orange
///   PROFITABLE  → Colors.green
enum MarginLevel {
  loss,
  low,
  moderate,
  profitable;

  static MarginLevel fromString(String raw) {
    switch (raw.toUpperCase()) {
      case 'LOSS':
        return MarginLevel.loss;
      case 'LOW':
        return MarginLevel.low;
      case 'MODERATE':
        return MarginLevel.moderate;
      case 'PROFITABLE':
      default:
        return MarginLevel.profitable;
    }
  }

  Color get color {
    switch (this) {
      case MarginLevel.loss:
      case MarginLevel.low:
        return Colors.red;
      case MarginLevel.moderate:
        return Colors.orange;
      case MarginLevel.profitable:
        return Colors.green;
    }
  }
}

// ── DailyMarginEntry ──────────────────────────────────────────────────────────

/// A single data point for a 7-day margin sparkline.
class DailyMarginEntry {
  final String date;
  final int marginXaf;

  const DailyMarginEntry({required this.date, required this.marginXaf});

  factory DailyMarginEntry.fromJson(Map<String, dynamic> json) =>
      DailyMarginEntry(
        date: json['date'] as String,
        marginXaf: (json['marginXaf'] as num).toInt(),
      );
}

// ── ProductProfitabilityEntry ─────────────────────────────────────────────────

/// Flat product-level profitability entry returned by the list endpoint.
class ProductProfitabilityEntry {
  final String productId;
  final String productName;
  final String? categoryName;
  final int unitsSold;
  final int totalRevenue;
  final int totalCost;
  final int grossMarginXaf;
  final double marginPercent;
  final bool isLoss;
  final String? storeId;
  final String marginLevel;

  const ProductProfitabilityEntry({
    required this.productId,
    required this.productName,
    required this.categoryName,
    required this.unitsSold,
    required this.totalRevenue,
    required this.totalCost,
    required this.grossMarginXaf,
    required this.marginPercent,
    required this.isLoss,
    required this.storeId,
    required this.marginLevel,
  });

  factory ProductProfitabilityEntry.fromJson(Map<String, dynamic> json) =>
      ProductProfitabilityEntry(
        productId: json['productId'] as String,
        productName: json['productName'] as String,
        categoryName: json['categoryName'] as String?,
        unitsSold: (json['unitsSold'] as num).toInt(),
        totalRevenue: (json['totalRevenue'] as num).toInt(),
        totalCost: (json['totalCost'] as num).toInt(),
        grossMarginXaf: (json['grossMarginXaf'] as num).toInt(),
        marginPercent: (json['marginPercent'] as num).toDouble(),
        isLoss: json['isLoss'] as bool,
        storeId: json['storeId'] as String?,
        marginLevel: (json['marginLevel'] as String?) ?? 'PROFITABLE',
      );

  /// Color corresponding to margin level — matches PricingCalculatorWidget.
  Color get marginColor =>
      MarginLevel.fromString(marginLevel).color;
}

// ── ProductProfitabilityDetail ────────────────────────────────────────────────

/// Enriched detail model returned by the product detail endpoint.
class ProductProfitabilityDetail extends ProductProfitabilityEntry {
  final int currentCataloguePrice;
  final int currentBuyPrice;
  final int currentTransportCost;
  final int minAppliedPrice;
  final int maxAppliedPrice;
  final double avgAppliedPrice;
  final List<DailyMarginEntry> dailyMarginLast7;
  final String? topStoreId;
  final String? topStoreName;
  final int? topStoreUnitsSold;

  const ProductProfitabilityDetail({
    required super.productId,
    required super.productName,
    required super.categoryName,
    required super.unitsSold,
    required super.totalRevenue,
    required super.totalCost,
    required super.grossMarginXaf,
    required super.marginPercent,
    required super.isLoss,
    required super.storeId,
    required super.marginLevel,
    required this.currentCataloguePrice,
    required this.currentBuyPrice,
    required this.currentTransportCost,
    required this.minAppliedPrice,
    required this.maxAppliedPrice,
    required this.avgAppliedPrice,
    required this.dailyMarginLast7,
    required this.topStoreId,
    required this.topStoreName,
    required this.topStoreUnitsSold,
  });

  factory ProductProfitabilityDetail.fromJson(Map<String, dynamic> json) =>
      ProductProfitabilityDetail(
        productId: json['productId'] as String,
        productName: json['productName'] as String,
        categoryName: json['categoryName'] as String?,
        unitsSold: (json['unitsSold'] as num).toInt(),
        totalRevenue: (json['totalRevenue'] as num).toInt(),
        totalCost: (json['totalCost'] as num).toInt(),
        grossMarginXaf: (json['grossMarginXaf'] as num).toInt(),
        marginPercent: (json['marginPercent'] as num).toDouble(),
        isLoss: json['isLoss'] as bool,
        storeId: json['storeId'] as String?,
        marginLevel: (json['marginLevel'] as String?) ?? 'PROFITABLE',
        currentCataloguePrice:
            (json['currentCataloguePrice'] as num).toInt(),
        currentBuyPrice: (json['currentBuyPrice'] as num).toInt(),
        currentTransportCost:
            (json['currentTransportCost'] as num).toInt(),
        minAppliedPrice: (json['minAppliedPrice'] as num).toInt(),
        maxAppliedPrice: (json['maxAppliedPrice'] as num).toInt(),
        avgAppliedPrice: (json['avgAppliedPrice'] as num).toDouble(),
        dailyMarginLast7: (json['dailyMarginLast7'] as List<dynamic>)
            .map((e) =>
                DailyMarginEntry.fromJson(e as Map<String, dynamic>))
            .toList(),
        topStoreId: json['topStoreId'] as String?,
        topStoreName: json['topStoreName'] as String?,
        topStoreUnitsSold: json['topStoreUnitsSold'] != null
            ? (json['topStoreUnitsSold'] as num).toInt()
            : null,
      );
}

// ── StorePerformanceEntry ─────────────────────────────────────────────────────

/// Store comparative performance entry returned by the stores endpoint.
class StorePerformanceEntry {
  final int rank;
  final String storeId;
  final String storeName;
  final int totalRevenue;
  final int salesCount;
  final int averageBasket;
  final String? topProductName;
  final double deltaPercent;

  const StorePerformanceEntry({
    required this.rank,
    required this.storeId,
    required this.storeName,
    required this.totalRevenue,
    required this.salesCount,
    required this.averageBasket,
    required this.topProductName,
    required this.deltaPercent,
  });

  factory StorePerformanceEntry.fromJson(Map<String, dynamic> json) =>
      StorePerformanceEntry(
        rank: (json['rank'] as num).toInt(),
        storeId: json['storeId'] as String,
        storeName: json['storeName'] as String,
        totalRevenue: (json['totalRevenue'] as num).toInt(),
        salesCount: (json['salesCount'] as num).toInt(),
        averageBasket: (json['averageBasket'] as num).toInt(),
        topProductName: json['topProductName'] as String?,
        deltaPercent: (json['deltaPercent'] as num).toDouble(),
      );
}

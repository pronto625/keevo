import 'package:freezed_annotation/freezed_annotation.dart';

part 'store_stock_summary_model.freezed.dart';
part 'store_stock_summary_model.g.dart';

/// StoreStockSummaryModel — aggregated stock snapshot for one store.
/// Mirrors StoreStockSummary backend domain model.
/// Story 3.2. GoF: Composite root.
@freezed
class StoreStockSummaryModel with _$StoreStockSummaryModel {
  const factory StoreStockSummaryModel({
    required String storeId,
    required String storeName,
    required String storeType,      // 'STORE' | 'WAREHOUSE'
    required int productCount,
    required int totalValueXaf,     // int: XAF is whole numbers
    required int lowStockCount,
    @Default(false) bool isUpdated, // transient — UI indicator after sync
  }) = _StoreStockSummaryModel;

  factory StoreStockSummaryModel.fromJson(Map<String, dynamic> json) =>
      _$StoreStockSummaryModelFromJson(json);
}

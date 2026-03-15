import 'package:freezed_annotation/freezed_annotation.dart';

part 'cross_store_availability_model.freezed.dart';
part 'cross_store_availability_model.g.dart';

/// Stock status for a store entry in the cross-store availability sheet.
enum CrossStoreStockStatus { ok, low, outOfStock }

/// CrossStoreAvailabilityModel — product availability across all active stores.
///
/// Returned by [StockRepository.getCrossStoreAvailability].
/// Story 3.4.
@freezed
class CrossStoreAvailabilityModel with _$CrossStoreAvailabilityModel {
  const CrossStoreAvailabilityModel._();

  const factory CrossStoreAvailabilityModel({
    required String productId,
    required String productName,
    required List<CrossStoreAvailabilityEntry> entries,
    required DateTime refreshedAt,
  }) = _CrossStoreAvailabilityModel;

  factory CrossStoreAvailabilityModel.fromJson(Map<String, dynamic> json) =>
      _$CrossStoreAvailabilityModelFromJson(json);
}

/// CrossStoreAvailabilityEntry — stock data for one store.
///
/// Story 3.4.
@freezed
class CrossStoreAvailabilityEntry with _$CrossStoreAvailabilityEntry {
  const CrossStoreAvailabilityEntry._();

  const factory CrossStoreAvailabilityEntry({
    required String storeId,
    required String storeName,
    @Default('STORE') String storeType,
    required int quantity,
    @Default(0) int minimumThreshold,
    @Default(false) bool isLow,
  }) = _CrossStoreAvailabilityEntry;

  factory CrossStoreAvailabilityEntry.fromJson(Map<String, dynamic> json) =>
      _$CrossStoreAvailabilityEntryFromJson(json);

  CrossStoreStockStatus get stockStatus {
    if (quantity == 0) return CrossStoreStockStatus.outOfStock;
    if (isLow) return CrossStoreStockStatus.low;
    return CrossStoreStockStatus.ok;
  }

  bool get isWarehouse => storeType == 'WAREHOUSE';
}

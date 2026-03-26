import 'package:freezed_annotation/freezed_annotation.dart';

part 'inventory_count_model.freezed.dart';
part 'inventory_count_model.g.dart';

/// InventoryCountModel — physical count for one product (or variant) in a session.
/// Mirrors InventoryCount backend domain model.
/// Story 6.2.
@freezed
class InventoryCountModel with _$InventoryCountModel {
  const InventoryCountModel._();

  const factory InventoryCountModel({
    required String id,
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    int? physical,
    String? countedBy,
    DateTime? countedAt,
    required DateTime updatedAt,
    @Default(false) bool synced,
  }) = _InventoryCountModel;

  /// Écart = physicalQty - theoreticalQty, null if not yet counted.
  int? get ecart => physical != null ? physical! - theoretical : null;

  /// Whether a physical count has been recorded.
  bool get isCounted => physical != null;

  /// True when physical == theoretical.
  bool get isMatch => physical != null && physical == theoretical;

  /// True when physical > theoretical.
  bool get isSurplus => physical != null && physical! > theoretical;

  /// True when physical < theoretical.
  bool get isShortage => physical != null && physical! < theoretical;

  factory InventoryCountModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryCountModelFromJson(json);
}

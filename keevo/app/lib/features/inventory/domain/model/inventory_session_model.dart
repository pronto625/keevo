import 'package:freezed_annotation/freezed_annotation.dart';

part 'inventory_session_model.freezed.dart';
part 'inventory_session_model.g.dart';

/// InventorySessionModel — represents an inventory counting session.
/// Mirrors InventorySession backend domain model.
/// Story 6.1. GoF: Value Object.
@freezed
class InventorySessionModel with _$InventorySessionModel {
  const InventorySessionModel._();

  const factory InventorySessionModel({
    required String id,
    required String storeId,
    required String scope,
    List<String>? categoryIds,
    required String status,
    required String startedBy,
    required DateTime startedAt,
    String? cancelledBy,
    DateTime? cancelledAt,
    DateTime? completedAt,
    required DateTime updatedAt,
  }) = _InventorySessionModel;

  bool get isActive => status == 'IN_PROGRESS';
  bool get isValidated => status == 'VALIDATED';
  bool get isCancelled => status == 'CANCELLED';

  factory InventorySessionModel.fromJson(Map<String, dynamic> json) =>
      _$InventorySessionModelFromJson(json);
}

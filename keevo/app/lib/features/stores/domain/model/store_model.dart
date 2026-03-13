import 'package:freezed_annotation/freezed_annotation.dart';

import 'store_type.dart';

part 'store_model.freezed.dart';
part 'store_model.g.dart';

/// StoreModel — immutable domain model for a store or warehouse.
///
/// Mirrors [Stores] Drift table and StoreResponseDto (backend).
/// Story 3.1 — AC1–AC6.
@freezed
class StoreModel with _$StoreModel {
  const factory StoreModel({
    required String id,
    required String name,
    @Default(StoreType.store) StoreType type,
    String? address,
    String? phone,
    @Default(true) bool isActive,
    required DateTime createdAt,
    required DateTime updatedAt,
  }) = _StoreModel;

  factory StoreModel.fromJson(Map<String, dynamic> json) =>
      _$StoreModelFromJson(json);
}

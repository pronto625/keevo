import 'package:freezed_annotation/freezed_annotation.dart';

import '../../../../core/utils/server_date.dart';

part 'supplier_model.freezed.dart';
part 'supplier_model.g.dart';

/// SupplierModel — domain model for a supplier in the tenant contact book.
///
/// Immutable Freezed value object — mirrors [Suppliers] Drift table and
/// backend Supplier entity. Story 2.5 — Gestion Clients & Fournisseurs.
@freezed
class SupplierModel with _$SupplierModel {
  const factory SupplierModel({
    required String id,
    required String name,
    required String phone,
    String? email,
    @Default(false) bool archived,
    @Default([]) List<String> productIds,
    @ServerDateTimeConverter() required DateTime createdAt,
    @ServerDateTimeConverter() required DateTime updatedAt,
  }) = _SupplierModel;

  factory SupplierModel.fromJson(Map<String, dynamic> json) =>
      _$SupplierModelFromJson(json);
}

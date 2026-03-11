import 'package:freezed_annotation/freezed_annotation.dart';

part 'client_model.freezed.dart';
part 'client_model.g.dart';

/// ClientModel — domain model for a client in the tenant contact book.
///
/// Immutable Freezed value object — mirrors [Clients] Drift table and
/// backend Client entity. Story 2.5 — Gestion Clients & Fournisseurs.
@freezed
class ClientModel with _$ClientModel {
  const factory ClientModel({
    required String id,
    required String name,
    required String phone,
    String? email,
    String? notes,
    @Default(false) bool archived,
    required DateTime createdAt,
    required DateTime updatedAt,
  }) = _ClientModel;

  factory ClientModel.fromJson(Map<String, dynamic> json) =>
      _$ClientModelFromJson(json);
}

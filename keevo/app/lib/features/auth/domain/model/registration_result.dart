import 'package:freezed_annotation/freezed_annotation.dart';

part 'registration_result.freezed.dart';

/// RegistrationResult — Domain model for a successful registration response.
///
/// Immutable Freezed value object — auto-generates equality, hashCode, copyWith and toString.
/// Mirrors the backend RegistrationResult record.
@freezed
abstract class RegistrationResult with _$RegistrationResult {
  const factory RegistrationResult({
    required String tenantCode,
    required String token,
    required String userId,
    required String tenantId,
    // Nullable: backends older than the refresh-on-registration change omit it.
    String? refreshToken,
  }) = _RegistrationResult;
}

import 'package:freezed_annotation/freezed_annotation.dart';

part 'audit_entry_dto.freezed.dart';
part 'audit_entry_dto.g.dart';

/// AuditEntryDto — domain model for an audit log entry returned by the backend.
///
/// Immutable Freezed value object — auto-generates equality, hashCode, copyWith and toString.
///
/// AC6: AuditRepository.getAuditHistory() returns List<AuditEntryDto>.
@freezed
class AuditEntryDto with _$AuditEntryDto {
  const factory AuditEntryDto({
    /// Unique identifier (UUID string).
    required String id,

    /// Entity type discriminator (e.g. "Product", "User", "Tenant").
    required String entityType,

    /// Entity identifier (UUID string).
    required String entityId,

    /// Action label (e.g. "USER_REGISTERED", "STOCK_ADJUSTED", "ONBOARDING_COMPLETED").
    required String action,

    /// JSON-encoded state before the operation — null for creation events.
    String? valueBefore,

    /// JSON-encoded state after the operation.
    String? valueAfter,

    /// UUID of the user who triggered the action.
    required String userId,

    /// Timestamp of the audit entry (UTC).
    required DateTime occurredAt,
  }) = _AuditEntryDto;

  factory AuditEntryDto.fromJson(Map<String, dynamic> json) =>
      _$AuditEntryDtoFromJson(json);
}

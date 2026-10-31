import 'dart:convert';

import 'package:freezed_annotation/freezed_annotation.dart';
import 'package:keevo_core/utils/server_date.dart';

part 'audit_entry_dto.freezed.dart';
part 'audit_entry_dto.g.dart';

/// AuditEntryDto — domain model for an audit log entry returned by the backend.
///
/// Immutable Freezed value object — auto-generates equality, hashCode, copyWith and toString.
///
/// AC6: AuditRepository.getAuditHistory() returns List<AuditEntryDto>.
@freezed
abstract class AuditEntryDto with _$AuditEntryDto {
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

    /// Phone number of the actor — resolved by the backend via JOIN with public.users.
    /// Null only in edge cases (e.g. user deleted after the audit entry was recorded).
    String? actorPhone,

    /// Timestamp of the audit entry (UTC).
    @ServerDateTimeConverter() required DateTime occurredAt,

    // ── v1s-18-6 enrichment (null on rows written before it) ──────────────

    /// Store the action happened in.
    String? storeId,

    /// Store name resolved by the backend (or the local `stores` table).
    String? storeName,

    /// Readable object label at action time (product, client, employee name…).
    String? entityLabel,

    /// Originating device id.
    String? deviceId,

    /// `ONLINE` | `SYNC` | `SYSTEM`.
    String? source,

    /// Actor display name resolved by the backend (or local `employees`).
    String? actorName,

    /// Context JSON object as text (REST sends an object, Drift stores text).
    @JsonKey(fromJson: metadataFromJson) String? metadata,
  }) = _AuditEntryDto;

  factory AuditEntryDto.fromJson(Map<String, dynamic> json) =>
      _$AuditEntryDtoFromJson(json);
}

/// Accepts the REST JSON object or the Drift/sync JSON text.
String? metadataFromJson(Object? value) {
  if (value == null) return null;
  if (value is String) return value;
  return jsonEncode(value);
}

/// AuditPageResult — result of a paginated audit history request.
class AuditPageResult {
  final List<AuditEntryDto> entries;
  final bool hasMore;

  const AuditPageResult({required this.entries, required this.hasMore});
}

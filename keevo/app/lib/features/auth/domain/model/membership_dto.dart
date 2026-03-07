import 'package:freezed_annotation/freezed_annotation.dart';

part 'membership_dto.freezed.dart';

/// MembershipDto — one tenant membership returned in the login session response.
///
/// Mirrors the backend LoginSessionResponse.MembershipDto record.
/// Used in [TenantPickerPage] and auto-select logic in [LoginUseCase].
@freezed
class MembershipDto with _$MembershipDto {
  const factory MembershipDto({
    /// Tenant short code (e.g. "KV-ABC123")
    required String tenantCode,

    /// Human-readable business name (e.g. "Boutique Simon")
    required String tenantName,

    /// Role in this tenant: 'OWNER' or 'EMPLOYEE'
    required String role,

    /// Database schema name (e.g. "kv_abc123")
    required String schemaName,
  }) = _MembershipDto;
}

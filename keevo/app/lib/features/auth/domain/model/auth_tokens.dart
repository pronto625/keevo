import 'package:freezed_annotation/freezed_annotation.dart';

part 'auth_tokens.freezed.dart';

/// AuthTokens — Domain model for a successful authentication response.
///
/// Immutable Freezed value object — auto-generates equality, hashCode,
/// copyWith and toString.
///
/// AC1: returned after successful login or token refresh.
/// AC3: refreshToken is used to silently renew the access token.
@freezed
class AuthTokens with _$AuthTokens {
  const factory AuthTokens({
    /// RS256-signed JWT (24h expiry). Sent as Authorization: Bearer header.
    required String accessToken,

    /// Opaque refresh token (30-day expiry). Never sent in regular requests.
    required String refreshToken,

    /// Authenticated user's UUID.
    required String userId,

    /// Tenant schema name (kv_xxxxxx) for multi-tenant routing.
    required String tenantId,

    /// User role: 'OWNER' or 'EMPLOYEE'.
    required String role,

    /// Access token lifetime in seconds (defaults to 86400 = 24h).
    required int expiresIn,

    /// Assigned store UUID (null for OWNER) — Story 3.5.
    String? storeId,

    /// True if employee must change password on first login — Story 3.5.
    @Default(false) bool passwordChangeRequired,
  }) = _AuthTokens;
}

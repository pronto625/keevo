import '../model/account_profile.dart';
import '../model/auth_tokens.dart';
import '../model/login_session_response.dart';
import '../model/registration_result.dart';

/// AuthRepository — Port interface for authentication operations.
///
/// Follows hexagonal architecture: the domain defines the contract,
/// the data layer provides the implementation.
abstract interface class AuthRepository {
  /// Register a new user and provision their isolated tenant workspace.
  ///
  /// Returns [RegistrationResult] on success.
  /// Throws [AuthException] on domain or network errors.
  Future<RegistrationResult> register({
    required String phoneNumber,
    required String password,
  });

  /// POST /auth/login — Step 1 of two-step login (Story 1.7).
  ///
  /// Returns [LoginSessionResponse] with a short-lived loginToken and memberships.
  /// Throws [AuthException] with INVALID_CREDENTIALS or ACCOUNT_LOCKED.
  Future<LoginSessionResponse> login({
    required String phoneNumber,
    required String password,
  });

  /// POST /auth/select-tenant — Step 2 of two-step login (Story 1.7).
  ///
  /// Exchanges a [loginToken] + [tenantCode] for a full scoped [AuthTokens].
  /// Throws [AuthException] on TOKEN_EXPIRED, TOKEN_INVALID, TENANT_NOT_FOUND.
  Future<AuthTokens> selectTenant({
    required String loginToken,
    required String tenantCode,
  });

  /// Exchange a valid refresh token for a new [AuthTokens] pair.
  ///
  /// Throws [AuthException] with domainCode 'REFRESH_TOKEN_INVALID' if expired/revoked.
  Future<AuthTokens> refreshToken(String rawRefreshToken);

  /// POST /auth/change-password — Story 3.5 AC4.
  ///
  /// Changes the employee's password and returns fresh [AuthTokens].
  /// Throws [AuthException] with INVALID_CREDENTIALS or VALIDATION_FAILED.
  Future<AuthTokens> changePassword({
    required String currentPassword,
    required String newPassword,
  });

  /// GET /auth/profile — Story 8.6 AC3.
  ///
  /// Returns the authenticated user's [AccountProfile].
  /// Requires valid JWT; throws [AuthException] if unauthenticated.
  Future<AccountProfile> getProfile();
}

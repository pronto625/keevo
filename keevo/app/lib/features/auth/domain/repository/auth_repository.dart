import '../model/auth_tokens.dart';
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

  /// Authenticate with phone + password.
  ///
  /// Returns [AuthTokens] on success (access + refresh tokens).
  /// Throws [AuthException] with domainCode 'INVALID_CREDENTIALS' on wrong password.
  /// Throws [AuthException] with domainCode 'ACCOUNT_LOCKED' when locked.
  Future<AuthTokens> login({
    required String phoneNumber,
    required String password,
  });

  /// Exchange a valid refresh token for a new [AuthTokens] pair.
  ///
  /// Throws [AuthException] with domainCode 'REFRESH_TOKEN_INVALID' if expired/revoked.
  Future<AuthTokens> refreshToken(String rawRefreshToken);
}

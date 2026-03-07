import '../model/auth_tokens.dart';
import '../repository/auth_repository.dart';
import '../repository/token_storage.dart';

/// SelectTenantUseCase — completes the two-step login flow (Story 1.7, AC3, AC8, AC9).
///
/// Called transparently by [LoginUseCase] for single-membership users (AC8),
/// or explicitly by [TenantPickerPage] when the user picks a tenant (AC9).
class SelectTenantUseCase {
  final AuthRepository _repository;
  final TokenStorage _tokenStorage;

  const SelectTenantUseCase(this._repository, this._tokenStorage);

  /// Exchanges a [loginToken] + [tenantCode] for a full scoped [AuthTokens].
  ///
  /// On success, stores access token, refresh token, userId and tenantId
  /// in secure storage — identical to the previous single-step login flow.
  ///
  /// Propagates [AuthException] on:
  /// - TOKEN_EXPIRED: loginToken is older than 5 min
  /// - TOKEN_INVALID: wrong token scope or malformed JWT
  /// - TENANT_NOT_FOUND: tenantCode doesn't match any tenant
  Future<AuthTokens> execute({
    required String loginToken,
    required String tenantCode,
  }) async {
    final tokens = await _repository.selectTenant(
      loginToken: loginToken,
      tenantCode: tenantCode,
    );

    await Future.wait<void>([
      _tokenStorage.saveToken(tokens.accessToken),
      _tokenStorage.saveRefreshToken(tokens.refreshToken),
      _tokenStorage.saveUserId(tokens.userId),
      _tokenStorage.saveTenantId(tokens.tenantId),
    ]);

    return tokens;
  }
}

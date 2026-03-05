import '../model/auth_tokens.dart';
import '../repository/auth_repository.dart';
import '../repository/token_storage.dart';

/// LoginUseCase — Application use case for user authentication.
///
/// Thin orchestration layer: delegates to [AuthRepository] and
/// stores all tokens securely in [TokenStorage] (AC5).
///
/// AC1: successful login → access token + refresh token stored.
/// AC4: account lockout → surfaces AuthException(ACCOUNT_LOCKED).
class LoginUseCase {
  final AuthRepository _repository;
  final TokenStorage _tokenStorage;

  const LoginUseCase(this._repository, this._tokenStorage);

  /// Execute login with [phoneNumber] and [password].
  ///
  /// On success, stores all tokens in secure storage (AC5, AC1).
  /// Propagates [AuthException] from repository unchanged:
  /// - INVALID_CREDENTIALS: wrong phone or password
  /// - ACCOUNT_LOCKED: too many failed attempts
  Future<AuthTokens> execute({
    required String phoneNumber,
    required String password,
  }) async {
    final tokens = await _repository.login(
      phoneNumber: phoneNumber.trim(),
      password: password,
    );

    // AC5: Store ALL tokens securely — NEVER SharedPreferences
    await Future.wait<void>([
      _tokenStorage.saveToken(tokens.accessToken),
      _tokenStorage.saveRefreshToken(tokens.refreshToken),
      _tokenStorage.saveUserId(tokens.userId),
      _tokenStorage.saveTenantId(tokens.tenantId),
    ]);

    return tokens;
  }
}

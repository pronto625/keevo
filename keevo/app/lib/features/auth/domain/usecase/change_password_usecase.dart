import '../model/auth_tokens.dart';
import '../repository/auth_repository.dart';
import '../repository/token_storage.dart';

/// ChangePasswordUseCase — Story 3.5 AC4.
///
/// Validates the new password, calls the repository, stores fresh tokens,
/// and returns the new [AuthTokens].
class ChangePasswordUseCase {
  final AuthRepository _repository;
  final TokenStorage _tokenStorage;

  const ChangePasswordUseCase(this._repository, this._tokenStorage);

  /// Execute password change with [currentPassword] and [newPassword].
  ///
  /// Validates:
  /// - newPassword ≥ 8 characters
  /// - newPassword contains at least one digit
  ///
  /// On success, persists new tokens in secure storage.
  Future<AuthTokens> execute({
    required String currentPassword,
    required String newPassword,
  }) async {
    // Client-side validation matching AC4 rules
    if (newPassword.length < 8) {
      throw ArgumentError.value(
        newPassword,
        'newPassword',
        'Le mot de passe doit contenir au moins 8 caractères',
      );
    }
    if (!newPassword.contains(RegExp(r'[0-9]'))) {
      throw ArgumentError.value(
        newPassword,
        'newPassword',
        'Le mot de passe doit contenir au moins un chiffre',
      );
    }

    final tokens = await _repository.changePassword(
      currentPassword: currentPassword,
      newPassword: newPassword,
    );

    // Persist new tokens
    await Future.wait([
      _tokenStorage.saveToken(tokens.accessToken),
      _tokenStorage.saveRefreshToken(tokens.refreshToken),
      _tokenStorage.saveUserId(tokens.userId),
      _tokenStorage.saveTenantId(tokens.tenantId),
      _tokenStorage.saveStoreId(tokens.storeId),
    ]);

    return tokens;
  }
}

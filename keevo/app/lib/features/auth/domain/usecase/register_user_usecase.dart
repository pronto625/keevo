import '../model/registration_result.dart';
import '../repository/auth_repository.dart';
import '../repository/token_storage.dart';

/// RegisterUserUseCase — Application use case for user registration.
///
/// Thin orchestration layer: validates inputs, delegates to [AuthRepository],
/// and stores the JWT token securely (AC5).
class RegisterUserUseCase {
  final AuthRepository _repository;
  final TokenStorage _tokenStorage;

  const RegisterUserUseCase(this._repository, this._tokenStorage);

  /// Execute registration with [phoneNumber] and [password].
  ///
  /// Throws [ArgumentError] for invalid inputs before any network call.
  /// On success, stores JWT + user/tenant IDs in secure storage (AC5).
  Future<RegistrationResult> execute({
    required String phoneNumber,
    required String password,
  }) async {
    final trimmedPhone = phoneNumber.trim();
    final trimmedPassword = password.trim();

    if (trimmedPhone.isEmpty) {
      throw ArgumentError.value(phoneNumber, 'phoneNumber', 'Phone number must not be empty');
    }

    if (!RegExp(r'^\+?[0-9]{8,15}$').hasMatch(trimmedPhone)) {
      throw ArgumentError.value(phoneNumber, 'phoneNumber', 'Invalid phone number format');
    }

    if (trimmedPassword.length < 8) {
      throw ArgumentError.value(password, 'password', 'Password must be at least 8 characters');
    }

    final result = await _repository.register(
      phoneNumber: trimmedPhone,
      password: trimmedPassword,
    );

    // AC5: Store token securely — NEVER SharedPreferences or plaintext
    await _tokenStorage.saveToken(result.token);
    await _tokenStorage.saveUserId(result.userId);
    await _tokenStorage.saveTenantId(result.tenantId);

    return result;
  }
}

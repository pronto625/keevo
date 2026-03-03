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
}

/// TokenStorage — Port interface for secure token persistence.
///
/// AC5 requirement: tokens must be stored securely (never SharedPreferences
/// or plaintext). Implementation uses flutter_secure_storage.
abstract interface class TokenStorage {
  /// Persist the JWT token.
  Future<void> saveToken(String token);

  /// Persist the user ID.
  Future<void> saveUserId(String userId);

  /// Persist the tenant ID.
  Future<void> saveTenantId(String tenantId);

  /// Retrieve the stored JWT token (null if not set).
  Future<String?> getToken();

  /// Clear all stored credentials (for logout).
  Future<void> clearAll();
}

/// TokenStorage — Port interface for secure token persistence.
///
/// AC5 requirement: tokens must be stored securely (never SharedPreferences
/// or plaintext). Implementation uses flutter_secure_storage.
abstract interface class TokenStorage {
  /// Persist the JWT access token.
  Future<void> saveToken(String token);

  /// Persist the opaque refresh token.
  Future<void> saveRefreshToken(String refreshToken);

  /// Persist the user ID.
  Future<void> saveUserId(String userId);

  /// Persist the tenant ID (schema name, e.g. kv_xxxxxx).
  Future<void> saveTenantId(String tenantId);

  /// Retrieve the stored JWT access token (null if not set).
  Future<String?> getToken();

  /// Retrieve the stored refresh token (null if not set).
  Future<String?> getRefreshToken();

  /// Clear all stored credentials (for logout or refresh failure).
  Future<void> clearAll();
}

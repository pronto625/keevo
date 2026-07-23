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

  /// Persist the assigned store ID (EMPLOYEE only) — Story 3.5.
  Future<void> saveStoreId(String? storeId);

  /// Retrieve the stored JWT access token (null if not set).
  Future<String?> getToken();

  /// Retrieve the stored refresh token (null if not set).
  Future<String?> getRefreshToken();

  /// Retrieve the stored user ID (null if not set) — Story 14.11 (Task 9.1bis).
  Future<String?> getUserId();

  /// Retrieve the stored store ID (null for OWNER) — Story 3.5.
  Future<String?> getStoreId();

  /// Clear all stored credentials (for logout or refresh failure).
  Future<void> clearAll();
}

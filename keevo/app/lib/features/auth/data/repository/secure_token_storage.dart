import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../domain/repository/token_storage.dart';

/// SecureTokenStorage — Implementation of [TokenStorage] using flutter_secure_storage.
///
/// AC5: tokens are NEVER stored in SharedPreferences or plaintext.
/// All keys are stored in the platform's secure enclave (Keychain / KeyStore).
class SecureTokenStorage implements TokenStorage {
  final FlutterSecureStorage _storage;

  static const _keyToken = 'jwt_token';
  static const _keyUserId = 'user_id';
  static const _keyTenantId = 'tenant_id';

  const SecureTokenStorage(this._storage);

  @override
  Future<void> saveToken(String token) =>
      _storage.write(key: _keyToken, value: token);

  @override
  Future<void> saveUserId(String userId) =>
      _storage.write(key: _keyUserId, value: userId);

  @override
  Future<void> saveTenantId(String tenantId) =>
      _storage.write(key: _keyTenantId, value: tenantId);

  @override
  Future<String?> getToken() => _storage.read(key: _keyToken);

  @override
  Future<void> clearAll() => _storage.deleteAll();
}

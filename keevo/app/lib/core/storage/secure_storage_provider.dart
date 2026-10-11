import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Factory for a hardened [FlutterSecureStorage] instance with platform-specific
/// options:
/// - Android: `encryptedSharedPreferences: true` (AES-256 backing, S8)
/// - iOS: `first_unlock_this_device` (available after first post-boot unlock)
///
/// All production call sites MUST use this factory instead of
/// `const FlutterSecureStorage()` directly.
FlutterSecureStorage buildSecureStorage() {
  return const FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.first_unlock_this_device,
    ),
  );
}

/// Secure-storage keys tied to the signed-in session.
///
/// `db_encryption_key` and `keevo_device_id` are deliberately absent: they
/// belong to the device. Deleting the DB key makes the next cold start
/// discard the encrypted DB, including every sale still in `sync_queue`.
const _sessionSecureKeys = <String>[
  'jwt_token',
  'refresh_token',
  'user_id',
  'tenant_id',
  'store_id',
  'last_sync_timestamp_ms',
];

/// Signs the session out of secure storage without touching device secrets.
/// Use this instead of `deleteAll()` for logout and session expiry.
Future<void> clearSessionSecrets(FlutterSecureStorage storage) async {
  for (final key in _sessionSecureKeys) {
    await storage.delete(key: key);
  }
}

/// SharedPreferences key flagging that the one-shot migration from the legacy
/// (unencrypted) backing store to [buildSecureStorage] has completed.
const _kMigrationDoneFlag = 'secure_storage_migrated_v1';

/// Keys known to be stored in FlutterSecureStorage across the app.
///
/// If a new secure-storage key is introduced in the future, add it here so
/// the migration picks it up for upgrading users.
const _allSecureKeys = <String>[
  'jwt_token',
  'refresh_token',
  'user_id',
  'tenant_id',
  'store_id',
  'db_encryption_key',
  'keevo_device_id',
  'last_sync_timestamp_ms',
];

/// One-shot migration from the legacy `FlutterSecureStorage()` (default
/// Android options — standard SharedPreferences backing) to
/// [buildSecureStorage()] (`encryptedSharedPreferences: true`).
///
/// **Why this exists:** `encryptedSharedPreferences: true` changes the backing
/// store on Android. Without migration, upgrading users would lose all tokens
/// AND the SQLCipher DB encryption key (making the local database unreadable).
///
/// Idempotent: guarded by [_kMigrationDoneFlag] in SharedPreferences. Runs
/// once per device, then skipped on subsequent launches.
///
/// Must be called in `main()` BEFORE [buildSecureStorage] is used for any
/// other purpose (especially `DbEncryptionKeyService.getOrCreate()`).
Future<void> migrateToEncryptedStorage({
  required SharedPreferences prefs,
}) async {
  if (prefs.getBool(_kMigrationDoneFlag) == true) return;

  final oldStorage = const FlutterSecureStorage();
  final newStorage = buildSecureStorage();

  for (final key in _allSecureKeys) {
    final value = await oldStorage.read(key: key);
    if (value != null) {
      await newStorage.write(key: key, value: value);
    }
  }

  await prefs.setBool(_kMigrationDoneFlag, true);
}

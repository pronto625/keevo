import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../storage/app_database.dart';
import '../sync/sync_service.dart';

/// Encryption key provider — overridden in main.dart with the actual key.
///
/// This indirection allows AppDatabase to be constructed lazily by Riverpod
/// while the key is available synchronously (set before runApp).
final dbEncryptionKeyProvider = Provider<String>((ref) {
  throw UnimplementedError('Override dbEncryptionKeyProvider in main.dart');
});

/// Global AppDatabase provider — singleton, encrypted via SQLCipher.
///
/// Reads the hex key from [dbEncryptionKeyProvider] which is overridden
/// in main.dart after [DbEncryptionKeyService.getOrCreate()] completes.
final appDatabaseProvider = Provider<AppDatabase>((ref) {
  final hexKey = ref.read(dbEncryptionKeyProvider);
  dev.log(
    '[DB] Initializing AppDatabase with SQLCipher key (${hexKey.length} chars) ✅',
    name: 'AppDatabase',
  );
  final db = AppDatabase(hexKey: hexKey);
  ref.onDispose(db.close);
  return db;
});

/// SharedPreferences provider — overridden in main.dart before runApp().
final sharedPreferencesProvider = Provider<SharedPreferences>((ref) {
  throw UnimplementedError('Override sharedPreferencesProvider in main.dart');
});

/// SyncService provider — stub implementation for Epic 1.
/// Strategy pattern: Epic 5 will replace this with RestSyncService.
final syncServiceProvider = Provider<SyncService>((ref) => _StubSyncService());

class _StubSyncService implements SyncService {
  @override
  Future<void> push() async {}

  @override
  Future<void> pull() async {}

  @override
  Future<void> queueOperation({
    required String operation,
    required Map<String, dynamic> payload,
  }) async {}
}

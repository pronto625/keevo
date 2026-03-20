import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dio/dio.dart';

import '../services/api_service.dart';
import '../storage/app_database.dart';
import '../sync/connectivity_service.dart';
import '../sync/connectivity_service_impl.dart';
import '../sync/rest_sync_service.dart';
import '../sync/sync_service.dart';
import '../../features/catalog/data/datasource/remote_product_datasource.dart';
import '../../features/auth/presentation/provider/auth_provider.dart';

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

/// Current user role for the active tenant: 'OWNER' | 'EMPLOYEE' | null.
///
/// Persisted by [TenantPickerPage] and [LoginUseCase] after tenant selection.
/// Story 3.4 — AC3.
const kUserRoleKey = 'user_role';

final currentUserRoleProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kUserRoleKey);
});

/// Current user phone number (e.g. +237600000000) — set at login.
const kUserPhoneKey = 'user_phone';

final currentUserPhoneProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kUserPhoneKey);
});

/// Current user ID (UUID) — read from FlutterSecureStorage (key: 'user_id').
/// Used in audit trail to mark entries made by the current user as "Vous".
final currentUserIdProvider = FutureProvider<String?>((ref) async {
  const storage = FlutterSecureStorage();
  return storage.read(key: 'user_id');
});

/// Password change required flag — Story 3.5.
/// Stored in SharedPreferences (not secret, just a UI routing flag).
const kPasswordChangeRequiredKey = 'pwd_change_req';

final passwordChangeRequiredProvider = Provider<bool>((ref) {
  return ref.watch(sharedPreferencesProvider).getBool(kPasswordChangeRequiredKey) ?? false;
});

/// SyncService provider — REST implementation for offline queue batch push sync.
/// Online writes go backend-first via individual REST endpoints;
/// this service replays the offline safety-net queue via POST /api/v1/sync/push.
final syncServiceProvider = Provider<SyncService>((ref) {
  final database = ref.watch(appDatabaseProvider);
  final dio = ref.watch(dioProvider);
  final remoteProducts = RemoteProductDataSource(dio: dio);
  
  return RestSyncService(
    database: database,
    remoteProducts: remoteProducts,
    dio: dio,
    secureStorage: const FlutterSecureStorage(),
  );
});

/// ConnectivityService provider — production implementation using connectivity_plus.
/// Used by repositories to check if device is online before write operations.
final connectivityServiceProvider = Provider<ConnectivityService>((ref) {
  return ConnectivityServiceImpl();
});

/// ApiService provider — default stub implementation.
/// Will be overridden where needed to use the authenticated version.
final apiServiceProvider = Provider<ApiService>((ref) {
  return StubApiService();
});

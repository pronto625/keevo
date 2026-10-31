import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api_service.dart';
import '../storage/app_database.dart';
import '../sync/connectivity_service.dart';
import '../sync/connectivity_service_impl.dart';
import '../sync/sync_event_logger.dart';

/// Story 15.4a — kernel providers that need neither the network client
/// (`dioProvider`) nor a feature: they live in `keevo_core`. The app-shell
/// providers that do (`syncServiceProvider`, `syncDiagnosticRunnerProvider`)
/// stay in the app (`lib/core/di/providers.dart`).

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
/// Persisted by `TenantPickerPage` and `LoginUseCase` (app features) after
/// tenant selection.
/// Story 3.4 — AC3.
const kUserRoleKey = 'user_role';

final currentUserRoleProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kUserRoleKey);
});

/// True when the session is an OWNER one. Owner-only endpoints (dashboard
/// summary, employees list, stores list) answer 403 to an employee: their
/// callers check this before going to the server and fall back to the local
/// data (filled by the sync pull) otherwise.
final isOwnerSessionProvider = Provider<bool>((ref) {
  return ref.watch(currentUserRoleProvider) == 'OWNER';
});

/// Current user phone number (e.g. +237600000000) — set at login.
const kUserPhoneKey = 'user_phone';

final currentUserPhoneProvider = Provider<String?>((ref) {
  return ref.watch(sharedPreferencesProvider).getString(kUserPhoneKey);
});

/// Password change required flag — Story 3.5.
/// Stored in SharedPreferences (not secret, just a UI routing flag).
const kPasswordChangeRequiredKey = 'pwd_change_req';

final passwordChangeRequiredProvider = Provider<bool>((ref) {
  return ref.watch(sharedPreferencesProvider).getBool(kPasswordChangeRequiredKey) ?? false;
});

/// ConnectivityService provider — production implementation using connectivity_plus.
/// Used by repositories to check if device is online before write operations.
final connectivityServiceProvider = Provider<ConnectivityService>((ref) {
  return ConnectivityServiceImpl();
});

/// SyncEventLogger provider — logs push/pull/diagnostic events to local Drift sync_events table.
final syncEventLoggerProvider = Provider<SyncEventLogger>((ref) {
  return SyncEventLogger(ref.watch(appDatabaseProvider));
});

/// ApiService provider — default stub implementation.
/// Will be overridden where needed to use the authenticated version.
final apiServiceProvider = Provider<ApiService>((ref) {
  return StubApiService();
});

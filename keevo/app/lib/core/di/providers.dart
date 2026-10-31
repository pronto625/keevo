import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/diagnostics/sync_diagnostic_runner.dart';
import 'package:keevo_core/sync/sync_service.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../../features/catalog/data/datasource/remote_product_datasource.dart';
import '../modules/registered_modules.dart';
import '../sync/rest_sync_service.dart';

/// App-shell providers: they need `dioProvider` (feature `auth`) or the
/// feature-coupled [RestSyncService]. Kernel providers (database, prefs,
/// session role, connectivity…) live in `core_providers.dart` (keevo_core).

/// Current user ID (UUID) — read from FlutterSecureStorage (key: 'user_id').
/// Used in audit trail to mark entries made by the current user as "Vous".
final currentUserIdProvider = FutureProvider<String?>((ref) async {
  return ref.read(flutterSecureStorageProvider).read(key: 'user_id');
});

/// Sync device id of this install (secure storage `keevo_device_id`, written
/// by the sync service). v1s-16-20: only used to tell « Cet appareil » from
/// « Autre appareil » — never displayed. Auto-disposed: re-read each time the
/// screen opens, so an id written lazily by the first sync is picked up.
final currentDeviceIdProvider =
    FutureProvider.autoDispose<String?>((ref) async {
  return ref.read(flutterSecureStorageProvider).read(key: kDeviceIdKey);
});

/// SyncService provider — REST implementation for offline queue batch push sync.
/// Online writes go backend-first via individual REST endpoints;
/// this service replays the offline safety-net queue via POST /api/v1/sync/push.
final syncServiceProvider = Provider<SyncService>((ref) {
  final database = ref.watch(appDatabaseProvider);
  final dio = ref.watch(dioProvider);
  final remoteProducts = RemoteProductDataSource(dio: dio);
  final prefs = ref.watch(sharedPreferencesProvider);

  return RestSyncService(
    database: database,
    remoteProducts: remoteProducts,
    dio: dio,
    secureStorage: ref.watch(flutterSecureStorageProvider),
    prefs: prefs,
    // Story 15.5b — pulled module entities (invoices) → module_records.
    moduleMergers: composeModuleMergers(kRegisteredModules),
    moduleRecordPaths: composeModuleRecordPaths(kRegisteredModules),
  );
});

/// SyncDiagnosticRunner provider — health + JWT checks with 6h cooldown.
final syncDiagnosticRunnerProvider = Provider<SyncDiagnosticRunner>((ref) {
  return SyncDiagnosticRunner(
    dio: ref.watch(dioProvider),
    secureStorage: ref.watch(flutterSecureStorageProvider),
    prefs: ref.watch(sharedPreferencesProvider),
    eventLogger: ref.watch(syncEventLoggerProvider),
  );
});

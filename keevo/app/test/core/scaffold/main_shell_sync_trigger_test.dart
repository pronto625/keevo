import 'dart:async';
import 'dart:ffi';
import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/scaffold/main_shell.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart'
    show flutterSecureStorageProvider;
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

import '../../helpers/fake_entitlements.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

/// Secure storage that cannot be read (no token, no FCM registration).
class _BrokenStorage extends FlutterSecureStorage {
  const _BrokenStorage();

  @override
  Future<String?> read({
    required String key,
    IOSOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    MacOsOptions? mOptions,
    WindowsOptions? wOptions,
  }) async =>
      throw Exception('storage unavailable');
}

/// The real trigger (its connectivity listener and debounce), with the sync
/// itself replaced by a counter.
class _CountingTrigger extends SyncTriggerNotifier {
  static int syncs = 0;

  @override
  Future<void> triggerSync() async => syncs++;

  @override
  Future<void> onAppStartup() async {}
}

/// Story 15.4b — Riverpod 3 pauses a widget's watches while a full-screen page
/// covers it: the sync trigger started by the shell must still hear the
/// network coming back.
void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  testWidgets(
      'a full-screen page over the shell: the sync still starts when the '
      'network comes back', (tester) async {
    SharedPreferences.setMockInitialValues({kUserRoleKey: 'EMPLOYEE'});
    final prefs = await SharedPreferences.getInstance();
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
    final network = StreamController<List<ConnectivityResult>>();
    addTearDown(network.close);
    _CountingTrigger.syncs = 0;

    final rootKey = GlobalKey<NavigatorState>();
    final router = GoRouter(
      navigatorKey: rootKey,
      initialLocation: '/pos',
      routes: [
        ShellRoute(
          builder: (_, __, child) => MainShell(child: child),
          routes: [
            GoRoute(path: '/pos', builder: (_, __) => const Text('Caisse')),
          ],
        ),
        GoRoute(
          path: '/full',
          parentNavigatorKey: rootKey,
          builder: (_, __) => const Scaffold(body: Text('Plein écran')),
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        appDatabaseProvider.overrideWithValue(db),
        flutterSecureStorageProvider.overrideWithValue(const _BrokenStorage()),
        entitlementsProvider
            .overrideWith(() => FakeEntitlementsNotifier(() async => {})),
        pendingDraftsCountProvider.overrideWith((ref) async => 0),
        deletionPendingStatusProvider.overrideWith((ref) async => null),
        connectivityStreamProvider.overrideWith((ref) => network.stream),
        syncTriggerNotifierProvider.overrideWith(_CountingTrigger.new),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pump(const Duration(milliseconds: 100));
    network.add([ConnectivityResult.none]);
    await tester.pump(const Duration(milliseconds: 100));

    router.push('/full');
    await tester.pumpAndSettle();
    expect(find.text('Plein écran'), findsOneWidget);
    expect(find.text('Caisse'), findsNothing); // the shell is covered

    network.add([ConnectivityResult.wifi]);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(seconds: 4)); // 3 s debounce

    expect(_CountingTrigger.syncs, 1);

    // Unmount: the trigger's periodic timers are cancelled with the scope.
    await tester.pumpWidget(const SizedBox());
  });
}

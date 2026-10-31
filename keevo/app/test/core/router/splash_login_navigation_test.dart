import 'dart:ffi';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/core/router/session_presence_cache.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/auth/presentation/page/auth_page.dart';
import 'package:keevo/features/auth/presentation/page/forgot_password_page.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/onboarding/presentation/page/terms_page.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

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

/// Secure storage that cannot be read (corrupted keystore): the splash's
/// `resolveStartupRoute` throws.
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
      throw StateError('keystore unreadable');
}

/// The router's session cache still answers « signed in » (cached before the
/// storage broke) — the case that looped splash → login → splash.
class _SignedInCache extends SessionPresenceCache {
  int calls = 0;
  _SignedInCache() : super(const _BrokenStorage());

  @override
  Future<bool> hasSession() async {
    calls++;
    return true;
  }
}

/// Signed out (healthy storage, nothing stored).
class _SignedOutCache extends SessionPresenceCache {
  _SignedOutCache() : super(const FlutterSecureStorage());

  @override
  Future<bool> hasSession() async => false;
}

class _IdleSync extends SyncTriggerNotifier {
  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async {}

  @override
  Future<void> onAppStartup() async {}
}

/// v1s-16-13 follow-up: the splash-failure marker is checked on the real
/// [appRouter] (top-level guard + `/auth/login` route redirect + splash
/// page), not only on the extracted redirect function.
void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  testWidgets(
      'splash failure with a cached session lands on the login form and '
      'stays there (no splash ↔ login loop)', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
    final cache = _SignedInCache();

    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        appDatabaseProvider.overrideWithValue(db),
        flutterSecureStorageProvider.overrideWithValue(const _BrokenStorage()),
        sessionPresenceCacheProvider.overrideWithValue(cache),
        syncTriggerNotifierProvider.overrideWith(_IdleSync.new),
      ],
      child: MaterialApp.router(routerConfig: appRouter),
    ));
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    String location() =>
        appRouter.routerDelegate.currentConfiguration.uri.path;
    expect(location(), '/auth/login');
    expect(find.byType(AuthPage), findsOneWidget);

    // Stable: no further navigation while time passes.
    final callsAfterLanding = cache.calls;
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(location(), '/auth/login');
    expect(cache.calls, callsAfterLanding,
        reason: 'no more redirects once the login form is shown');

    // A plain /auth/login (no marker) with a session goes through the splash
    // once, which fails again and comes back to the form — and stops.
    appRouter.go('/auth/login');
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(location(), '/auth/login');
    expect(find.byType(AuthPage), findsOneWidget);
    final callsAfterSecond = cache.calls;
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(cache.calls, callsAfterSecond);

    await tester.pumpWidget(const SizedBox());
  });

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  String location() => appRouter.routerDelegate.currentConfiguration.uri.path;

  Future<void> pumpApp(WidgetTester tester,
      {required SessionPresenceCache cache,
      required FlutterSecureStorage storage}) async {
    final db = AppDatabase.forTesting();
    addTearDown(db.close);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        appDatabaseProvider.overrideWithValue(db),
        flutterSecureStorageProvider.overrideWithValue(storage),
        sessionPresenceCacheProvider.overrideWithValue(cache),
        syncTriggerNotifierProvider.overrideWith(_IdleSync.new),
      ],
      child: MaterialApp.router(routerConfig: appRouter),
    ));
    await settle(tester);
  }

  testWidgets('session without a stored role: an owner-only deep link never '
      'opens, the splash decides', (tester) async {
    SharedPreferences.setMockInitialValues({}); // no user_role
    await pumpApp(tester,
        cache: _SignedInCache(), storage: const _BrokenStorage());

    appRouter.go('/dashboard');
    await settle(tester);

    // Splash re-ran (and, storage broken, fell back to the login form).
    expect(location(), '/auth/login');
    expect(find.byType(AuthPage), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('signed out: reset-password without its phone restarts at '
      'forgot-password; /terms/view is reachable', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({
      'onboarding_seen': true,
      'terms_accepted': true,
    });
    await pumpApp(tester,
        cache: _SignedOutCache(), storage: const FlutterSecureStorage());
    expect(location(), '/auth/login');

    appRouter.go('/auth/reset-password');
    await settle(tester);
    expect(location(), '/auth/forgot-password');
    expect(find.byType(ForgotPasswordPage), findsOneWidget);

    appRouter.go('/auth/reset-password', extra: {'phoneNumber': '  '});
    await settle(tester);
    expect(location(), '/auth/forgot-password');

    appRouter.go('/terms/view?section=privacy');
    await settle(tester);
    expect(location(), '/terms/view');
    expect(tester.widget<TermsPage>(find.byType(TermsPage)).readOnly, true);

    // Opened without a stack (deep link): back lets the splash decide —
    // signed out here, so the login form (never the register form).
    await tester.tap(find.byIcon(Icons.arrow_back));
    await settle(tester);
    expect(location(), '/auth/login');
    await tester.pumpWidget(const SizedBox());
  });
}

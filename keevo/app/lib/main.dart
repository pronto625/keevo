import 'dart:developer' as dev;
import 'dart:ffi';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

import 'core/di/providers.dart';
import 'core/router/app_router.dart';
import 'core/storage/db_encryption_key_service.dart';
import 'core/storage/secure_storage_provider.dart';
import 'core/theme/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // ── 0a. Initialize date formatting for fr_FR locale ──────────────────────
  await initializeDateFormatting('fr_FR');

  // ── 0. Override SQLite native library loader ────────────────────────────────
  // sqlcipher_flutter_libs ships libsqlcipher.so on Android, NOT libsqlite3.so.
  // Without this override, sqlite3 (used by drift) tries to dlopen libsqlite3.so
  // and crashes with "library not found". Must be done before any DB access.
  if (Platform.isAndroid) {
    open.overrideFor(
      OperatingSystem.android,
      () => DynamicLibrary.open('libsqlcipher.so'),
    );
  }

  // ── 1. SharedPreferences (sync read for providers) ─────────────────────────
  final prefs = await SharedPreferences.getInstance();

  // ── 1b. Migrate legacy secure storage → encryptedSharedPreferences ──────────
  // One-shot: reads old backing store (standard SharedPreferences) and writes
  // all keys into the new hardened store (EncryptedSharedPreferences). Critical
  // to preserve the SQLCipher DB encryption key across upgrades (Story 12.7 D1).
  await migrateToEncryptedStorage(prefs: prefs);

  // ── 2. SQLCipher encryption key (generate on first launch, reuse after) ────
  final secureStorage = buildSecureStorage();
  final keyService = DbEncryptionKeyService(storage: secureStorage);
  final hexKey = await keyService.getOrCreate();

  dev.log(
    '[DB] Encryption key loaded — ${hexKey.length} chars '
    '(${hexKey.substring(0, 8)}…) ✅',
    name: 'AppDatabase',
  );

  // ── 3. Bootstrap app with providers ────────────────────────────────────────
  runApp(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        // Pass the hex key so appDatabaseProvider can open the encrypted DB.
        dbEncryptionKeyProvider.overrideWithValue(hexKey),
      ],
      child: const KeevoApp(),
    ),
  );
}

class KeevoApp extends ConsumerWidget {
  const KeevoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      title: 'Keevo',
      scaffoldMessengerKey: rootScaffoldMessengerKey,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      themeMode: ThemeMode.system,
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [
        Locale('fr', 'FR'),
        Locale('en', 'US'),
      ],
      locale: const Locale('fr', 'FR'),
      routerConfig: appRouter,
    );
  }
}

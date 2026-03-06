import 'dart:developer' as dev;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/di/providers.dart';
import 'core/router/app_router.dart';
import 'core/storage/db_encryption_key_service.dart';
import 'core/theme/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // ── 1. SharedPreferences (sync read for providers) ─────────────────────────
  final prefs = await SharedPreferences.getInstance();

  // ── 2. SQLCipher encryption key (generate on first launch, reuse after) ────
  const secureStorage = FlutterSecureStorage();
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
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      themeMode: ThemeMode.system,
      routerConfig: appRouter,
    );
  }
}

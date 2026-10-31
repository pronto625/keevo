import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'app_constants.dart';

/// DbEncryptionKeyService — manages the SQLCipher database encryption key.
///
/// Generates a 32-byte random key (hex-encoded, 64 chars) on first launch
/// and stores it in Flutter Secure Storage. Returns the same key on all
/// subsequent launches (idempotent).
///
/// Facade pattern: hides key-management complexity from AppDatabase.
class DbEncryptionKeyService {
  final FlutterSecureStorage storage;

  const DbEncryptionKeyService({required this.storage});

  /// Returns the existing encryption key, or generates + stores a new one.
  ///
  /// Uses [Random.secure()] for cryptographically strong random bytes.
  Future<String> getOrCreate() async {
    final existing = await storage.read(key: kDbEncryptionKey);
    if (existing != null) return existing;
    final key = _generateHexKey();
    await storage.write(key: kDbEncryptionKey, value: key);
    return key;
  }

  /// Generates a 64-character lowercase hex string from 32 random bytes.
  String _generateHexKey() {
    final rng = Random.secure();
    final bytes = List<int>.generate(32, (_) => rng.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }
}

import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../sync_event_logger.dart';

/// Auto-diagnostic check — runs when sync queue is stale (>1h with pending ops while online).
///
/// Strategy pattern: each check is a function returning a diagnostic result.
/// Cooldown: 6 hours between runs (tracked via SharedPreferences).
class SyncDiagnosticRunner {
  static const String kLastDiagnosticAtKey = 'last_diagnostic_at_ms';
  static const Duration cooldown = Duration(hours: 6);

  final Dio _dio;
  final FlutterSecureStorage _secureStorage;
  final SharedPreferences _prefs;
  final SyncEventLogger _eventLogger;

  SyncDiagnosticRunner({
    required Dio dio,
    required FlutterSecureStorage secureStorage,
    required SharedPreferences prefs,
    required SyncEventLogger eventLogger,
  })  : _dio = dio,
        _secureStorage = secureStorage,
        _prefs = prefs,
        _eventLogger = eventLogger;

  /// Returns true if diagnostic ran, false if skipped (cooldown).
  Future<bool> runIfNeeded() async {
    // Cooldown check
    final lastMs = _prefs.getInt(kLastDiagnosticAtKey);
    if (lastMs != null) {
      final elapsed = DateTime.now()
          .difference(DateTime.fromMillisecondsSinceEpoch(lastMs));
      if (elapsed < cooldown) return false;
    }

    await _prefs.setInt(
        kLastDiagnosticAtKey, DateTime.now().millisecondsSinceEpoch);

    // Check 1: Server reachability
    bool serverReachable = false;
    try {
      final response = await _dio.get('/actuator/health');
      serverReachable = response.statusCode == 200;
    } catch (_) {}

    // Check 2: JWT validity
    bool jwtValid = false;
    try {
      final token = await _secureStorage.read(key: 'jwt_token');
      if (token != null) {
        jwtValid = _isJwtValid(token);
      }
    } catch (_) {}

    // Log result
    if (serverReachable && jwtValid) {
      await _eventLogger.logDiagnostic(
          'OK', 'Server reachable, JWT valid — sync issue may be transient');
    } else {
      final issues = <String>[];
      if (!serverReachable) issues.add('server unreachable');
      if (!jwtValid) issues.add('JWT expired/invalid');
      await _eventLogger.logDiagnostic('WARN', issues.join(', '));
    }

    return true;
  }

  /// Manual JWT validation without external package.
  bool _isJwtValid(String token) {
    final parts = token.split('.');
    if (parts.length != 3) return false;
    try {
      final payload = parts[1];
      final decoded = utf8.decode(
        base64Url.decode(base64Url.normalize(payload)),
      );
      final data = jsonDecode(decoded) as Map<String, dynamic>;
      final exp = data['exp'] as int?;
      if (exp == null) return false;
      return DateTime.now()
          .isBefore(DateTime.fromMillisecondsSinceEpoch(exp * 1000));
    } catch (_) {
      return false;
    }
  }
}

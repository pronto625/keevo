import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/diagnostics/sync_diagnostic_runner.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
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

/// Story 5.5 — Task 7.4: SyncDiagnosticRunner unit tests.
void main() {
  late AppDatabase db;
  late SyncEventLogger eventLogger;
  late SharedPreferences prefs;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  setUp(() async {
    db = AppDatabase.forTesting();
    eventLogger = SyncEventLogger(db);
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    FlutterSecureStorage.setMockInitialValues({});
  });

  tearDown(() async {
    await db.close();
  });

  SyncDiagnosticRunner _createRunner({
    required Dio dio,
    FlutterSecureStorage? secureStorage,
  }) {
    return SyncDiagnosticRunner(
      dio: dio,
      secureStorage: secureStorage ?? const FlutterSecureStorage(),
      prefs: prefs,
      eventLogger: eventLogger,
    );
  }

  group('SyncDiagnosticRunner', () {
    test('serverReachable + jwtValid → logs OK', () async {
      // Build a JWT that expires in the future
      final expFuture = DateTime.now()
              .add(const Duration(hours: 1))
              .millisecondsSinceEpoch ~/
          1000;
      final jwt = _buildJwt({'exp': expFuture});
      FlutterSecureStorage.setMockInitialValues({'jwt_token': jwt});

      final runner = _createRunner(
        dio: _FakeDio(statusCode: 200),
        secureStorage: const FlutterSecureStorage(),
      );

      final ran = await runner.runIfNeeded();
      expect(ran, isTrue);

      final events = await eventLogger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'DIAGNOSTIC');
      expect(events.first.status, 'OK');
    });

    test('serverUnreachable → logs WARN with "server unreachable"', () async {
      FlutterSecureStorage.setMockInitialValues({});
      final runner = _createRunner(
        dio: _FakeDio(shouldThrow: true),
      );

      await runner.runIfNeeded();

      final events = await eventLogger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.status, 'WARN');
      expect(events.first.errorMessage, contains('server unreachable'));
    });

    test('jwtExpired → logs WARN with "JWT expired/invalid"', () async {
      // Build a JWT that is already expired
      final expPast = DateTime.now()
              .subtract(const Duration(hours: 1))
              .millisecondsSinceEpoch ~/
          1000;
      final jwt = _buildJwt({'exp': expPast});
      FlutterSecureStorage.setMockInitialValues({'jwt_token': jwt});

      final runner = _createRunner(
        dio: _FakeDio(statusCode: 200),
        secureStorage: const FlutterSecureStorage(),
      );

      await runner.runIfNeeded();

      final events = await eventLogger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.status, 'WARN');
      expect(events.first.errorMessage, contains('JWT expired/invalid'));
    });

    test('cooldown 6h — second run within cooldown is skipped', () async {
      FlutterSecureStorage.setMockInitialValues({});
      final runner = _createRunner(dio: _FakeDio(shouldThrow: true));

      final ran1 = await runner.runIfNeeded();
      expect(ran1, isTrue);

      // Second call within cooldown — should return false
      final ran2 = await runner.runIfNeeded();
      expect(ran2, isFalse);

      // Only 1 event logged
      final events = await eventLogger.getHistory();
      expect(events, hasLength(1));
    });
  });
}

/// Build a minimal valid JWT with a given payload.
String _buildJwt(Map<String, dynamic> payload) {
  final header = base64Url.encode(utf8.encode('{"alg":"HS256","typ":"JWT"}'));
  final body = base64Url.encode(utf8.encode(jsonEncode(payload)));
  final sig = base64Url.encode(utf8.encode('signature'));
  return '$header.$body.$sig';
}

class _FakeDio implements Dio {
  final int statusCode;
  final bool shouldThrow;

  _FakeDio({this.statusCode = 200, this.shouldThrow = false});

  @override
  Future<Response<T>> get<T>(
    String path, {
    Object? data,
    Map<String, dynamic>? queryParameters,
    Options? options,
    CancelToken? cancelToken,
    ProgressCallback? onReceiveProgress,
  }) async {
    if (shouldThrow) {
      throw DioException(requestOptions: RequestOptions(path: path));
    }
    return Response(
      data: null,
      requestOptions: RequestOptions(path: path),
      statusCode: statusCode,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

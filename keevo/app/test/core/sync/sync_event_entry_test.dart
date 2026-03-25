import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/sync_event_logger.dart';
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

/// Story 5.5 — Task 7.1: SyncEventLogger unit tests.
void main() {
  late AppDatabase db;
  late SyncEventLogger logger;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  setUp(() {
    db = AppDatabase.forTesting();
    logger = SyncEventLogger(db);
  });

  tearDown(() async {
    await db.close();
  });

  group('SyncEventLogger', () {
    test('logPushSuccess creates entry with PUSH type and SUCCESS status',
        () async {
      await logger.logPushSuccess(3);

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'PUSH');
      expect(events.first.status, 'SUCCESS');
      expect(events.first.operationCount, 3);
      expect(events.first.errorMessage, isNull);
    });

    test('logPullFailed creates entry with PULL type and error message',
        () async {
      await logger.logPullFailed('Connection timeout');

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'PULL');
      expect(events.first.status, 'FAILED');
      expect(events.first.errorMessage, 'Connection timeout');
    });

    test('logDiagnostic creates entry with DIAGNOSTIC type', () async {
      await logger.logDiagnostic('WARN', 'server unreachable');

      final events = await logger.getHistory();
      expect(events, hasLength(1));
      expect(events.first.type, 'DIAGNOSTIC');
      expect(events.first.status, 'WARN');
      expect(events.first.errorMessage, 'server unreachable');
    });

    test('getHistory returns events ordered by createdAt DESC', () async {
      await logger.logPushSuccess(1);
      await Future.delayed(const Duration(milliseconds: 10));
      await logger.logPullSuccess(2);

      final events = await logger.getHistory();
      expect(events, hasLength(2));
      // Most recent first
      expect(events[0].type, 'PULL');
      expect(events[1].type, 'PUSH');
    });

    test('pruning keeps only 50 most recent entries', () async {
      // Insert 55 entries
      for (var i = 0; i < 55; i++) {
        await logger.logPushSuccess(i);
      }

      final events = await logger.getHistory(limit: 100);
      expect(events.length, 50);
    });
  });
}

import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/notifications/data/datasource/local_notification_datasource.dart';
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

void main() {
  late AppDatabase db;
  late LocalNotificationDataSource datasource;

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
  });

  setUp(() {
    db = AppDatabase.forTesting();
    datasource = LocalNotificationDataSource(db);
  });

  tearDown(() async {
    await db.close();
  });

  group('LocalNotificationDataSource', () {
    test('insert saves to Drift', () async {
      await datasource.insert(
        id: 'n-1',
        type: 'GENERAL',
        title: 'Test Title',
        body: 'Test Body',
        receivedAt: DateTime(2026, 1, 1),
      );

      final rows = await datasource.getAll();
      expect(rows, hasLength(1));
      expect(rows.first.id, 'n-1');
      expect(rows.first.title, 'Test Title');
      expect(rows.first.isRead, false);
    });

    test('markAsRead updates isRead', () async {
      await datasource.insert(
        id: 'n-2',
        type: 'GENERAL',
        title: 'Test',
        body: 'Body',
        receivedAt: DateTime(2026, 1, 1),
      );

      await datasource.markAsRead('n-2');

      final rows = await datasource.getAll();
      expect(rows.first.isRead, true);
    });

    test('markAllAsRead updates all unread', () async {
      await datasource.insert(
        id: 'n-3', type: 'A', title: 'T1', body: 'B1',
        receivedAt: DateTime(2026, 1, 1),
      );
      await datasource.insert(
        id: 'n-4', type: 'B', title: 'T2', body: 'B2',
        receivedAt: DateTime(2026, 1, 2),
      );

      await datasource.markAllAsRead();

      final count = await datasource.countUnread();
      expect(count, 0);
    });

    test('countUnread returns correct count', () async {
      await datasource.insert(
        id: 'n-5', type: 'A', title: 'T', body: 'B',
        receivedAt: DateTime(2026, 1, 1),
      );
      await datasource.insert(
        id: 'n-6', type: 'A', title: 'T', body: 'B',
        receivedAt: DateTime(2026, 1, 2),
      );
      await datasource.markAsRead('n-5');

      final count = await datasource.countUnread();
      expect(count, 1);
    });

    test('deleteOlderThan cleans up old entries', () async {
      await datasource.insert(
        id: 'old', type: 'A', title: 'Old', body: 'B',
        receivedAt: DateTime(2025, 1, 1),
      );
      await datasource.insert(
        id: 'new', type: 'A', title: 'New', body: 'B',
        receivedAt: DateTime(2026, 6, 1),
      );

      await datasource.deleteOlderThan(DateTime(2026, 1, 1));

      final rows = await datasource.getAll();
      expect(rows, hasLength(1));
      expect(rows.first.id, 'new');
    });

    test('getAll returns reverse chronological order', () async {
      await datasource.insert(
        id: 'n-a', type: 'A', title: 'Older', body: 'B',
        receivedAt: DateTime(2026, 1, 1),
      );
      await datasource.insert(
        id: 'n-b', type: 'A', title: 'Newer', body: 'B',
        receivedAt: DateTime(2026, 6, 1),
      );

      final rows = await datasource.getAll();
      expect(rows, hasLength(2));
      expect(rows.first.id, 'n-b'); // newer first
      expect(rows.last.id, 'n-a');
    });
  });
}

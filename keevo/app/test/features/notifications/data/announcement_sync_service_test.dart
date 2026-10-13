import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/notifications/data/datasource/local_notification_datasource.dart';
import 'package:keevo/features/notifications/data/datasource/remote_announcement_datasource.dart';
import 'package:keevo/features/notifications/data/repository/announcement_sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class _MockRemote extends Mock implements RemoteAnnouncementDataSource {}

class _MockDio extends Mock implements Dio {}

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
  late LocalNotificationDataSource local;
  late _MockRemote remote;
  late SharedPreferences prefs;
  final now = DateTime.utc(2026, 10, 13, 10);

  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    registerFallbackValue(DateTime(2026));
  });

  setUp(() async {
    db = AppDatabase.forTesting();
    local = LocalNotificationDataSource(db);
    remote = _MockRemote();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  tearDown(() async {
    await db.close();
  });

  AnnouncementSyncService service() => AnnouncementSyncService(
        remote: remote,
        local: local,
        prefs: prefs,
        now: () => now,
      );

  final announcement = RemoteAnnouncement(
    id: 'g-1',
    title: 'Maintenance ce soir',
    body: 'Keevo indisponible 22h-23h',
    sentAt: DateTime.utc(2026, 10, 12, 18),
  );

  group('AnnouncementSyncService (Story 9-5)', () {
    test('first run asks 90 days back and stores each announcement unread',
        () async {
      when(() => remote.fetch(since: any(named: 'since')))
          .thenAnswer((_) async => [announcement]);

      expect(await service().sync(), 1);

      verify(() => remote.fetch(since: now.subtract(const Duration(days: 90))))
          .called(1);
      final rows = await local.getAll();
      expect(rows, hasLength(1));
      expect(rows.single.id, 'g-1');
      expect(rows.single.type, 'GLOBAL_ANNOUNCEMENT');
      expect(rows.single.title, 'Maintenance ce soir');
      expect(rows.single.isRead, isFalse);
      expect(rows.single.receivedAt.toUtc(), announcement.sentAt);
      expect(prefs.getString(kAnnouncementsFetchedAtKey),
          '2026-10-12T18:00:00.000Z');
    });

    test('next run asks since the latest sentAt already fetched', () async {
      await prefs.setString(
          kAnnouncementsFetchedAtKey, '2026-10-12T18:00:00.000Z');
      when(() => remote.fetch(since: any(named: 'since')))
          .thenAnswer((_) async => []);

      await service().sync();

      verify(() => remote.fetch(since: DateTime.utc(2026, 10, 12, 18)))
          .called(1);
    });

    test('an announcement already stored from FCM is not duplicated and '
        'keeps its read state', () async {
      await local.insert(
        id: 'g-1',
        type: 'GLOBAL_ANNOUNCEMENT',
        title: 'Maintenance ce soir',
        body: 'Keevo indisponible 22h-23h',
        receivedAt: DateTime(2026, 10, 12, 19),
      );
      await local.markAsRead('g-1');
      when(() => remote.fetch(since: any(named: 'since')))
          .thenAnswer((_) async => [announcement]);

      expect(await service().sync(), 0);

      final rows = await local.getAll();
      expect(rows, hasLength(1));
      expect(rows.single.isRead, isTrue);
    });

    test('offline: skipped silently, nothing stored, since unchanged',
        () async {
      when(() => remote.fetch(since: any(named: 'since'))).thenThrow(
          DioException(requestOptions: RequestOptions(path: '/x')));

      expect(await service().sync(), 0);

      expect(await local.getAll(), isEmpty);
      expect(prefs.getString(kAnnouncementsFetchedAtKey), isNull);
    });
  });

  group('RemoteAnnouncementDataSource', () {
    test('GET with since in UTC, unwraps data', () async {
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>(any(),
              queryParameters: any(named: 'queryParameters')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/x'),
                data: {
                  'data': [
                    {
                      'id': 'g-1',
                      'title': 'T',
                      'body': 'B',
                      'sentAt': '2026-10-12T18:00:00Z',
                    }
                  ]
                },
              ));

      final list = await RemoteAnnouncementDataSource(dio: dio)
          .fetch(since: DateTime.utc(2026, 7, 15, 10));

      verify(() => dio.get<Map<String, dynamic>>(
            '/api/v1/notifications/announcements',
            queryParameters: {'since': '2026-07-15T10:00:00.000Z'},
          )).called(1);
      expect(list.single.id, 'g-1');
      expect(list.single.sentAt, DateTime.utc(2026, 10, 12, 18));
    });
  });
}

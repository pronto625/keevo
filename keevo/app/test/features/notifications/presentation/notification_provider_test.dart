import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/notifications/data/datasource/local_notification_datasource.dart';
import 'package:keevo/features/notifications/data/repository/announcement_sync_service.dart';
import 'package:keevo/features/notifications/domain/repository/notification_repository.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class MockNotificationRepository extends Mock
    implements NotificationRepository {}

class _MockLocal extends Mock implements LocalNotificationDataSource {}

void main() {
  late MockNotificationRepository mockRepo;

  setUp(() {
    mockRepo = MockNotificationRepository();
  });

  ProviderContainer makeContainer() => ProviderContainer(
        retry: noProviderRetry,
        overrides: [
          notificationRepositoryProvider.overrideWithValue(mockRepo),
        ],
      );

  group('markAllAsReadProvider — refresh reinvokes repository', () {
    test('first call invokes markAllAsRead once', () async {
      when(() => mockRepo.markAllAsRead()).thenAnswer((_) async {});

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(markAllAsReadProvider.future);

      verify(() => mockRepo.markAllAsRead()).called(1);
    });

    test('refresh re-executes and invokes markAllAsRead a second time (AC3)',
        () async {
      when(() => mockRepo.markAllAsRead()).thenAnswer((_) async {});

      final container = makeContainer();
      addTearDown(container.dispose);

      // First call — markAllAsRead executed once
      await container.read(markAllAsReadProvider.future);

      // Second call via refresh — simulates user tapping "Tout marquer comme lu"
      // after new unread notifications have arrived
      await container.refresh(markAllAsReadProvider.future);

      // markAllAsRead() must have been called twice, not just once
      verify(() => mockRepo.markAllAsRead()).called(2);
    });

    test('read does NOT re-execute (bug confirmation — baseline for AC3)',
        () async {
      when(() => mockRepo.markAllAsRead()).thenAnswer((_) async {});

      final container = makeContainer();
      addTearDown(container.dispose);

      await container.read(markAllAsReadProvider.future);
      // Second read (not refresh) — should NOT re-execute
      await container.read(markAllAsReadProvider.future);

      // Called only once because FutureProvider caches the result
      verify(() => mockRepo.markAllAsRead()).called(1);
    });
  });

  test('announcementSyncServiceProvider wires dio, local datasource and prefs '
      '(Story 9-5)', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      dioProvider.overrideWithValue(Dio()),
      localNotificationDataSourceProvider.overrideWithValue(_MockLocal()),
    ]);
    addTearDown(container.dispose);

    expect(container.read(announcementSyncServiceProvider),
        isA<AnnouncementSyncService>());
  });
}

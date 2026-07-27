import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/notifications/domain/repository/notification_repository.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:mocktail/mocktail.dart';

class MockNotificationRepository extends Mock
    implements NotificationRepository {}

void main() {
  late MockNotificationRepository mockRepo;

  setUp(() {
    mockRepo = MockNotificationRepository();
  });

  ProviderContainer makeContainer() => ProviderContainer(
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
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/notifications/domain/model/notification_model.dart';
import 'package:keevo/features/notifications/domain/repository/notification_repository.dart';
import 'package:keevo/features/notifications/presentation/page/notifications_page.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:mocktail/mocktail.dart';

class MockNotificationRepository extends Mock
    implements NotificationRepository {}

void main() {
  late MockNotificationRepository mockRepo;

  setUpAll(() {
    registerFallbackValue(DateTime(2020));
  });

  setUp(() {
    mockRepo = MockNotificationRepository();
    when(() => mockRepo.markAllAsRead()).thenAnswer((_) async {});
    when(() => mockRepo.deleteOlderThan(any())).thenAnswer((_) async {});
  });

  Widget buildPage({List<NotificationModel> items = const []}) {
    return ProviderScope(
      overrides: [
        notificationRepositoryProvider.overrideWithValue(mockRepo),
        unreadNotificationCountProvider.overrideWith((ref) => Stream.value(2)),
        notificationsListProvider.overrideWith((ref) => Stream.value(items)),
      ],
      child: MaterialApp.router(
        routerConfig: GoRouter(
          routes: [
            GoRoute(
              path: '/',
              builder: (_, __) => const NotificationsPage(),
            ),
          ],
        ),
      ),
    );
  }

  testWidgets(
    'tapping "Tout marquer comme lu" twice invokes markAllAsRead twice',
    (tester) async {
      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      final button = find.text('Tout marquer comme lu');
      expect(button, findsOneWidget);

      await tester.tap(button);
      await tester.pumpAndSettle();

      await tester.tap(button);
      await tester.pumpAndSettle();

      // Two taps must invoke markAllAsRead twice — regression guard for the
      // ref.read → ref.refresh fix (v1s-16-7): ref.read would cache the
      // provider body after the first tap and silently no-op on the second.
      verify(() => mockRepo.markAllAsRead()).called(2);
    },
  );

  testWidgets(
    'a Keevo announcement is listed as « Annonce Keevo » with its title, '
    'body and a megaphone icon (Story 9-5)',
    (tester) async {
      await tester.pumpWidget(buildPage(items: [
        NotificationModel(
          id: 'g-1',
          type: 'GLOBAL_ANNOUNCEMENT',
          title: 'Maintenance ce soir',
          body: 'Keevo sera indisponible de 22h à 23h.',
          receivedAt: DateTime.now(),
        ),
        NotificationModel(
          id: 's-1',
          type: 'STOCK_ALERT',
          title: 'Stock bas',
          body: 'Savon : 2',
          receivedAt: DateTime.now(),
        ),
      ]));
      await tester.pumpAndSettle();

      expect(find.text('Annonce Keevo'), findsOneWidget);
      expect(find.text('Maintenance ce soir'), findsOneWidget);
      expect(find.text('Keevo sera indisponible de 22h à 23h.'), findsOneWidget);
      expect(find.byIcon(Icons.campaign_rounded), findsOneWidget);
      // other types keep their own visual, without the label
      expect(find.text('Stock bas'), findsOneWidget);
      expect(find.byIcon(Icons.warning_amber_rounded), findsOneWidget);
    },
  );
}

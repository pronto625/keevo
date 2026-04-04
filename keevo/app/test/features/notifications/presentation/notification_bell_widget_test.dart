import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo/features/notifications/presentation/widget/notification_bell_widget.dart';

void main() {
  group('NotificationBellWidget', () {
    testWidgets('shows badge when unread > 0', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            unreadNotificationCountProvider
                .overrideWith((ref) => Stream.value(3)),
          ],
          child: const MaterialApp(
            home: Scaffold(body: NotificationBellWidget()),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('3'), findsOneWidget);
      expect(find.byIcon(Icons.notifications_outlined), findsOneWidget);
    });

    testWidgets('hides badge when unread is 0', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            unreadNotificationCountProvider
                .overrideWith((ref) => Stream.value(0)),
          ],
          child: const MaterialApp(
            home: Scaffold(body: NotificationBellWidget()),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Badge label should not be visible (Badge isLabelVisible = false)
      expect(find.text('0'), findsNothing);
      expect(find.byIcon(Icons.notifications_outlined), findsOneWidget);
    });
  });
}

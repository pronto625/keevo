import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/notifications/domain/model/notification_model.dart';

void main() {
  group('NotificationModel', () {
    test('create sets all fields', () {
      final now = DateTime.now();
      final model = NotificationModel(
        id: 'n-1',
        type: 'STOCK_THRESHOLD_BREACHED',
        title: 'Low Stock',
        body: 'Product X is below threshold',
        deepLink: '/stock/overview',
        isRead: false,
        receivedAt: now,
      );

      expect(model.id, 'n-1');
      expect(model.type, 'STOCK_THRESHOLD_BREACHED');
      expect(model.title, 'Low Stock');
      expect(model.body, 'Product X is below threshold');
      expect(model.deepLink, '/stock/overview');
      expect(model.isRead, false);
      expect(model.receivedAt, now);
    });

    test('markAsRead sets isRead to true', () {
      final model = NotificationModel(
        id: 'n-2',
        type: 'GENERAL',
        title: 'Test',
        body: 'Body',
        receivedAt: DateTime.now(),
      );

      final read = model.markAsRead();
      expect(read.isRead, true);
      expect(read.id, 'n-2');
    });

    test('typeIcon returns correct emoji', () {
      expect(
        NotificationModel(
          id: '1', type: 'DRAFT_PRODUCT_PENDING_VALIDATION',
          title: '', body: '', receivedAt: DateTime.now(),
        ).typeIcon,
        '🔶',
      );
      expect(
        NotificationModel(
          id: '2', type: 'STOCK_THRESHOLD_BREACHED',
          title: '', body: '', receivedAt: DateTime.now(),
        ).typeIcon,
        '⚠️',
      );
      expect(
        NotificationModel(
          id: '3', type: 'DAILY_REPORT',
          title: '', body: '', receivedAt: DateTime.now(),
        ).typeIcon,
        '📊',
      );
      expect(
        NotificationModel(
          id: '4', type: 'WEEKLY_REPORT',
          title: '', body: '', receivedAt: DateTime.now(),
        ).typeIcon,
        '📊',
      );
      expect(
        NotificationModel(
          id: '5', type: 'UNKNOWN',
          title: '', body: '', receivedAt: DateTime.now(),
        ).typeIcon,
        '🔔',
      );
    });
  });
}

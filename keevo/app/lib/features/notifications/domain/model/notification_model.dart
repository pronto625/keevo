import 'package:freezed_annotation/freezed_annotation.dart';

part 'notification_model.freezed.dart';
part 'notification_model.g.dart';

@freezed
class NotificationModel with _$NotificationModel {
  const NotificationModel._();

  const factory NotificationModel({
    required String id,
    required String type,
    required String title,
    required String body,
    String? deepLink,
    @Default(false) bool isRead,
    required DateTime receivedAt,
  }) = _NotificationModel;

  factory NotificationModel.fromJson(Map<String, dynamic> json) =>
      _$NotificationModelFromJson(json);

  /// Returns an emoji icon based on notification type.
  String get typeIcon => switch (type) {
        'DRAFT_PRODUCT_PENDING_VALIDATION' => '🔶',
        'STOCK_THRESHOLD_BREACHED' || 'STOCK_ALERT' || 'STOCK_ALERT_BATCH' => '⚠️',
        'TREND_DOWN' => '📉',
        'TREND_UP' => '📈',
        'DAILY_REPORT' || 'WEEKLY_REPORT' => '📊',
        globalAnnouncementType => '📣',
        _ => '🔔',
      };

  /// Push type of the announcements broadcast by the Keevo team (Story 9-5).
  static const globalAnnouncementType = 'GLOBAL_ANNOUNCEMENT';

  /// Label shown above the title in the notifications list, or `null`.
  String? get typeLabel =>
      type == globalAnnouncementType ? 'Annonce Keevo' : null;

  NotificationModel markAsRead() => copyWith(isRead: true);
}

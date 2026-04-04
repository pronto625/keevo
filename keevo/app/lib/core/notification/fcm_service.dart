import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:go_router/go_router.dart';
import 'package:uuid/uuid.dart';

import '../../features/notifications/data/datasource/local_notification_datasource.dart';
import '../../features/notifications/data/datasource/remote_device_token_datasource.dart';

const _kChannelId = 'keevo_notifications';
const _kChannelName = 'Keevo Notifications';

class FcmService {
  final LocalNotificationDataSource _localNotifications;
  final RemoteDeviceTokenDataSource? _remoteTokenDataSource;
  final FlutterLocalNotificationsPlugin _flutterLocalNotifications;

  FcmService({
    required LocalNotificationDataSource localNotifications,
    RemoteDeviceTokenDataSource? remoteTokenDataSource,
    FlutterLocalNotificationsPlugin? flutterLocalNotifications,
  })  : _localNotifications = localNotifications,
        _remoteTokenDataSource = remoteTokenDataSource,
        _flutterLocalNotifications =
            flutterLocalNotifications ?? FlutterLocalNotificationsPlugin();

  bool get _isDesktop => Platform.isLinux || Platform.isWindows;

  Future<void> initialize(GoRouter router) async {
    if (_isDesktop) {
      debugPrint('[FCM] Desktop platform — skipping FCM initialization');
      return;
    }

    // Guard: Firebase.initializeApp() throws if called twice.
    if (Firebase.apps.isNotEmpty) {
      debugPrint('[FCM] Firebase already initialized — re-using existing app');
      // Still need to set up listeners even if Firebase was already inited
    } else {
      debugPrint('[FCM] Calling Firebase.initializeApp()...');
      await Firebase.initializeApp();
      debugPrint('[FCM] Firebase.initializeApp() done ✅');
    }

    await _initLocalNotifications(router);

    final settings =
        await FirebaseMessaging.instance.requestPermission();
    debugPrint('[FCM] permission: ${settings.authorizationStatus}');

    // Foreground message handler
    FirebaseMessaging.onMessage.listen((message) {
      _onForegroundMessage(message);
    });

    // Background → foreground tap handler
    FirebaseMessaging.onMessageOpenedApp.listen((message) {
      _onTap(message, router);
    });

    // Cold start tap
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) _onTap(initial, router);

    // Token refresh — re-register with backend (AC2)
    FirebaseMessaging.instance.onTokenRefresh.listen((token) {
      debugPrint('[FCM] Token refreshed — re-registering with backend');
      _onTokenRefresh(token);
    });
  }

  Future<void> _onTokenRefresh(String token) async {
    try {
      await _remoteTokenDataSource?.registerToken(
        token: token,
        platform: _getPlatform(),
      );
    } catch (e) {
      debugPrint('[FCM] Failed to re-register refreshed FCM token: $e');
    }
  }

  String _getPlatform() {
    if (Platform.isAndroid) return 'ANDROID';
    if (Platform.isIOS) return 'IOS';
    if (Platform.isWindows) return 'WINDOWS';
    return 'LINUX';
  }

  Future<String?> getToken() async {
    if (_isDesktop) return null;
    try {
      return await FirebaseMessaging.instance.getToken();
    } catch (e) {
      debugPrint('[FCM] Failed to get FCM token: $e');
      return null;
    }
  }

  Future<void> deleteToken() async {
    if (_isDesktop) return;
    try {
      await FirebaseMessaging.instance.deleteToken();
    } catch (e) {
      debugPrint('[FCM] Failed to delete FCM token: $e');
    }
  }

  Future<void> _initLocalNotifications(GoRouter router) async {
    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    const initSettings = InitializationSettings(android: androidSettings);

    await _flutterLocalNotifications.initialize(
      initSettings,
      onDidReceiveNotificationResponse: (response) {
        final deepLink = response.payload;
        if (deepLink != null && deepLink.isNotEmpty) {
          router.go(deepLink);
        }
      },
    );

    // Create notification channel
    const channel = AndroidNotificationChannel(
      _kChannelId,
      _kChannelName,
      importance: Importance.high,
    );

    await _flutterLocalNotifications
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel);
  }

  void _onForegroundMessage(RemoteMessage message) {
    debugPrint('[FCM] Foreground message: ${message.messageId}');

    // Store locally
    _storeNotification(message);

    // Show local notification
    final notification = message.notification;
    if (notification != null) {
      _flutterLocalNotifications.show(
        notification.hashCode,
        notification.title ?? 'Keevo',
        notification.body ?? '',
        const NotificationDetails(
          android: AndroidNotificationDetails(
            _kChannelId,
            _kChannelName,
            importance: Importance.high,
            priority: Priority.high,
          ),
        ),
        payload: message.data['deepLink'] as String?,
      );
    }
  }

  void _onTap(RemoteMessage message, GoRouter router) {
    final deepLink = message.data['deepLink'] as String?;
    if (deepLink != null && deepLink.isNotEmpty) {
      router.go(deepLink);
    }

    // Mark as read if we have an ID
    final notifId = message.data['id'] as String?;
    if (notifId != null) {
      _localNotifications.markAsRead(notifId);
    }
  }

  void _storeNotification(RemoteMessage message) {
    final data = message.data;
    final notification = message.notification;
    _localNotifications.insert(
      id: data['id'] as String? ?? const Uuid().v4(),
      type: data['type'] as String? ?? 'GENERAL',
      title: notification?.title ?? data['title'] as String? ?? 'Notification',
      body: notification?.body ?? data['body'] as String? ?? '',
      deepLink: data['deepLink'] as String?,
      receivedAt: DateTime.now(),
    );
  }
}

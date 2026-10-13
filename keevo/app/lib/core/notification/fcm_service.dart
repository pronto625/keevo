import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:go_router/go_router.dart';
import 'package:uuid/uuid.dart';

import '../../features/notifications/data/datasource/local_notification_datasource.dart';
import '../../features/notifications/data/datasource/remote_device_token_datasource.dart';
import '../../features/notifications/domain/model/notification_model.dart';

const _kChannelId = 'keevo_notifications';
const _kChannelName = 'Keevo Notifications';

/// Announcement broadcast by the Keevo team to every merchant (Story 9-5,
/// FR82). It has no deep link of its own: a tap opens the notifications list.
const kGlobalAnnouncementType = NotificationModel.globalAnnouncementType;
const kNotificationsRoute = '/notifications';

/// Thin seam over the static Firebase / FirebaseMessaging APIs so
/// [FcmService] can be unit-tested (v1s-16-2).
abstract class FcmMessagingGateway {
  /// Initialises the default Firebase app unless it already exists.
  Future<void> ensureInitialized();

  /// FCM permission request (Android 13 runtime permission, FCM status).
  Future<void> requestPermission();

  Future<void> setForegroundNotificationPresentationOptions({
    required bool alert,
    required bool badge,
    required bool sound,
  });

  Stream<RemoteMessage> get onMessage;
  Stream<RemoteMessage> get onMessageOpenedApp;
  Future<RemoteMessage?> getInitialMessage();
  Stream<String> get onTokenRefresh;
  Future<String?> getAPNSToken();
  Future<String?> getToken();
  Future<void> deleteToken();
}

/// Default gateway: the real Firebase plugins.
class FirebaseFcmMessagingGateway implements FcmMessagingGateway {
  const FirebaseFcmMessagingGateway();

  // Lazy: FirebaseMessaging.instance needs an initialised Firebase app.
  FirebaseMessaging get _messaging => FirebaseMessaging.instance;

  @override
  Future<void> ensureInitialized() async {
    // Guard: Firebase.initializeApp() throws if called twice.
    if (Firebase.apps.isNotEmpty) {
      debugPrint('[FCM] Firebase already initialized — re-using existing app');
      return;
    }
    debugPrint('[FCM] Calling Firebase.initializeApp()...');
    await Firebase.initializeApp();
    debugPrint('[FCM] Firebase.initializeApp() done');
  }

  @override
  Future<void> requestPermission() async {
    final settings = await _messaging.requestPermission();
    debugPrint('[FCM] permission: ${settings.authorizationStatus}');
  }

  @override
  Future<void> setForegroundNotificationPresentationOptions({
    required bool alert,
    required bool badge,
    required bool sound,
  }) =>
      _messaging.setForegroundNotificationPresentationOptions(
        alert: alert,
        badge: badge,
        sound: sound,
      );

  @override
  Stream<RemoteMessage> get onMessage => FirebaseMessaging.onMessage;

  @override
  Stream<RemoteMessage> get onMessageOpenedApp =>
      FirebaseMessaging.onMessageOpenedApp;

  @override
  Future<RemoteMessage?> getInitialMessage() => _messaging.getInitialMessage();

  @override
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Future<String?> getAPNSToken() => _messaging.getAPNSToken();

  @override
  Future<String?> getToken() => _messaging.getToken();

  @override
  Future<void> deleteToken() => _messaging.deleteToken();
}

class FcmService {
  final LocalNotificationDataSource _localNotifications;
  final RemoteDeviceTokenDataSource? _remoteTokenDataSource;
  final FlutterLocalNotificationsPlugin _flutterLocalNotifications;
  final FcmMessagingGateway _messaging;

  /// Optional callback invoked on every foreground FCM message (after local
  /// storage and OS notification).  Callers with a Riverpod [Ref] can use this
  /// to invalidate stale providers without coupling [FcmService] to Riverpod.
  void Function(RemoteMessage message)? _onMessage;

  /// MainShell calls [initialize] each time it is built (every sign-in).
  /// Firebase streams are app-wide: attaching listeners again would open a
  /// tapped notification's page once per sign-in and re-register the token
  /// as many times. Only [_onMessage] is refreshed on later calls.
  bool _listenersAttached = false;

  /// Last token the backend accepted in this process (v1s-16-2). It can
  /// differ from [getToken] when a refreshed token failed to re-register;
  /// sign-out revokes both.
  String? _lastRegisteredToken;

  /// Set by [deleteToken] (sign-out): a token FCM generates afterwards must
  /// not be registered until the next sign-in calls [registerCurrentToken].
  bool _signedOut = false;

  /// On a fresh iOS install the APNs token usually arrives after
  /// [initialize]: [registerCurrentToken] polls for it this many times.
  static const apnsMaxAttempts = 5;
  final Duration _apnsRetryDelay;

  FcmService({
    required LocalNotificationDataSource localNotifications,
    RemoteDeviceTokenDataSource? remoteTokenDataSource,
    FlutterLocalNotificationsPlugin? flutterLocalNotifications,
    FcmMessagingGateway? messaging,
    Duration apnsRetryDelay = const Duration(seconds: 2),
  })  : _localNotifications = localNotifications,
        _apnsRetryDelay = apnsRetryDelay,
        _remoteTokenDataSource = remoteTokenDataSource,
        _flutterLocalNotifications =
            flutterLocalNotifications ?? FlutterLocalNotificationsPlugin(),
        _messaging = messaging ?? const FirebaseFcmMessagingGateway();

  String? get lastRegisteredToken => _lastRegisteredToken;

  /// FCM is unavailable on web and on Linux/Windows desktop.
  bool get _isUnsupported =>
      kIsWeb ||
      defaultTargetPlatform == TargetPlatform.linux ||
      defaultTargetPlatform == TargetPlatform.windows;

  bool get _isIos => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  /// Device platform value sent to the backend.
  String get platformName {
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return 'ANDROID';
      case TargetPlatform.iOS:
        return 'IOS';
      case TargetPlatform.windows:
        return 'WINDOWS';
      default:
        return 'LINUX';
    }
  }

  Future<void> initialize(
    GoRouter router, {
    void Function(RemoteMessage message)? onMessage,
  }) async {
    _onMessage = onMessage;
    if (_isUnsupported) {
      debugPrint('[FCM] Unsupported platform — skipping FCM initialization');
      return;
    }

    await _messaging.ensureInitialized();

    // Set before the awaits so a concurrent call does not attach the
    // listeners twice; reset if setup fails before they are attached, so the
    // next call retries instead of leaving notifications off for good.
    if (_listenersAttached) return;
    _listenersAttached = true;

    try {
      await _initLocalNotifications(router);

      if (_isIos) {
        // The local plugin shows foreground pushes (single tap path): FCM's
        // native foreground presentation would show each push a second time.
        await _messaging.setForegroundNotificationPresentationOptions(
          alert: false,
          badge: false,
          sound: false,
        );
      }

      await _messaging.requestPermission();
    } catch (_) {
      _listenersAttached = false;
      rethrow;
    }

    // Foreground message handler
    _messaging.onMessage.listen(_onForegroundMessage);

    // Background → foreground tap handler
    _messaging.onMessageOpenedApp.listen((message) {
      _onTap(message, router);
    });

    // Token refresh — re-register with backend (AC2). Attached before the
    // cold-start read so a failure there cannot leave it off.
    _messaging.onTokenRefresh.listen((token) {
      if (_signedOut) {
        debugPrint('[FCM] Token refreshed while signed out — not registered');
        return;
      }
      debugPrint('[FCM] Token refreshed — re-registering with backend');
      _registerWithBackend(token);
    });

    // Cold start tap
    try {
      final initial = await _messaging.getInitialMessage();
      if (initial != null) _onTap(initial, router);
    } catch (e) {
      debugPrint('[FCM] getInitialMessage() failed: $e');
    }
  }

  /// Registers the current FCM token with the backend. Best-effort: never
  /// throws. On iOS, [FirebaseMessaging.getToken] fails until APNs has
  /// delivered its own token, so it is polled [apnsMaxAttempts] times; if it
  /// never comes, registration is skipped and only
  /// [FirebaseMessaging.onTokenRefresh] (or the next sign-in) registers.
  Future<void> registerCurrentToken() async {
    if (_isUnsupported || _remoteTokenDataSource == null) return;
    _signedOut = false;
    try {
      if (_isIos && !await _waitForApnsToken()) {
        debugPrint('[FCM] APNs token not available — skipping registration');
        return;
      }
      final token = await _messaging.getToken();
      if (token == null) {
        debugPrint('[FCM] getToken() returned null — skipping registration');
        return;
      }
      await _registerWithBackend(token);
    } catch (e) {
      debugPrint('[FCM] Token registration failed: $e');
    }
  }

  Future<bool> _waitForApnsToken() async {
    for (var attempt = 1; attempt <= apnsMaxAttempts; attempt++) {
      if (await _messaging.getAPNSToken() != null) return true;
      if (attempt < apnsMaxAttempts) await Future<void>.delayed(_apnsRetryDelay);
    }
    return false;
  }

  Future<void> _registerWithBackend(String token) async {
    final remote = _remoteTokenDataSource;
    if (remote == null) return;
    try {
      await remote.registerToken(token: token, platform: platformName);
      _lastRegisteredToken = token;
      debugPrint('[FCM] Token registered with backend');
    } catch (e) {
      debugPrint('[FCM] Failed to register FCM token with backend: $e');
    }
  }

  Future<String?> getToken() async {
    if (_isUnsupported) return null;
    try {
      return await _messaging.getToken();
    } catch (e) {
      debugPrint('[FCM] Failed to get FCM token: $e');
      return null;
    }
  }

  /// Sign-out only (called after the backend deletions): forgets the
  /// registered token and stops refreshed tokens from being registered.
  Future<void> deleteToken() async {
    _lastRegisteredToken = null;
    _signedOut = true;
    if (_isUnsupported) return;
    try {
      await _messaging.deleteToken();
    } catch (e) {
      debugPrint('[FCM] Failed to delete FCM token: $e');
    }
  }

  Future<void> _initLocalNotifications(GoRouter router) async {
    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    // No prompt from initialize(): permissions are requested explicitly below.
    const darwinSettings = DarwinInitializationSettings(
      requestAlertPermission: false,
      requestBadgePermission: false,
      requestSoundPermission: false,
    );
    const initSettings = InitializationSettings(
      android: androidSettings,
      iOS: darwinSettings,
    );

    await _flutterLocalNotifications.initialize(
      initSettings,
      onDidReceiveNotificationResponse: (response) {
        final deepLink = response.payload;
        if (deepLink != null && deepLink.isNotEmpty) {
          // Use push() so OS back button returns to the previous screen.
          router.push(deepLink);
        }
      },
    );

    // A local notification tapped after the app was killed only reaches
    // the app through the launch details.
    try {
      final launch =
          await _flutterLocalNotifications.getNotificationAppLaunchDetails();
      final payload = launch?.notificationResponse?.payload;
      if ((launch?.didNotificationLaunchApp ?? false) &&
          payload != null &&
          payload.isNotEmpty) {
        router.push(payload);
      }
    } catch (e) {
      debugPrint('[FCM] getNotificationAppLaunchDetails() failed: $e');
    }

    if (_isIos) {
      await _flutterLocalNotifications
          .resolvePlatformSpecificImplementation<
              IOSFlutterLocalNotificationsPlugin>()
          ?.requestPermissions(alert: true, badge: true, sound: true);
      return;
    }

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
    unawaited(_storeNotification(message));

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
          iOS: DarwinNotificationDetails(
            presentAlert: true,
            presentBanner: true,
            presentList: true,
            presentSound: true,
          ),
        ),
        payload: _tapRoute(message),
      );
    }

    // Notify caller so Riverpod providers can be invalidated as needed.
    _onMessage?.call(message);
  }

  /// Route opened when the notification is tapped: its deep link, or the
  /// notifications list for a Keevo announcement.
  static String? _tapRoute(RemoteMessage message) {
    final deepLink = message.data['deepLink'] as String?;
    if (deepLink != null && deepLink.isNotEmpty) return deepLink;
    if (message.data['type'] == kGlobalAnnouncementType) {
      return kNotificationsRoute;
    }
    return null;
  }

  void _onTap(RemoteMessage message, GoRouter router) {
    final route = _tapRoute(message);
    if (route != null) {
      // Use push() so OS back button returns to the previous screen.
      router.push(route);
    }

    final notifId = message.data['id'] as String?;
    if (message.data['type'] == kGlobalAnnouncementType) {
      // Received while the app was in background / killed: not stored yet.
      unawaited(_storeThenMarkRead(message, notifId));
      return;
    }

    // Mark as read if we have an ID
    if (notifId != null) {
      _localNotifications.markAsRead(notifId);
    }
  }

  Future<void> _storeThenMarkRead(RemoteMessage message, String? id) async {
    try {
      await _storeNotification(message);
      if (id != null) await _localNotifications.markAsRead(id);
    } catch (e) {
      debugPrint('[FCM] Failed to store tapped announcement: $e');
    }
  }

  Future<void> _storeNotification(RemoteMessage message) {
    final data = message.data;
    final notification = message.notification;
    return _localNotifications.insert(
      id: data['id'] as String? ?? const Uuid().v4(),
      type: data['type'] as String? ?? 'GENERAL',
      title: notification?.title ?? data['title'] as String? ?? 'Notification',
      body: notification?.body ?? data['body'] as String? ?? '',
      deepLink: data['deepLink'] as String?,
      // Send time, not tap time: a push tapped hours later keeps its place.
      receivedAt: message.sentTime ?? DateTime.now(),
    );
  }
}

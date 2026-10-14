import 'dart:async';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/features/notifications/data/datasource/local_notification_datasource.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:mocktail/mocktail.dart';

class _MockGateway extends Mock implements FcmMessagingGateway {}

class _MockPlugin extends Mock implements FlutterLocalNotificationsPlugin {}

class _MockIosPlugin extends Mock implements IOSFlutterLocalNotificationsPlugin {}

class _MockAndroidPlugin extends Mock
    implements AndroidFlutterLocalNotificationsPlugin {}

class _MockLocal extends Mock implements LocalNotificationDataSource {}

class _MockRemote extends Mock implements RemoteDeviceTokenDataSource {}

class _MockRouter extends Mock implements GoRouter {}

void main() {
  late _MockGateway gateway;
  late _MockPlugin plugin;
  late _MockIosPlugin iosPlugin;
  late _MockAndroidPlugin androidPlugin;
  late _MockLocal local;
  late _MockRemote remote;
  late _MockRouter router;
  late StreamController<RemoteMessage> onMessage;
  late StreamController<RemoteMessage> onOpened;
  late StreamController<String> onTokenRefresh;
  late FcmService service;

  setUpAll(() {
    registerFallbackValue(const InitializationSettings());
    registerFallbackValue(const NotificationDetails());
    registerFallbackValue(
        const AndroidNotificationChannel('fallback', 'fallback'));
    registerFallbackValue(DateTime(2026));
  });

  void usePlatform(TargetPlatform platform) {
    debugDefaultTargetPlatformOverride = platform;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
  }

  setUp(() {
    gateway = _MockGateway();
    plugin = _MockPlugin();
    iosPlugin = _MockIosPlugin();
    androidPlugin = _MockAndroidPlugin();
    local = _MockLocal();
    remote = _MockRemote();
    router = _MockRouter();
    onMessage = StreamController<RemoteMessage>.broadcast();
    onOpened = StreamController<RemoteMessage>.broadcast();
    onTokenRefresh = StreamController<String>.broadcast();
    addTearDown(() {
      onMessage.close();
      onOpened.close();
      onTokenRefresh.close();
    });

    when(() => gateway.ensureInitialized()).thenAnswer((_) async {});
    when(() => gateway.requestPermission()).thenAnswer((_) async {});
    when(() => gateway.setForegroundNotificationPresentationOptions(
          alert: any(named: 'alert'),
          badge: any(named: 'badge'),
          sound: any(named: 'sound'),
        )).thenAnswer((_) async {});
    when(() => gateway.onMessage).thenAnswer((_) => onMessage.stream);
    when(() => gateway.onMessageOpenedApp).thenAnswer((_) => onOpened.stream);
    when(() => gateway.onTokenRefresh).thenAnswer((_) => onTokenRefresh.stream);
    when(() => gateway.getInitialMessage()).thenAnswer((_) async => null);
    when(() => gateway.getAPNSToken()).thenAnswer((_) async => 'apns');
    when(() => gateway.getToken()).thenAnswer((_) async => 'token-A');
    when(() => gateway.deleteToken()).thenAnswer((_) async {});

    when(() => plugin.initialize(
          any(),
          onDidReceiveNotificationResponse:
              any(named: 'onDidReceiveNotificationResponse'),
        )).thenAnswer((_) async => true);
    when(() => plugin.resolvePlatformSpecificImplementation<
        IOSFlutterLocalNotificationsPlugin>()).thenReturn(iosPlugin);
    when(() => plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>()).thenReturn(androidPlugin);
    when(() => plugin.getNotificationAppLaunchDetails())
        .thenAnswer((_) async => null);
    when(() => router.push<Object?>(any())).thenAnswer((_) async => null);
    when(() => router.go(any())).thenReturn(null);
    when(() => plugin.show(any(), any(), any(), any(),
        payload: any(named: 'payload'))).thenAnswer((_) async {});
    when(() => iosPlugin.requestPermissions(
          alert: any(named: 'alert'),
          badge: any(named: 'badge'),
          sound: any(named: 'sound'),
        )).thenAnswer((_) async => true);
    when(() => androidPlugin.createNotificationChannel(any()))
        .thenAnswer((_) async {});

    when(() => local.insert(
          id: any(named: 'id'),
          type: any(named: 'type'),
          title: any(named: 'title'),
          body: any(named: 'body'),
          deepLink: any(named: 'deepLink'),
          receivedAt: any(named: 'receivedAt'),
        )).thenAnswer((_) async {});
    when(() => remote.registerToken(
          token: any(named: 'token'),
          platform: any(named: 'platform'),
        )).thenAnswer((_) async {});

    service = FcmService(
      localNotifications: local,
      remoteTokenDataSource: remote,
      flutterLocalNotifications: plugin,
      messaging: gateway,
      apnsRetryDelay: Duration.zero,
    );
  });

  const pushWithNotification = RemoteMessage(
    notification: RemoteNotification(title: 'Stock bas', body: 'Savon: 2'),
    data: {'deepLink': '/products/p1', 'type': 'STOCK_ALERT'},
  );

  group('initialize', () {
    test('shouldInitializeDarwinSettingsOnIos', () async {
      usePlatform(TargetPlatform.iOS);

      await service.initialize(router);

      final settings = verify(() => plugin.initialize(
            captureAny(),
            onDidReceiveNotificationResponse:
                any(named: 'onDidReceiveNotificationResponse'),
          )).captured.single as InitializationSettings;
      expect(settings.iOS, isA<DarwinInitializationSettings>());
      expect(settings.iOS!.requestAlertPermission, isFalse);
      expect(settings.iOS!.requestBadgePermission, isFalse);
      expect(settings.iOS!.requestSoundPermission, isFalse);
      expect(settings.android, isNotNull);
    });

    test('shouldRequestAlertBadgeSoundPermissions', () async {
      usePlatform(TargetPlatform.iOS);

      await service.initialize(router);

      verify(() => iosPlugin.requestPermissions(
          alert: true, badge: true, sound: true)).called(1);
      verify(() => gateway.requestPermission()).called(1);
    });

    test('disables FCM native foreground presentation on iOS', () async {
      usePlatform(TargetPlatform.iOS);

      await service.initialize(router);

      verify(() => gateway.setForegroundNotificationPresentationOptions(
          alert: false, badge: false, sound: false)).called(1);
    });

    test('Android: channel created, Darwin settings passed, no iOS permission',
        () async {
      usePlatform(TargetPlatform.android);

      await service.initialize(router);

      final settings = verify(() => plugin.initialize(
            captureAny(),
            onDidReceiveNotificationResponse:
                any(named: 'onDidReceiveNotificationResponse'),
          )).captured.single as InitializationSettings;
      expect(settings.iOS, isNotNull);
      final channel = verify(
              () => androidPlugin.createNotificationChannel(captureAny()))
          .captured
          .single as AndroidNotificationChannel;
      expect(channel.id, 'keevo_notifications');
      expect(channel.importance, Importance.high);
      verifyNever(() => iosPlugin.requestPermissions(
            alert: any(named: 'alert'),
            badge: any(named: 'badge'),
            sound: any(named: 'sound'),
          ));
      verifyNever(() => gateway.setForegroundNotificationPresentationOptions(
            alert: any(named: 'alert'),
            badge: any(named: 'badge'),
            sound: any(named: 'sound'),
          ));
    });

    test('iOS: local permissions are requested before the FCM permission',
        () async {
      usePlatform(TargetPlatform.iOS);

      await service.initialize(router);

      verifyInOrder([
        () => iosPlugin.requestPermissions(
            alert: true, badge: true, sound: true),
        () => gateway.requestPermission(),
      ]);
    });

    test('tapping a local notification pushes its deep link', () async {
      usePlatform(TargetPlatform.android);

      await service.initialize(router);

      final callback = verify(() => plugin.initialize(
            any(),
            onDidReceiveNotificationResponse:
                captureAny(named: 'onDidReceiveNotificationResponse'),
          )).captured.single as DidReceiveNotificationResponseCallback;
      callback(const NotificationResponse(
        notificationResponseType: NotificationResponseType.selectedNotification,
        payload: '/products/p1',
      ));
      // J16: legacy « /products/{id} » opens the product form route.
      verify(() => router.push<Object?>('/products/p1/edit')).called(1);
    });

    test('a local notification that launched the app opens its deep link',
        () async {
      usePlatform(TargetPlatform.iOS);
      when(() => plugin.getNotificationAppLaunchDetails()).thenAnswer(
        (_) async => const NotificationAppLaunchDetails(
          true,
          notificationResponse: NotificationResponse(
            notificationResponseType:
                NotificationResponseType.selectedNotification,
            payload: '/reports',
          ),
        ),
      );

      await service.initialize(router);

      // J16: a bottom-nav tab is opened with go(), never pushed.
      verify(() => router.go('/reports')).called(1);
      verifyNever(() => router.push<Object?>('/reports'));
    });

    test('getInitialMessage failing still attaches the token refresh listener',
        () async {
      usePlatform(TargetPlatform.android);
      when(() => gateway.getInitialMessage()).thenThrow(Exception('boom'));

      await service.initialize(router);
      await service.registerCurrentToken();
      onTokenRefresh.add('token-B');
      await pumpEventQueue();

      expect(service.lastRegisteredToken, 'token-B');
    });

    test('listeners are attached once across repeated calls', () async {
      usePlatform(TargetPlatform.android);

      await service.initialize(router);
      await service.initialize(router);

      verify(() => plugin.initialize(
            any(),
            onDidReceiveNotificationResponse:
                any(named: 'onDidReceiveNotificationResponse'),
          )).called(1);
      verify(() => gateway.onMessage).called(1);
      verify(() => gateway.onTokenRefresh).called(1);
    });

    test('a setup failure resets the guard and rethrows', () async {
      usePlatform(TargetPlatform.android);
      when(() => gateway.requestPermission()).thenThrow(Exception('boom'));

      await expectLater(service.initialize(router), throwsException);

      when(() => gateway.requestPermission()).thenAnswer((_) async {});
      await service.initialize(router);
      verify(() => gateway.onMessage).called(1);
    });
  });

  group('foreground message', () {
    for (final platform in [TargetPlatform.iOS, TargetPlatform.android]) {
      test('$platform: stored and shown once with Android + Darwin details',
          () async {
        usePlatform(platform);
        await service.initialize(router);

        onMessage.add(pushWithNotification);
        await pumpEventQueue();

        final captured = verify(() => plugin.show(
              any(),
              'Stock bas',
              'Savon: 2',
              captureAny(),
              payload: '/products/p1',
            )).captured;
        expect(captured, hasLength(1));
        final details = captured.single as NotificationDetails;
        expect(details.android!.channelId, 'keevo_notifications');
        expect(details.android!.importance, Importance.high);
        expect(details.android!.priority, Priority.high);
        final ios = details.iOS!;
        expect(ios.presentAlert, isTrue);
        expect(ios.presentBanner, isTrue);
        expect(ios.presentList, isTrue);
        expect(ios.presentSound, isTrue);
        verify(() => local.insert(
              id: any(named: 'id'),
              type: 'STOCK_ALERT',
              title: 'Stock bas',
              body: 'Savon: 2',
              deepLink: '/products/p1',
              receivedAt: any(named: 'receivedAt'),
            )).called(1);
      });
    }

    test('the onMessage callback receives the foreground message', () async {
      usePlatform(TargetPlatform.android);
      final received = <RemoteMessage>[];
      await service.initialize(router, onMessage: received.add);

      onMessage.add(pushWithNotification);
      await pumpEventQueue();

      expect(received, [pushWithNotification]);
    });

    test('data-only push is stored but not shown', () async {
      usePlatform(TargetPlatform.iOS);
      await service.initialize(router);

      onMessage.add(const RemoteMessage(
          data: {'title': 'Rapport', 'body': 'Prêt', 'type': 'EOD_REPORT'}));
      await pumpEventQueue();

      verifyNever(() => plugin.show(any(), any(), any(), any(),
          payload: any(named: 'payload')));
      verify(() => local.insert(
            id: any(named: 'id'),
            type: 'EOD_REPORT',
            title: 'Rapport',
            body: 'Prêt',
            deepLink: any(named: 'deepLink'),
            receivedAt: any(named: 'receivedAt'),
          )).called(1);
    });
  });

  group('Keevo announcement (GLOBAL_ANNOUNCEMENT, Story 9-5)', () {
    const announcement = RemoteMessage(
      notification: RemoteNotification(
          title: 'Maintenance ce soir', body: 'Keevo indisponible 22h-23h'),
      data: {
        'type': 'GLOBAL_ANNOUNCEMENT',
        'id': 'g-1',
        'title': 'Maintenance ce soir',
        'body': 'Keevo indisponible 22h-23h',
      },
    );

    setUp(() {
      when(() => local.markAsRead(any())).thenAnswer((_) async {});
    });

    test('foreground: stored and shown, its tap opens the notifications list',
        () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);

      onMessage.add(announcement);
      await pumpEventQueue();

      verify(() => local.insert(
            id: 'g-1',
            type: 'GLOBAL_ANNOUNCEMENT',
            title: 'Maintenance ce soir',
            body: 'Keevo indisponible 22h-23h',
            deepLink: null,
            receivedAt: any(named: 'receivedAt'),
          )).called(1);
      verify(() => plugin.show(any(), 'Maintenance ce soir',
          'Keevo indisponible 22h-23h', any(),
          payload: '/notifications')).called(1);
    });

    test('background tap: stored, marked read, notifications list opened',
        () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);

      onOpened.add(announcement);
      await pumpEventQueue();

      verify(() => router.push<Object?>('/notifications')).called(1);
      verifyInOrder([
        () => local.insert(
              id: 'g-1',
              type: 'GLOBAL_ANNOUNCEMENT',
              title: 'Maintenance ce soir',
              body: 'Keevo indisponible 22h-23h',
              deepLink: null,
              receivedAt: any(named: 'receivedAt'),
            ),
        () => local.markAsRead('g-1'),
      ]);
    });

    test('cold start tap: stored and notifications list opened', () async {
      usePlatform(TargetPlatform.iOS);
      when(() => gateway.getInitialMessage())
          .thenAnswer((_) async => announcement);

      await service.initialize(router);
      await pumpEventQueue();

      verify(() => router.push<Object?>('/notifications')).called(1);
      verify(() => local.insert(
            id: 'g-1',
            type: 'GLOBAL_ANNOUNCEMENT',
            title: any(named: 'title'),
            body: any(named: 'body'),
            deepLink: null,
            receivedAt: any(named: 'receivedAt'),
          )).called(1);
    });

    test('tap long after the push: receivedAt is the send time', () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);
      final sentTime = DateTime(2026, 10, 12, 18);

      onOpened.add(RemoteMessage(
        sentTime: sentTime,
        notification: announcement.notification,
        data: announcement.data,
      ));
      await pumpEventQueue();

      verify(() => local.insert(
            id: 'g-1',
            type: 'GLOBAL_ANNOUNCEMENT',
            title: any(named: 'title'),
            body: any(named: 'body'),
            deepLink: null,
            receivedAt: sentTime,
          )).called(1);
    });

    test('other types tapped in background are not stored (unchanged)',
        () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);

      onOpened.add(pushWithNotification);
      await pumpEventQueue();

      // J16: legacy « /products/{id} » opens the product form route.
      verify(() => router.push<Object?>('/products/p1/edit')).called(1);
      verifyNever(() => local.insert(
            id: any(named: 'id'),
            type: any(named: 'type'),
            title: any(named: 'title'),
            body: any(named: 'body'),
            deepLink: any(named: 'deepLink'),
            receivedAt: any(named: 'receivedAt'),
          ));
    });
  });

  group('desktop', () {
    for (final platform in [TargetPlatform.linux, TargetPlatform.windows]) {
      test('$platform: every FCM call is a no-op', () async {
        usePlatform(platform);

        await service.initialize(router);
        expect(await service.getToken(), isNull);
        await service.deleteToken();
        await service.registerCurrentToken();

        verifyZeroInteractions(gateway);
        verifyZeroInteractions(plugin);
        verifyZeroInteractions(remote);
      });
    }
  });

  group('registerCurrentToken', () {
    test('iOS: APNs token arriving late is waited for', () async {
      usePlatform(TargetPlatform.iOS);
      var calls = 0;
      when(() => gateway.getAPNSToken())
          .thenAnswer((_) async => ++calls < 3 ? null : 'apns');

      await service.registerCurrentToken();

      verify(() => gateway.getAPNSToken()).called(3);
      verify(() => remote.registerToken(token: 'token-A', platform: 'IOS'))
          .called(1);
    });

    test('iOS without APNs token: skipped after the max attempts, no throw',
        () async {
      usePlatform(TargetPlatform.iOS);
      when(() => gateway.getAPNSToken()).thenAnswer((_) async => null);

      await service.registerCurrentToken();

      verify(() => gateway.getAPNSToken()).called(FcmService.apnsMaxAttempts);
      verifyNever(() => gateway.getToken());
      verifyNever(() => remote.registerToken(
          token: any(named: 'token'), platform: any(named: 'platform')));
      expect(service.lastRegisteredToken, isNull);
    });

    test('success records the token with the platform value', () async {
      usePlatform(TargetPlatform.iOS);

      await service.registerCurrentToken();

      verify(() => remote.registerToken(token: 'token-A', platform: 'IOS'))
          .called(1);
      expect(service.lastRegisteredToken, 'token-A');
    });

    test('Android does not wait for APNs', () async {
      usePlatform(TargetPlatform.android);

      await service.registerCurrentToken();

      verifyNever(() => gateway.getAPNSToken());
      verify(() => remote.registerToken(token: 'token-A', platform: 'ANDROID'))
          .called(1);
    });

    test('backend failure keeps the previous registered token', () async {
      usePlatform(TargetPlatform.android);
      await service.registerCurrentToken();
      when(() => gateway.getToken()).thenAnswer((_) async => 'token-B');
      when(() => remote.registerToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          )).thenThrow(Exception('offline'));

      await service.registerCurrentToken();

      expect(service.lastRegisteredToken, 'token-A');
    });

    test('getToken throwing is swallowed', () async {
      usePlatform(TargetPlatform.android);
      when(() => gateway.getToken()).thenThrow(Exception('no GMS'));

      await service.registerCurrentToken();

      expect(service.lastRegisteredToken, isNull);
    });
  });

  group('sign-out (deleteToken)', () {
    test('forgets the registered token', () async {
      usePlatform(TargetPlatform.android);
      await service.registerCurrentToken();

      await service.deleteToken();

      expect(service.lastRegisteredToken, isNull);
      verify(() => gateway.deleteToken()).called(1);
    });

    test('a token refreshed after sign-out is not registered until sign-in',
        () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);
      await service.deleteToken();

      onTokenRefresh.add('token-B');
      await pumpEventQueue();
      verifyNever(() => remote.registerToken(
          token: 'token-B', platform: any(named: 'platform')));

      await service.registerCurrentToken();
      onTokenRefresh.add('token-C');
      await pumpEventQueue();
      verify(() => remote.registerToken(token: 'token-C', platform: 'ANDROID'))
          .called(1);
    });
  });

  group('token refresh', () {
    test('re-registers and updates lastRegisteredToken on success', () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);
      await service.registerCurrentToken();

      onTokenRefresh.add('token-B');
      await pumpEventQueue();

      verify(() => remote.registerToken(token: 'token-B', platform: 'ANDROID'))
          .called(1);
      expect(service.lastRegisteredToken, 'token-B');
    });

    test('failure is logged and keeps the old token', () async {
      usePlatform(TargetPlatform.android);
      await service.initialize(router);
      await service.registerCurrentToken();
      when(() => remote.registerToken(
            token: 'token-B',
            platform: any(named: 'platform'),
          )).thenThrow(Exception('offline'));

      onTokenRefresh.add('token-B');
      await pumpEventQueue();

      expect(service.lastRegisteredToken, 'token-A');
    });
  });
}

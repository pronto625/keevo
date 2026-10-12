import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/features/notifications/data/datasource/local_notification_datasource.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockGateway extends Mock implements FcmMessagingGateway {}

class _MockLocal extends Mock implements LocalNotificationDataSource {}

class _MockRemote extends Mock implements RemoteDeviceTokenDataSource {}

// v1s-16-2: MainShell registers through fcmServiceProvider only — the
// provider must hand FcmService the backend token datasource.
void main() {
  test('fcmServiceProvider registers the token through the remote datasource',
      () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
    final gateway = _MockGateway();
    final remote = _MockRemote();
    when(() => gateway.getToken()).thenAnswer((_) async => 'token-A');
    when(() => remote.registerToken(
          token: any(named: 'token'),
          platform: any(named: 'platform'),
        )).thenAnswer((_) async {});
    final container = ProviderContainer(overrides: [
      fcmMessagingGatewayProvider.overrideWithValue(gateway),
      localNotificationDataSourceProvider.overrideWithValue(_MockLocal()),
      remoteDeviceTokenDataSourceProvider.overrideWithValue(remote),
    ]);
    addTearDown(container.dispose);

    final service = container.read(fcmServiceProvider);
    await service.registerCurrentToken();

    verify(() => remote.registerToken(token: 'token-A', platform: 'ANDROID'))
        .called(1);
    expect(service.lastRegisteredToken, 'token-A');
  });
}

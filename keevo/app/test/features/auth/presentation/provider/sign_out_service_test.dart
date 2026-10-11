import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/sign_out_service.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';

class _MockSync extends Mock implements SyncService {}

class _MockAuthRepository extends Mock implements AuthRepository {}

class _MockFcm extends Mock implements FcmService {}

class _MockDeviceTokens extends Mock implements RemoteDeviceTokenDataSource {}

void main() {
  late _MockSync sync;
  late _MockAuthRepository auth;
  late _MockFcm fcm;
  late SharedPreferences prefs;
  late ProviderContainer container;
  const storage = FlutterSecureStorage();

  setUp(() async {
    SharedPreferences.setMockInitialValues({
      kUserRoleKey: 'EMPLOYEE',
      kLocalDataOwnerKey: 'u1@kv_a',
    });
    FlutterSecureStorage.setMockInitialValues({
      'jwt_token': 'access',
      'refresh_token': 'refresh-1',
      kDbEncryptionKey: 'db-key',
    });
    prefs = await SharedPreferences.getInstance();
    sync = _MockSync();
    auth = _MockAuthRepository();
    fcm = _MockFcm();
    when(() => fcm.getToken()).thenAnswer((_) async => null);
    when(() => fcm.deleteToken()).thenAnswer((_) async {});
    when(() => auth.logout(any())).thenAnswer((_) async {});

    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      authRepositoryProvider.overrideWithValue(auth),
      fcmServiceProvider.overrideWithValue(fcm),
      remoteDeviceTokenDataSourceProvider.overrideWithValue(_MockDeviceTokens()),
    ]);
    addTearDown(container.dispose);
  });

  SignOutService service() => container.read(signOutServiceProvider);

  group('flushPendingOperations', () {
    test('pushes the queue first; nothing left → true', () async {
      when(() => sync.push()).thenAnswer((_) async => []);
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => false);
      when(() => sync.quarantinedOperationCount()).thenAnswer((_) async => 0);

      expect(await service().flushPendingOperations(), isTrue);
      verify(() => sync.push()).called(1);
    });

    // v1s-16-15: blocked operations are not pending, but signing out would
    // leave them behind — the user is warned.
    test('quarantined operations remain → false', () async {
      when(() => sync.push()).thenAnswer((_) async => []);
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => false);
      when(() => sync.quarantinedOperationCount()).thenAnswer((_) async => 1);

      expect(await service().flushPendingOperations(), isFalse);
    });

    test('offline: push fails, operations remain → false', () async {
      when(() => sync.push()).thenThrow(Exception('offline'));
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => true);

      expect(await service().flushPendingOperations(), isFalse);
    });
  });

  group('signOut', () {
    test('revokes the refresh token on the server, then clears the session', () async {
      container.read(cartProvider.notifier).addItem(CartItem(
            id: 'p1',
            productId: 'p1',
            productName: 'Savon',
            unitPrice: 500,
            appliedUnitPrice: 500,
            quantity: 1,
          ));

      await service().signOut();

      verify(() => auth.logout('refresh-1')).called(1);
      expect(await storage.read(key: 'refresh_token'), isNull);
      expect(await storage.read(key: 'jwt_token'), isNull);
      expect(await storage.read(key: kDbEncryptionKey), 'db-key');
      expect(prefs.getString(kUserRoleKey), isNull);
      expect(container.read(cartProvider), isEmpty);
      // Kept: tells the next sign-in whether it is the same account.
      expect(prefs.getString(kLocalDataOwnerKey), 'u1@kv_a');
    });

    test('offline: the server revocation fails but the device is signed out', () async {
      when(() => auth.logout(any())).thenThrow(Exception('offline'));

      await service().signOut();

      expect(await storage.read(key: 'refresh_token'), isNull);
    });
  });
}

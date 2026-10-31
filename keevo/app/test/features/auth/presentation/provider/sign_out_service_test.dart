import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/extension/extension_slot.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/features/account/domain/model/deletion_pending_status.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/sign_out_service.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';

class _MockSync extends Mock implements SyncService {}

class _MockAuthRepository extends Mock implements AuthRepository {}

class _MockFcm extends Mock implements FcmService {}

class _MockDeviceTokens extends Mock implements RemoteDeviceTokenDataSource {}

class _MockTokenStorage extends Mock implements TokenStorage {}

void main() {
  late _MockSync sync;
  late _MockAuthRepository auth;
  late _MockFcm fcm;
  late _MockDeviceTokens deviceTokens;
  late SharedPreferences prefs;
  late ProviderContainer container;
  const storage = FlutterSecureStorage();

  setUp(() async {
    SharedPreferences.setMockInitialValues({
      kUserRoleKey: 'EMPLOYEE',
      kLocalDataOwnerKey: 'u1@kv_a',
      kEntitlementsSnapshotKey:
          '{"owner":"u1@kv_a","states":{"multi-store":"ENABLED"}}',
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
    deviceTokens = _MockDeviceTokens();
    when(() => deviceTokens.deleteToken(any())).thenAnswer((_) async {});
    when(() => fcm.getToken()).thenAnswer((_) async => null);
    when(() => fcm.deleteToken()).thenAnswer((_) async {});
    when(() => auth.logout(any())).thenAnswer((_) async {});

    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      syncServiceProvider.overrideWithValue(sync),
      authRepositoryProvider.overrideWithValue(auth),
      fcmServiceProvider.overrideWithValue(fcm),
      remoteDeviceTokenDataSourceProvider.overrideWithValue(deviceTokens),
    ]);
    addTearDown(container.dispose);
  });

  SignOutService service() => container.read(signOutServiceProvider);

  group('flushPendingOperations', () {
    test('pushes the queue first; nothing left → true', () async {
      when(() => sync.push()).thenAnswer((_) async => []);
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => false);
      when(() => sync.heldOperationCount()).thenAnswer((_) async => 0);
      when(() => sync.quarantinedOperationCount()).thenAnswer((_) async => 0);

      expect(await service().flushPendingOperations(), isTrue);
      verify(() => sync.push()).called(1);
    });

    // v1s-16-15: blocked operations are not pending, but signing out would
    // leave them behind — the user is warned.
    test('quarantined operations remain → false', () async {
      when(() => sync.push()).thenAnswer((_) async => []);
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => false);
      when(() => sync.heldOperationCount()).thenAnswer((_) async => 0);
      when(() => sync.quarantinedOperationCount()).thenAnswer((_) async => 1);

      expect(await service().flushPendingOperations(), isFalse);
    });

    test('offline: push fails, operations remain → false', () async {
      when(() => sync.push()).thenThrow(Exception('offline'));
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => true);

      expect(await service().flushPendingOperations(), isFalse);
    });

    // Story 13.8b: held operations (module off) are not pending but would be
    // left behind — the user is warned.
    test('held operations remain → false', () async {
      when(() => sync.push()).thenAnswer((_) async => []);
      when(() => sync.hasPendingOperations()).thenAnswer((_) async => false);
      when(() => sync.heldOperationCount()).thenAnswer((_) async => 1);
      when(() => sync.quarantinedOperationCount()).thenAnswer((_) async => 0);

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
      // 15.1: the owner-tagged module snapshot stays — the baseline of this
      // account if it signs back in (an account switch clears it).
      expect(prefs.getString(kEntitlementsSnapshotKey), isNotNull);
    });

    test('offline: the server revocation fails but the device is signed out', () async {
      when(() => auth.logout(any())).thenThrow(Exception('offline'));

      await service().signOut();

      expect(await storage.read(key: 'refresh_token'), isNull);
    });

    // v1s-16-13: offline, FCM can throw — the session still ends.
    test('an FCM failure still clears the session', () async {
      when(() => fcm.getToken()).thenThrow(Exception('offline'));
      when(() => fcm.deleteToken()).thenThrow(Exception('offline'));

      await service().signOut();

      expect(await storage.read(key: 'jwt_token'), isNull);
      expect(await storage.read(key: 'refresh_token'), isNull);
      expect(prefs.getString(kUserRoleKey), isNull);
    });

    // v1s-16-2: a refresh whose re-registration failed leaves the backend
    // with the older token — both are revoked.
    test('stale registered token is deleted on the backend too', () async {
      when(() => fcm.getToken()).thenAnswer((_) async => 'token-B');
      when(() => fcm.lastRegisteredToken).thenReturn('token-A');

      await service().signOut();

      verify(() => deviceTokens.deleteToken('token-B')).called(1);
      verify(() => deviceTokens.deleteToken('token-A')).called(1);
      verify(() => fcm.deleteToken()).called(1);
    });

    test('same current and registered token is deleted once', () async {
      when(() => fcm.getToken()).thenAnswer((_) async => 'token-A');
      when(() => fcm.lastRegisteredToken).thenReturn('token-A');

      await service().signOut();

      verify(() => deviceTokens.deleteToken('token-A')).called(1);
      verify(() => fcm.deleteToken()).called(1);
    });

    test('no current token (iOS without APNs): the registered one is still deleted',
        () async {
      when(() => fcm.getToken()).thenAnswer((_) async => null);
      when(() => fcm.lastRegisteredToken).thenReturn('token-A');

      await service().signOut();

      verify(() => deviceTokens.deleteToken('token-A')).called(1);
      verify(() => fcm.deleteToken()).called(1);
    });

    test('remote delete of the current token failing still revokes the registered one',
        () async {
      when(() => fcm.getToken()).thenAnswer((_) async => 'token-B');
      when(() => fcm.lastRegisteredToken).thenReturn('token-A');
      when(() => deviceTokens.deleteToken('token-B'))
          .thenThrow(Exception('offline'));

      await service().signOut();

      verify(() => deviceTokens.deleteToken('token-A')).called(1);
      verify(() => fcm.deleteToken()).called(1);
    });

    // v1s-16-13: the next account must not see this tenant's plan.
    test('invalidates the subscription', () async {
      var fetches = 0;
      final c = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncServiceProvider.overrideWithValue(sync),
        authRepositoryProvider.overrideWithValue(auth),
        fcmServiceProvider.overrideWithValue(fcm),
        remoteDeviceTokenDataSourceProvider
            .overrideWithValue(_MockDeviceTokens()),
        subscriptionInfoProvider.overrideWith((ref) async {
          fetches++;
          return const SubscriptionInfo(
                planType: 'FREE',
                status: 'ACTIVE',
                currentStores: 1,
                currentProducts: 0,
                currentEmployees: 0);
        }),
      ]);
      addTearDown(c.dispose);
      await c.read(subscriptionInfoProvider.future);

      await c.read(signOutServiceProvider).signOut();
      await c.read(subscriptionInfoProvider.future);

      expect(fetches, 2);
    });

    // v1s-16-13 follow-up: an unreadable refresh token must not keep the
    // session alive (the forced password change would loop back).
    test('a failing refresh-token read still clears the session', () async {
      final tokens = _MockTokenStorage();
      when(() => tokens.getRefreshToken()).thenThrow(Exception('keystore'));
      final c = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncServiceProvider.overrideWithValue(sync),
        authRepositoryProvider.overrideWithValue(auth),
        fcmServiceProvider.overrideWithValue(fcm),
        remoteDeviceTokenDataSourceProvider.overrideWithValue(deviceTokens),
        tokenStorageProvider.overrideWithValue(tokens),
      ]);
      addTearDown(c.dispose);

      await c.read(signOutServiceProvider).signOut();

      expect(await storage.read(key: 'jwt_token'), isNull);
      expect(await storage.read(key: 'refresh_token'), isNull);
      verifyNever(() => auth.logout(any()));
    });

    // v1s-16-13 follow-up: nor this tenant's deletion banner.
    test('invalidates the account-deletion status', () async {
      var fetches = 0;
      final c = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncServiceProvider.overrideWithValue(sync),
        authRepositoryProvider.overrideWithValue(auth),
        fcmServiceProvider.overrideWithValue(fcm),
        remoteDeviceTokenDataSourceProvider
            .overrideWithValue(_MockDeviceTokens()),
        deletionPendingStatusProvider.overrideWith((ref) async {
          fetches++;
          return const DeletionPendingStatus();
        }),
      ]);
      addTearDown(c.dispose);
      await c.read(deletionPendingStatusProvider.future);

      await c.read(signOutServiceProvider).signOut();
      await c.read(deletionPendingStatusProvider.future);

      expect(fetches, 2);
    });

    // 15.3: the extension slot cache belongs to the session.
    test('drops the extension slot cache', () async {
      var fetches = 0;
      final c = ProviderContainer(overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        syncServiceProvider.overrideWithValue(sync),
        authRepositoryProvider.overrideWithValue(auth),
        fcmServiceProvider.overrideWithValue(fcm),
        remoteDeviceTokenDataSourceProvider
            .overrideWithValue(_MockDeviceTokens()),
        // Same readable set before and after: only the sign-out refetches.
        entitlementsProvider.overrideWith(() => FakeEntitlementsNotifier(
            () async => const {},
            seed: Entitlements(
                states: const {}, status: EntitlementsStatus.cached))),
        extensionContributionsFetcherProvider.overrideWithValue((_) async {
          fetches++;
          return const [];
        }),
      ]);
      addTearDown(c.dispose);
      final point = extensionContributionsProvider(kPosCheckoutPostSaleActions);
      final sub = c.listen(point, (_, __) {});
      addTearDown(sub.close);
      await c.read(point.future);

      await c.read(signOutServiceProvider).signOut();
      await c.read(point.future);

      expect(fetches, 2);
    });
  });
}

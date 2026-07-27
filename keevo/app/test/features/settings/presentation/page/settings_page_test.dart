import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo/features/settings/presentation/page/settings_page.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockFcmService extends Mock implements FcmService {}

class MockRemoteDeviceTokenDataSource extends Mock
    implements RemoteDeviceTokenDataSource {}

class MockTokenStorage extends Mock implements TokenStorage {}

// ── Helpers ───────────────────────────────────────────────────────────────

Widget _buildPage({
  required FcmService mockFcmService,
  required RemoteDeviceTokenDataSource mockTokenDataSource,
  required SharedPreferences prefs,
  String role = 'EMPLOYEE',
}) {
  return ProviderScope(
    overrides: [
      fcmServiceProvider.overrideWithValue(mockFcmService),
      remoteDeviceTokenDataSourceProvider
          .overrideWithValue(mockTokenDataSource),
      currentUserRoleProvider.overrideWithValue(role),
      currentUserPhoneProvider.overrideWithValue('+237600000000'),
      sharedPreferencesProvider.overrideWithValue(prefs),
      flutterSecureStorageProvider.overrideWithValue(
        const FlutterSecureStorage(),
      ),
    ],
    child: MaterialApp.router(
      routerConfig: GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, __) => const SettingsPage(),
          ),
          GoRoute(
            path: '/auth/login',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('Login Page')),
            ),
          ),
        ],
      ),
    ),
  );
}

/// Same routed shell as [_buildPage] but backed by an externally-owned
/// [ProviderContainer] so tests can `container.read(...)` provider state
/// before/after logout — needed to observe `ref.invalidate(...)` effects,
/// which `overrideWithValue` mocks cannot surface.
Widget _buildPageWithContainer(ProviderContainer container) {
  return UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, __) => const SettingsPage(),
          ),
          GoRoute(
            path: '/auth/login',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('Login Page')),
            ),
          ),
        ],
      ),
    ),
  );
}

/// Taps the "Se déconnecter" tile and confirms the dialog.
Future<void> _tapLogoutAndConfirm(WidgetTester tester) async {
  // Scroll to bottom to find logout tile
  await tester.scrollUntilVisible(
    find.text('Se déconnecter'),
    200,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.tap(find.text('Se déconnecter'));
  await tester.pumpAndSettle();

  // Confirm the dialog
  expect(find.text('Se déconnecter ?'), findsOneWidget);
  await tester.tap(find.text('Déconnecter'));
  // Let async logout complete
  await tester.pumpAndSettle();
}

void main() {
  late MockFcmService mockFcm;
  late MockRemoteDeviceTokenDataSource mockTokenDs;
  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();

    mockFcm = MockFcmService();
    mockTokenDs = MockRemoteDeviceTokenDataSource();

    // Safe defaults — individual tests override as needed
    when(() => mockFcm.getToken()).thenAnswer((_) async => null);
    when(() => mockFcm.deleteToken()).thenAnswer((_) async {});
    when(() => mockTokenDs.deleteToken(any())).thenAnswer((_) async {});
  });

  group('SettingsPage — Logout FCM cleanup (Story 16.1)', () {
    // ── AC1 + AC2: Normal logout with token ─────────────────────────────

    testWidgets('shouldDeleteFcmTokenOnLogout — deletes remote + local token',
        (tester) async {
      const token = 'fcm-token-abc123';
      when(() => mockFcm.getToken()).thenAnswer((_) async => token);

      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      // AC2: remote deleteToken(token) was called with exact token
      verify(() => mockTokenDs.deleteToken(token)).called(1);

      // AC3: local FCM deleteToken() was called
      verify(() => mockFcm.deleteToken()).called(1);

      // AC6: navigation still lands on /auth/login
      expect(find.text('Login Page'), findsOneWidget);
    });

    // ── AC4: Null token guard (desktop / no GMS) ────────────────────────

    testWidgets(
        'shouldGuardDesktopDeleteToken — null token skips remote call, '
        'still calls local deleteToken', (tester) async {
      // getToken returns null (desktop / no GMS)
      when(() => mockFcm.getToken()).thenAnswer((_) async => null);

      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      // AC4: remote deleteToken is NEVER called with any arg
      verifyNever(() => mockTokenDs.deleteToken(any()));

      // AC3: local deleteToken() is STILL called (unconditionally)
      verify(() => mockFcm.deleteToken()).called(1);

      // AC6: logout still completes
      expect(find.text('Login Page'), findsOneWidget);
    });

    // ── AC5: Remote delete failure is best-effort ───────────────────────

    testWidgets(
        'shouldSurviveRemoteDeleteTokenFailure — exception does not block logout',
        (tester) async {
      const token = 'fcm-token-xyz';
      when(() => mockFcm.getToken()).thenAnswer((_) async => token);
      when(() => mockTokenDs.deleteToken(any()))
          .thenThrow(Exception('Network error'));

      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      // AC5: logout did not crash — navigation still reached /auth/login
      expect(find.text('Login Page'), findsOneWidget);

      // Remote delete was attempted (raised exception, caught locally)
      verify(() => mockTokenDs.deleteToken(token)).called(1);

      // AC3: local deleteToken() is called unconditionally — the remote
      // exception is scoped to its own try/catch and must NOT prevent the
      // local unsubscribe from running.
      verify(() => mockFcm.deleteToken()).called(1);
    });

    // ── AC6: Existing logout behavior unchanged ─────────────────────────

    testWidgets(
        'shouldRetainExistingLogoutBehavior — secure storage cleared, '
        'prefs removed, provider invalidation, navigation intact',
        (tester) async {
      const token = 'fcm-token-final';
      when(() => mockFcm.getToken()).thenAnswer((_) async => token);

      // Pre-populate secure storage and prefs to verify they are cleared
      FlutterSecureStorage.setMockInitialValues({
        'jwt_token': 'some-jwt',
        'user_id': 'some-user-id',
      });
      SharedPreferences.setMockInitialValues({
        kUserRoleKey: 'EMPLOYEE',
        kUserPhoneKey: '+237600000000',
        kPasswordChangeRequiredKey: true,
        kOnboardingWizardSeenKey: true,
      });
      final prefsLocal = await SharedPreferences.getInstance();

      final mockTokenStorage = MockTokenStorage();
      when(() => mockTokenStorage.getToken()).thenAnswer((_) async => null);

      final container = ProviderContainer(
        overrides: [
          fcmServiceProvider.overrideWithValue(mockFcm),
          remoteDeviceTokenDataSourceProvider.overrideWithValue(mockTokenDs),
          sharedPreferencesProvider.overrideWithValue(prefsLocal),
          flutterSecureStorageProvider.overrideWithValue(
            const FlutterSecureStorage(),
          ),
          tokenStorageProvider.overrideWithValue(mockTokenStorage),
        ],
      );
      addTearDown(container.dispose);

      // Force-initialize the 3 providers _logout() must invalidate — mirrors
      // them already being read elsewhere in the app before logout happens.
      expect(container.read(currentUserRoleProvider), 'EMPLOYEE');
      expect(container.read(currentUserPhoneProvider), '+237600000000');
      await container.read(tenantStatusClaimProvider.future);
      verify(() => mockTokenStorage.getToken()).called(1);

      await tester.pumpWidget(_buildPageWithContainer(container));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      // AC6: navigation still lands on /auth/login
      expect(find.text('Login Page'), findsOneWidget);

      // AC6: secure storage was cleared (deleteAll() called)
      final secureStorage = const FlutterSecureStorage();
      expect(await secureStorage.read(key: 'jwt_token'), isNull);
      expect(await secureStorage.read(key: 'user_id'), isNull);

      // AC6: SharedPreferences keys were removed
      expect(prefsLocal.getString(kUserRoleKey), isNull);
      expect(prefsLocal.getString(kUserPhoneKey), isNull);
      expect(prefsLocal.getBool(kPasswordChangeRequiredKey), isNull);
      expect(prefsLocal.getBool(kOnboardingWizardSeenKey), isNull);

      // AC6: currentUserRoleProvider/currentUserPhoneProvider were
      // invalidated — re-reading now reflects the cleared prefs rather than
      // the cached pre-logout value.
      expect(container.read(currentUserRoleProvider), isNull);
      expect(container.read(currentUserPhoneProvider), isNull);

      // AC6: tenantStatusClaimProvider was invalidated — forcing it to
      // recompute triggers a second TokenStorage.getToken() call.
      await container.read(tenantStatusClaimProvider.future);
      verify(() => mockTokenStorage.getToken()).called(1);
    });
  });
}

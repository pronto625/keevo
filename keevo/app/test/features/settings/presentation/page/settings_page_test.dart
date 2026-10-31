import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/notification/fcm_service.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/sign_out_service.dart';
import 'package:keevo/features/notifications/data/datasource/remote_device_token_datasource.dart';
import 'package:keevo/features/notifications/presentation/provider/notification_provider.dart';
import 'package:keevo/features/settings/presentation/page/settings_page.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/sync/sync_service.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../helpers/fake_entitlements.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockFcmService extends Mock implements FcmService {}

class MockRemoteDeviceTokenDataSource extends Mock
    implements RemoteDeviceTokenDataSource {}

class MockTokenStorage extends Mock implements TokenStorage {}

class _MockAuthRepository extends Mock implements AuthRepository {}

/// Sign-out pushes the queue first; here the queue is always empty.
class _EmptyQueueSyncService extends Mock implements SyncService {
  @override
  Future<List<Map<String, dynamic>>> push() async => [];

  @override
  Future<bool> hasPendingOperations() async => false;

  @override
  Future<int> heldOperationCount() async => 0;

  @override
  Future<int> quarantinedOperationCount() async => 0;
}

/// Pending queue with [blocked] quarantined operations (v1s-16-15) and
/// [held] held ones (Story 13.8b).
class _BlockedQueueSyncService extends _EmptyQueueSyncService {
  _BlockedQueueSyncService(this.blocked, {this.held = 0});
  final int blocked;
  final int held;

  @override
  Future<int> quarantinedOperationCount() async => blocked;

  @override
  Future<int> heldOperationCount() async => held;
}

AuthRepository _authRepository() {
  final repo = _MockAuthRepository();
  when(() => repo.logout(any())).thenAnswer((_) async {});
  return repo;
}

/// Records the calls; [flush] decides when and how the queue push ends.
class _FakeSignOutService implements SignOutService {
  _FakeSignOutService(this.flush);

  final Future<bool> Function() flush;
  int flushCalls = 0;
  int signOutCalls = 0;

  @override
  Future<bool> flushPendingOperations() {
    flushCalls++;
    return flush();
  }

  @override
  Future<void> signOut() async => signOutCalls++;
}

// ── Helpers ───────────────────────────────────────────────────────────────

Widget _buildPage({
  required FcmService mockFcmService,
  required RemoteDeviceTokenDataSource mockTokenDataSource,
  required SharedPreferences prefs,
  String role = 'EMPLOYEE',
  List<Override> overrides = const [],
}) {
  return ProviderScope(
    overrides: [
      ...overrides,
      fcmServiceProvider.overrideWithValue(mockFcmService),
      remoteDeviceTokenDataSourceProvider
          .overrideWithValue(mockTokenDataSource),
      currentUserRoleProvider.overrideWithValue(role),
      currentUserPhoneProvider.overrideWithValue('+237600000000'),
      sharedPreferencesProvider.overrideWithValue(prefs),
      flutterSecureStorageProvider.overrideWithValue(
        const FlutterSecureStorage(),
      ),
      syncServiceProvider.overrideWithValue(_EmptyQueueSyncService()),
      authRepositoryProvider.overrideWithValue(_authRepository()),
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
          GoRoute(
            path: '/settings/feedback',
            builder: (_, state) => Scaffold(
              body: Center(
                child: Text(
                    'FeedbackPage from=${state.uri.queryParameters['from']}'),
              ),
            ),
          ),
          GoRoute(
            path: '/settings/help',
            builder: (_, __) => const Scaffold(
              body: Center(child: Text('HelpPage')),
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

  group('SettingsPage — Signaler un problème (Story 14.5 AC14)', () {
    for (final role in ['OWNER', 'EMPLOYEE']) {
      testWidgets(
          '$role: the tile is active and opens the feedback form with the '
          'current path as context', (tester) async {
        await tester.pumpWidget(_buildPage(
          mockFcmService: mockFcm,
          mockTokenDataSource: mockTokenDs,
          prefs: prefs,
          role: role,
        ));
        await tester.pumpAndSettle();

        await tester.scrollUntilVisible(
          find.text('Signaler un problème'),
          200,
          scrollable: find.byType(Scrollable).first,
        );
        // The tile can stop at the very bottom edge: bring it fully in view.
        await tester.ensureVisible(find.text('Signaler un problème'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Signaler un problème'));
        await tester.pumpAndSettle();

        expect(find.text('FeedbackPage from=/'), findsOneWidget);
      });
    }
  });

  group('SettingsPage — Aide & Tutoriels (v1s-14-6, FR7)', () {
    for (final role in ['OWNER', 'EMPLOYEE']) {
      testWidgets('$role: the tile has no « Bientôt » badge and opens the '
          'tutorials page', (tester) async {
        await tester.pumpWidget(_buildPage(
          mockFcmService: mockFcm,
          mockTokenDataSource: mockTokenDs,
          prefs: prefs,
          role: role,
        ));
        await tester.pumpAndSettle();

        await tester.scrollUntilVisible(
          find.text('Aide & Tutoriels'),
          200,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.ensureVisible(find.text('Aide & Tutoriels'));
        await tester.pumpAndSettle();
        final tile = find.ancestor(
            of: find.text('Aide & Tutoriels'), matching: find.byType(InkWell));
        expect(
            find.descendant(of: tile.first, matching: find.text('Bientôt')),
            findsNothing);
        await tester.tap(find.text('Aide & Tutoriels'));
        await tester.pumpAndSettle();

        expect(find.text('HelpPage'), findsOneWidget);
      });
    }
  });

  group('SettingsPage — Historique des actions (v1s-18-7)', () {
    testWidgets(
        'OWNER sees « Historique des actions » and « Mouvements de stock »',
        (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
      ));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Mouvements de stock'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Historique des actions'), findsOneWidget);
      expect(find.text('Qui a fait quoi, où et quand'), findsOneWidget);
      expect(find.text('Mouvements de stock'), findsOneWidget);
      expect(find.text('Journal d\'audit'), findsNothing);
    });

    testWidgets('EMPLOYEE sees neither tile', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Se déconnecter'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Historique des actions'), findsNothing);
      expect(find.text('Mouvements de stock'), findsNothing);
    });
  });

  group('SettingsPage — Export tiles (v1s-18-4)', () {
    testWidgets('OWNER sees « Exporter mes données » and « Mes exports »',
        (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
      ));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('settings-exports')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('settings-export-new')), findsOneWidget);
      expect(find.text('Exporter mes données'), findsOneWidget);
      expect(find.text('Mes exports'), findsOneWidget);
    });

    testWidgets('EMPLOYEE sees no export tile', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Se déconnecter'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('settings-export-new')), findsNothing);
      expect(find.byKey(const Key('settings-exports')), findsNothing);
    });
  });

  group('SettingsPage — module entries (Story 15.2)', () {
    Future<void> scrollToEnd(WidgetTester tester) => tester.scrollUntilVisible(
          find.text('Se déconnecter'),
          200,
          scrollable: find.byType(Scrollable).first,
        );

    Map<String, String> all(String state) => {
          for (final id in const [
            'crm',
            'multi-store',
            'counting',
            'exports',
            'report-config',
            'profitability',
          ])
            id: state,
        };

    testWidgets('OWNER, crm DISABLED: Clients / Fournisseurs hidden, the rest '
        'unchanged', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
        overrides: [
          entitlementsOverride({...all('ENABLED'), 'crm': 'DISABLED'}),
        ],
      ));
      await tester.pumpAndSettle();
      await scrollToEnd(tester);

      expect(find.text('Clients'), findsNothing);
      expect(find.text('Fournisseurs'), findsNothing);
      expect(find.text('Stock multi-boutiques'), findsOneWidget);
      expect(find.text('Transferts'), findsOneWidget);
      expect(find.text('Catégories'), findsOneWidget);
      expect(find.text('Rapports & WhatsApp'), findsOneWidget);
    });

    testWidgets('OWNER, premium modules DISABLED: their tiles hidden, the free '
        'export kept', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
        overrides: [
          entitlementsOverride({...all('DISABLED'), 'crm': 'ENABLED'}),
        ],
      ));
      await tester.pumpAndSettle();
      await scrollToEnd(tester);

      for (final title in const [
        'Stock multi-boutiques',
        'Inventaire',
        'Transferts',
        'Mes exports',
        'Rapports & WhatsApp',
      ]) {
        expect(find.text(title), findsNothing, reason: title);
      }
      expect(find.text('Exporter mes données'), findsOneWidget);
      expect(find.text('Clients'), findsOneWidget);
    });

    testWidgets('READ_ONLY (grace) modules keep their entries', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
        overrides: [entitlementsOverride(all('READ_ONLY'))],
      ));
      await tester.pumpAndSettle();
      await scrollToEnd(tester);

      expect(find.text('Transferts'), findsOneWidget);
      expect(find.text('Mes exports'), findsOneWidget);
      expect(find.text('Clients'), findsOneWidget);
    });

    testWidgets('EMPLOYEE, every stock module DISABLED: no « Consultation » '
        'section', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        overrides: [entitlementsOverride(all('DISABLED'))],
      ));
      await tester.pumpAndSettle();
      await scrollToEnd(tester);

      expect(find.text('Consultation'), findsNothing);
      expect(find.text('Transferts'), findsNothing);
    });

    testWidgets('module re-enabled (new server answer): entries reappear',
        (tester) async {
      var states = {...all('ENABLED'), 'crm': 'DISABLED'};
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
        overrides: [
          entitlementsProvider.overrideWith(
              () => FakeEntitlementsNotifier(() async => states)),
        ],
      ));
      await tester.pumpAndSettle();
      await scrollToEnd(tester);
      expect(find.text('Clients'), findsNothing);

      states = all('ENABLED');
      final container =
          ProviderScope.containerOf(tester.element(find.byType(SettingsPage)));
      await container.read(entitlementsProvider.notifier).refresh();
      await tester.pumpAndSettle();
      await scrollToEnd(tester);
      expect(find.text('Clients'), findsOneWidget);
      expect(find.text('Fournisseurs'), findsOneWidget);
    });
  });

  group('SettingsPage — Entreprise tile (Story 14.2)', () {
    testWidgets('OWNER sees « Entreprise »', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
      ));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('settings-company')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Entreprise'), findsOneWidget);
      expect(find.text('Nom et logo sur vos factures et exports'),
          findsOneWidget);
    });

    testWidgets('EMPLOYEE does not', (tester) async {
      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
      ));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Se déconnecter'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const Key('settings-company')), findsNothing);
    });
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

    // v1s-15-8 (deferred from CR v1s-16-1): the OWNER settings page has more
    // tiles above the logout — the FCM cleanup must run for that role too.
    testWidgets('OWNER logout deletes remote + local FCM token',
        (tester) async {
      const token = 'fcm-token-owner';
      when(() => mockFcm.getToken()).thenAnswer((_) async => token);

      await tester.pumpWidget(_buildPage(
        mockFcmService: mockFcm,
        mockTokenDataSource: mockTokenDs,
        prefs: prefs,
        role: 'OWNER',
      ));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      verify(() => mockTokenDs.deleteToken(token)).called(1);
      verify(() => mockFcm.deleteToken()).called(1);
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
      when(() => mockTokenStorage.getRefreshToken())
          .thenAnswer((_) async => null);

      final container = ProviderContainer(
        overrides: [
          fcmServiceProvider.overrideWithValue(mockFcm),
          remoteDeviceTokenDataSourceProvider.overrideWithValue(mockTokenDs),
          sharedPreferencesProvider.overrideWithValue(prefsLocal),
          flutterSecureStorageProvider.overrideWithValue(
            const FlutterSecureStorage(),
          ),
          tokenStorageProvider.overrideWithValue(mockTokenStorage),
          syncServiceProvider.overrideWithValue(_EmptyQueueSyncService()),
          authRepositoryProvider.overrideWithValue(_authRepository()),
          // 15.2: the page reads the module states — kept off the token
          // storage whose calls this test counts.
          entitlementsOverride(const {
            'multi-store': 'ENABLED',
            'counting': 'ENABLED',
          }),
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

      // AC6: session secrets were cleared (device secrets are kept)
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

  group('SettingsPage — unsynced operations at sign-out', () {
    Widget page(_FakeSignOutService service, {int blocked = 0, int held = 0}) =>
        ProviderScope(
          overrides: [
            fcmServiceProvider.overrideWithValue(mockFcm),
            remoteDeviceTokenDataSourceProvider.overrideWithValue(mockTokenDs),
            currentUserRoleProvider.overrideWithValue('EMPLOYEE'),
            currentUserPhoneProvider.overrideWithValue('+237600000000'),
            sharedPreferencesProvider.overrideWithValue(prefs),
            flutterSecureStorageProvider
                .overrideWithValue(const FlutterSecureStorage()),
            syncServiceProvider
                .overrideWithValue(_BlockedQueueSyncService(blocked, held: held)),
            authRepositoryProvider.overrideWithValue(_authRepository()),
            signOutServiceProvider.overrideWithValue(service),
          ],
          child: MaterialApp.router(
            routerConfig: GoRouter(routes: [
              GoRoute(path: '/', builder: (_, __) => const SettingsPage()),
              GoRoute(
                path: '/auth/login',
                builder: (_, __) =>
                    const Scaffold(body: Center(child: Text('Login Page'))),
              ),
            ]),
          ),
        );

    testWidgets('"Rester connecté" keeps the session', (tester) async {
      final service = _FakeSignOutService(() async => false);
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);
      expect(find.text('Opérations non synchronisées'), findsOneWidget);
      await tester.tap(find.text('Rester connecté'));
      await tester.pumpAndSettle();

      expect(service.signOutCalls, 0);
      expect(find.text('Login Page'), findsNothing);
    });

    // v1s-16-15 follow-up: blocked operations are never sent automatically —
    // the dialog must not promise they will be.
    testWidgets('blocked operations: the dialog says they will not be sent',
        (tester) async {
      final service = _FakeSignOutService(() async => false);
      await tester.pumpWidget(page(service, blocked: 1));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      expect(find.textContaining('sont bloquées'), findsOneWidget);
      expect(find.textContaining('prochaine connexion'), findsNothing);
    });

    // Story 13.8b: held operations go once their module is active again.
    testWidgets('held operations: sent once the module is active again',
        (tester) async {
      final service = _FakeSignOutService(() async => false);
      await tester.pumpWidget(page(service, held: 2));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      expect(find.textContaining('dès que le module sera réactivé'),
          findsOneWidget);
      expect(find.textContaining('prochaine connexion'), findsNothing);
      expect(find.textContaining('sont bloquées'), findsNothing);
    });

    testWidgets('only pending operations: sent at the next sign-in',
        (tester) async {
      final service = _FakeSignOutService(() async => false);
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      expect(find.textContaining('prochaine connexion'), findsOneWidget);
      expect(find.textContaining('sont bloquées'), findsNothing);
    });

    testWidgets('"Déconnecter quand même" signs out', (tester) async {
      final service = _FakeSignOutService(() async => false);
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);
      await tester.tap(find.text('Déconnecter quand même'));
      await tester.pumpAndSettle();

      expect(service.signOutCalls, 1);
      expect(find.text('Login Page'), findsOneWidget);
    });

    testWidgets('a push that throws still warns instead of aborting',
        (tester) async {
      final service =
          _FakeSignOutService(() async => throw StateError('db closed'));
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await _tapLogoutAndConfirm(tester);

      expect(find.text('Opérations non synchronisées'), findsOneWidget);
    });

    testWidgets(
        'progress is shown while the queue is sent, a second tap is ignored',
        (tester) async {
      final release = Completer<bool>();
      final service = _FakeSignOutService(() => release.future);
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Se déconnecter'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('Se déconnecter'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Déconnecter'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.text('Envoi des opérations…'), findsOneWidget);
      // The progress dialog blocks the page; even a forced tap does nothing.
      await tester.tap(find.text('Se déconnecter'), warnIfMissed: false);
      await tester.pump();
      expect(service.flushCalls, 1);

      release.complete(true);
      await tester.pumpAndSettle();

      expect(service.flushCalls, 1);
      expect(service.signOutCalls, 1);
      expect(find.text('Login Page'), findsOneWidget);
    });

    // v1s-16-13 (routed from CR v1s-16-1): a double tap on the tile opens a
    // single confirmation and signs out once.
    testWidgets('a double tap on the logout tile signs out once',
        (tester) async {
      final service = _FakeSignOutService(() async => true);
      await tester.pumpWidget(page(service));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Se déconnecter'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('Se déconnecter'));
      await tester.tap(find.text('Se déconnecter'), warnIfMissed: false);
      await tester.pumpAndSettle();

      expect(find.text('Se déconnecter ?'), findsOneWidget);
      await tester.tap(find.text('Déconnecter'));
      await tester.pumpAndSettle();

      expect(service.flushCalls, 1);
      expect(service.signOutCalls, 1);
      expect(find.text('Login Page'), findsOneWidget);
    });
  });
}

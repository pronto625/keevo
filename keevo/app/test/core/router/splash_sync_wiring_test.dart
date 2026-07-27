import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/model/login_result.dart';
import 'package:keevo/features/auth/domain/model/membership_dto.dart';
import 'package:keevo/features/auth/domain/usecase/login_usecase.dart';
import 'package:keevo/features/auth/domain/usecase/select_tenant_usecase.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

/// Story 16.8 — Verify that onAppStartup() is correctly wired at its three
/// call sites.
///
/// AC2 (Login.login() / SelectTenant.select()): tested below at the provider
/// level, driving the real `Login`/`SelectTenant` notifiers against a mocked
/// use case — a regression that removes the `onAppStartup()` call from either
/// method fails these tests.
///
/// AC1 (_SplashRedirectPageState._redirect()): NOT covered here.
/// `_SplashRedirectPage` is a private class inside `app_router.dart` (Dart
/// library-privacy makes it unreachable from this file by name), and its
/// `initState()` eagerly reads `appDatabaseProvider`, which constructs a real
/// SQLCipher-encrypted `AppDatabase` — not something this unit-test file can
/// fake without pulling in native plugin bindings. Regression coverage for
/// the splash call site currently relies on manual `flutter test` runs
/// (Task 4) and code review, not an automated check.
void main() {
  group('Story 16.8 — onAppStartup guard (SyncTriggerNotifier)', () {
    test('onAppStartup_delegatesToTriggerSync_whenIdle', () async {
      int triggerSyncCallCount = 0;

      final container = ProviderContainer(
        overrides: [
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeSyncTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      container.read(syncTriggerNotifierProvider.notifier).onAppStartup();

      expect(triggerSyncCallCount, 1,
          reason: 'onAppStartup() must call triggerSync() when state is idle');
    });

    test('onAppStartup_isNoop_whenAlreadySyncing', () async {
      // AC3: the existing guard `if (state is! SyncTriggerIdle) return;`
      // prevents double-trigger. Verify the guard works.
      int triggerSyncCallCount = 0;

      final container = ProviderContainer(
        overrides: [
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeSyncingTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      container.read(syncTriggerNotifierProvider.notifier).onAppStartup();

      expect(triggerSyncCallCount, 0,
          reason:
              'onAppStartup() must be a no-op when state is already syncing');
    });

    test('onAppStartup_isNoop_whenCriticalFailure', () async {
      // The guard is `if (state is! SyncTriggerIdle) return;` — verify it
      // also holds for the third sealed state, not just SyncTriggerSyncing.
      int triggerSyncCallCount = 0;

      final container = ProviderContainer(
        overrides: [
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeCriticalFailureTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      container.read(syncTriggerNotifierProvider.notifier).onAppStartup();

      expect(triggerSyncCallCount, 0,
          reason:
              'onAppStartup() must be a no-op when state is criticalFailure');
    });
  });

  group('Story 16.8 AC2 — Login.login() wiring', () {
    late MockLoginUseCase mockLoginUseCase;
    late SharedPreferences prefs;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      mockLoginUseCase = MockLoginUseCase();
    });

    test('login_triggersOnAppStartup_onAuthenticatedResult', () async {
      int triggerSyncCallCount = 0;
      when(() => mockLoginUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenAnswer((_) async => AuthenticatedResult(
            const AuthTokens(
              accessToken: 'token',
              refreshToken: 'refresh',
              userId: 'uid',
              tenantId: 'kv_abc',
              role: 'OWNER',
              expiresIn: 86400,
            ),
          ));

      final container = ProviderContainer(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          loginUseCaseProvider.overrideWithValue(mockLoginUseCase),
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeSyncTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      await container.read(loginProvider.notifier).login(
            phoneNumber: '600000001',
            password: 'SecurePass123!',
          );

      expect(triggerSyncCallCount, 1,
          reason:
              'Login.login() must call onAppStartup() after a fresh AuthenticatedResult');
    });

    test('login_doesNotTriggerOnAppStartup_onNeedsTenantSelection', () async {
      int triggerSyncCallCount = 0;
      when(() => mockLoginUseCase.execute(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenAnswer((_) async => NeedsTenantSelectionResult('login-token', [
            const MembershipDto(
              tenantCode: 'KV-ABC123',
              tenantName: 'Boutique Simon',
              role: 'OWNER',
              schemaName: 'kv_abc123',
            ),
          ]));

      final container = ProviderContainer(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          loginUseCaseProvider.overrideWithValue(mockLoginUseCase),
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeSyncTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      await container.read(loginProvider.notifier).login(
            phoneNumber: '600000001',
            password: 'SecurePass123!',
          );

      expect(triggerSyncCallCount, 0,
          reason:
              'Login.login() must not sync yet when tenant selection is still pending');
    });
  });

  group('Story 16.8 AC2 — SelectTenant.select() wiring', () {
    late MockSelectTenantUseCase mockSelectTenantUseCase;
    late SharedPreferences prefs;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      mockSelectTenantUseCase = MockSelectTenantUseCase();
    });

    test('selectTenant_triggersOnAppStartup_onSuccess', () async {
      int triggerSyncCallCount = 0;
      when(() => mockSelectTenantUseCase.execute(
            loginToken: any(named: 'loginToken'),
            tenantCode: any(named: 'tenantCode'),
          )).thenAnswer((_) async => const AuthTokens(
            accessToken: 'token',
            refreshToken: 'refresh',
            userId: 'uid',
            tenantId: 'kv_abc',
            role: 'OWNER',
            expiresIn: 86400,
          ));

      final container = ProviderContainer(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          selectTenantUseCaseProvider.overrideWithValue(mockSelectTenantUseCase),
          syncTriggerNotifierProvider.overrideWith(() {
            final fake = _FakeSyncTriggerNotifier();
            fake.onTriggerSyncCalled = () => triggerSyncCallCount++;
            return fake;
          }),
        ],
      );
      addTearDown(container.dispose);

      await container.read(selectTenantProvider.notifier).select(
            loginToken: 'login-token',
            tenantCode: 'KV-ABC123',
          );

      expect(triggerSyncCallCount, 1,
          reason:
              'SelectTenant.select() must call onAppStartup() after a successful tenant selection');
    });
  });
}

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockLoginUseCase extends Mock implements LoginUseCase {}

class MockSelectTenantUseCase extends Mock implements SelectTenantUseCase {}

// ── Fake notifiers for test ──────────────────────────────────────────────────

class _FakeSyncTriggerNotifier extends SyncTriggerNotifier {
  void Function()? onTriggerSyncCalled;

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> triggerSync() async {
    onTriggerSyncCalled?.call();
  }

  @override
  Future<void> triggerPush() async {}
}

class _FakeSyncingTriggerNotifier extends SyncTriggerNotifier {
  void Function()? onTriggerSyncCalled;

  @override
  SyncTriggerState build() => const SyncTriggerState.syncing();

  @override
  Future<void> triggerSync() async {
    onTriggerSyncCalled?.call();
  }

  @override
  Future<void> triggerPush() async {}
}

class _FakeCriticalFailureTriggerNotifier extends SyncTriggerNotifier {
  void Function()? onTriggerSyncCalled;

  @override
  SyncTriggerState build() =>
      const SyncTriggerState.criticalFailure(duration: Duration(hours: 25));

  @override
  Future<void> triggerSync() async {
    onTriggerSyncCalled?.call();
  }

  @override
  Future<void> triggerPush() async {}
}

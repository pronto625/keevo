import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/sync/sync_trigger_notifier.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/model/login_result.dart';
import 'package:keevo/features/auth/domain/usecase/login_usecase.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/session_claims_applier.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockLoginUseCase extends Mock implements LoginUseCase {}

class _MockApplier extends Mock implements SessionClaimsApplier {}

class _IdleSyncTrigger extends SyncTriggerNotifier {
  int startups = 0;

  @override
  SyncTriggerState build() => const SyncTriggerState.idle();

  @override
  Future<void> onAppStartup() async => startups++;
}

/// Another account's unsynced operations on the device: the login stops and
/// the page asks the user (decision D1), instead of deleting them silently.
void main() {
  late _MockLoginUseCase useCase;
  late _MockApplier applier;
  late _IdleSyncTrigger sync;
  late ProviderContainer container;

  const tokens = AuthTokens(
    accessToken: 'access-b',
    refreshToken: 'refresh-b',
    userId: 'u-b',
    tenantId: 'kv_b',
    role: 'OWNER',
    expiresIn: 3600,
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    useCase = _MockLoginUseCase();
    applier = _MockApplier();
    sync = _IdleSyncTrigger();
    when(() => useCase.execute(
          phoneNumber: any(named: 'phoneNumber'),
          password: any(named: 'password'),
        )).thenAnswer((_) async => AuthenticatedResult(tokens));
    when(() => applier.apply('access-b', newSession: true))
        .thenThrow(const PreviousAccountDataException(3));
    when(() => applier.apply('access-b',
            newSession: true, discardPreviousAccountData: true))
        .thenAnswer((_) async {});
    when(() => applier.abandonSignIn()).thenAnswer((_) async {});

    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      loginUseCaseProvider.overrideWithValue(useCase),
      sessionClaimsApplierProvider.overrideWithValue(applier),
      syncTriggerNotifierProvider.overrideWith(() => sync),
    ]);
    addTearDown(container.dispose);
  });

  Future<void> login() => container
      .read(loginProvider.notifier)
      .login(phoneNumber: '+237600000000', password: 'secret');

  test('the login stops with the number of operations at stake', () async {
    await login();

    final state = container.read(loginProvider);
    expect(state.error, isA<PreviousAccountDataException>()
        .having((e) => e.pendingOperations, 'pendingOperations', 3));
    expect(sync.startups, 0);
  });

  test('accepted: the device is cleared and the login completes', () async {
    await login();

    await container.read(loginProvider.notifier).discardPreviousAccountDataAndContinue();

    expect(container.read(loginProvider).valueOrNull, isA<AuthenticatedResult>());
    expect(sync.startups, 1);
  });

  test('declined: the new session ends, nothing is cleared', () async {
    await login();

    await container.read(loginProvider.notifier).cancelPendingSignIn();

    verify(() => applier.abandonSignIn()).called(1);
    verifyNever(() => applier.apply('access-b',
        newSession: true, discardPreviousAccountData: true));
    expect(container.read(loginProvider).valueOrNull, isNull);
  });

  test('a storage failure ends in an error, never an endless spinner', () async {
    when(() => applier.apply('access-b', newSession: true))
        .thenThrow(StateError('keystore'));

    await login();

    expect(container.read(loginProvider).hasError, isTrue);
  });
}

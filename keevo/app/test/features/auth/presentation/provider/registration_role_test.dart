import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/domain/model/registration_result.dart';
import 'package:keevo/features/auth/domain/usecase/register_user_usecase.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/session_claims_applier.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockRegisterUseCase extends Mock implements RegisterUserUseCase {}

class _MockApplier extends Mock implements SessionClaimsApplier {}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256'})}.${part({'exp': 4102444800, ...claims})}.sig';
}

/// Story 16.11 (was v1s-15-10): the role comes from the profile loaded by the
/// session applier, never from a token claim; registration only seeds 'OWNER'
/// for when the profile cannot be loaded.
void main() {
  late _MockRegisterUseCase useCase;
  late _MockApplier applier;
  late SharedPreferences prefs;
  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    useCase = _MockRegisterUseCase();
    applier = _MockApplier();
    // The applier is mocked: it writes nothing, so the stored role can only
    // come from the registration notifier itself.
    when(() => applier.apply(any(),
            newSession: any(named: 'newSession'),
            discardPreviousAccountData:
                any(named: 'discardPreviousAccountData'),
            seedRole: any(named: 'seedRole'),
            seedStoreId: any(named: 'seedStoreId'),
            seedPasswordChangeRequired:
                any(named: 'seedPasswordChangeRequired')))
        .thenAnswer((_) async {});

    container = ProviderContainer(retry: noProviderRetry, overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      registerUserUseCaseProvider.overrideWithValue(useCase),
      sessionClaimsApplierProvider.overrideWithValue(applier),
    ]);
    addTearDown(container.dispose);
  });

  Future<void> registerWithToken(String token) async {
    when(() => useCase.execute(
          phoneNumber: any(named: 'phoneNumber'),
          password: any(named: 'password'),
        )).thenAnswer((_) async => RegistrationResult(
          tenantCode: 'T1',
          token: token,
          userId: 'u1',
          tenantId: 'kv_t1',
        ));
    await container
        .read(registrationProvider.notifier)
        .register(phoneNumber: '+237600000000', password: 'secret');
  }

  test('the session applier gets the token with OWNER as the seed role', () async {
    final token = _jwt({'sub': 'u1'});
    await registerWithToken(token);

    expect(container.read(registrationProvider).hasError, isFalse);
    verify(() => applier.apply(token,
        newSession: true,
        discardPreviousAccountData: false,
        seedRole: 'OWNER',
        seedStoreId: any(named: 'seedStoreId'),
        seedPasswordChangeRequired:
            any(named: 'seedPasswordChangeRequired'))).called(1);
  });

  test('a role claim in the token is never stored', () async {
    await registerWithToken(_jwt({'role': 'EMPLOYEE'}));

    expect(prefs.getString(kUserRoleKey), isNull);
  });
}

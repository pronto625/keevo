import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/auth/domain/model/registration_result.dart';
import 'package:keevo/features/auth/domain/usecase/register_user_usecase.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/auth/presentation/provider/session_claims_applier.dart';

class _MockRegisterUseCase extends Mock implements RegisterUserUseCase {}

class _MockApplier extends Mock implements SessionClaimsApplier {}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256'})}.${part({'exp': 4102444800, ...claims})}.sig';
}

/// v1s-15-10: the role stored at registration comes from the token's role
/// claim; 'OWNER' is only the fallback for a token without the claim.
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
                any(named: 'discardPreviousAccountData')))
        .thenAnswer((_) async {});

    container = ProviderContainer(overrides: [
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

  test('role claim present: kUserRoleKey is the claim role', () async {
    await registerWithToken(_jwt({'role': 'EMPLOYEE'}));

    expect(container.read(registrationProvider).hasError, isFalse);
    expect(prefs.getString(kUserRoleKey), 'EMPLOYEE');
  });

  test('no role claim: falls back to OWNER', () async {
    await registerWithToken(_jwt({'sub': 'u1'}));

    expect(prefs.getString(kUserRoleKey), 'OWNER');
  });

  test('unparseable token: falls back to OWNER', () async {
    await registerWithToken('not-a-jwt');

    expect(prefs.getString(kUserRoleKey), 'OWNER');
  });
}

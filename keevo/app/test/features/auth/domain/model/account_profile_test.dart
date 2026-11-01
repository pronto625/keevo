import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/domain/model/account_profile.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockDio extends Mock implements Dio {}

/// Story 16.11 — the profile is the app's identity source: the JSON field
/// names are the backend `UserProfileResponse` ones.
const _serverProfile = <String, dynamic>{
  'userId': 'u1',
  'phoneNumber': '+237600000000',
  'role': 'EMPLOYEE',
  'firstName': 'Awa',
  'lastName': 'Ngo',
  'storeId': 's1',
  'storeName': 'Boutique Centrale',
  'passwordChangeRequired': true,
  'tenantStatus': 'SUSPENDED',
  'onboardingCompleted': true,
};

void main() {
  setUpAll(() => registerFallbackValue(Options()));

  test('fromJson reads every field of the server profile', () {
    final p = AccountProfile.fromJson(_serverProfile);

    expect(p.role, 'EMPLOYEE');
    expect(p.storeId, 's1');
    expect(p.storeName, 'Boutique Centrale');
    expect(p.firstName, 'Awa');
    expect(p.passwordChangeRequired, isTrue);
    expect(p.tenantStatus, 'SUSPENDED');
    expect(p.onboardingCompleted, isTrue);
  });

  test('an older server without the 16.11 fields: safe defaults', () {
    final p = AccountProfile.fromJson(
        {'userId': 'u1', 'phoneNumber': '+237600000000', 'role': 'OWNER'});
    expect(p.passwordChangeRequired, isFalse);
    expect(p.tenantStatus, isNull);
    expect(p.onboardingCompleted, isNull);
  });

  group('sessionProfileFetcherProvider', () {
    late _MockDio dio;
    late ProviderContainer container;

    setUp(() {
      dio = _MockDio();
      container = ProviderContainer(
          overrides: [sessionProfileDioProvider.overrideWithValue(dio)]);
      addTearDown(container.dispose);
    });

    test('GET /api/v1/auth/profile with Bearer <token>', () async {
      when(() => dio.get<Map<String, dynamic>>(any(), options: any(named: 'options')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/api/v1/auth/profile'),
                statusCode: 200,
                data: _serverProfile,
              ));

      final p = await container.read(sessionProfileFetcherProvider)('tok-1');

      expect(p?.role, 'EMPLOYEE');
      final captured = verify(() => dio.get<Map<String, dynamic>>(
          '/api/v1/auth/profile',
          options: captureAny(named: 'options'))).captured.single as Options;
      expect(captured.headers?['Authorization'], 'Bearer tok-1');
    });

    test('any error → null (offline, server refusal)', () async {
      when(() => dio.get<Map<String, dynamic>>(any(), options: any(named: 'options')))
          .thenThrow(DioException(
              requestOptions: RequestOptions(path: '/api/v1/auth/profile'),
              type: DioExceptionType.connectionError));

      expect(await container.read(sessionProfileFetcherProvider)('tok-1'), isNull);
    });
  });
}

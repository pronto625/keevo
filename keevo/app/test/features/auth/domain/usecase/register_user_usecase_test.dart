import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/model/registration_result.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/domain/usecase/register_user_usecase.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockAuthRepository extends Mock implements AuthRepository {}
class MockTokenStorage extends Mock implements TokenStorage {}

void main() {
  late MockAuthRepository mockRepository;
  late MockTokenStorage mockTokenStorage;
  late RegisterUserUseCase useCase;

  setUp(() {
    mockRepository = MockAuthRepository();
    mockTokenStorage = MockTokenStorage();
    useCase = RegisterUserUseCase(mockRepository, mockTokenStorage);
  });

  group('RegisterUserUseCase', () {
    const validPhone = '+237600000001';
    const validPassword = 'SecurePass123!';
    const result = RegistrationResult(
      tenantCode: 'KV-ABC123',
      token: 'STUB:user-id:tenant-id',
      userId: 'user-id',
      tenantId: 'tenant-id',
    );

    test('execute() returns RegistrationResult on valid inputs', () async {
      when(() => mockRepository.register(
            phoneNumber: validPhone,
            password: validPassword,
          )).thenAnswer((_) async => result);
      when(() => mockTokenStorage.saveToken(any())).thenAnswer((_) async {});
      when(() => mockTokenStorage.saveUserId(any())).thenAnswer((_) async {});
      when(() => mockTokenStorage.saveTenantId(any())).thenAnswer((_) async {});

      final actual = await useCase.execute(
        phoneNumber: validPhone,
        password: validPassword,
      );

      expect(actual.tenantCode, equals('KV-ABC123'));
      expect(actual.token, startsWith('STUB:'));
      // AC5: verify token stored securely
      verify(() => mockTokenStorage.saveToken('STUB:user-id:tenant-id')).called(1);
      verify(() => mockTokenStorage.saveUserId('user-id')).called(1);
      verify(() => mockTokenStorage.saveTenantId('tenant-id')).called(1);
    });

    test('execute() throws ArgumentError when phone is empty', () async {
      expect(
        () => useCase.execute(phoneNumber: '', password: validPassword),
        throwsA(isA<ArgumentError>()),
      );
      verifyNever(() => mockRepository.register(
          phoneNumber: any(named: 'phoneNumber'),
          password: any(named: 'password')));
    });

    test('execute() throws ArgumentError when phone format is invalid', () async {
      expect(
        () => useCase.execute(phoneNumber: 'abc123', password: validPassword),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('execute() throws ArgumentError when password is too short', () async {
      expect(
        () => useCase.execute(phoneNumber: validPhone, password: 'short'),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('execute() propagates repository errors', () async {
      when(() => mockRepository.register(
            phoneNumber: validPhone,
            password: validPassword,
          )).thenThrow(Exception('network error'));

      expect(
        () => useCase.execute(phoneNumber: validPhone, password: validPassword),
        throwsA(isA<Exception>()),
      );
    });
  });
}

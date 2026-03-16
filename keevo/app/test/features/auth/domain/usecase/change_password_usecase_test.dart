import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/domain/usecase/change_password_usecase.dart';

class _MockAuthRepo extends Mock implements AuthRepository {}

class _MockTokenStorage extends Mock implements TokenStorage {}

void main() {
  late _MockAuthRepo mockRepo;
  late _MockTokenStorage mockStorage;
  late ChangePasswordUseCase useCase;

  const validTokens = AuthTokens(
    accessToken: 'new-access',
    refreshToken: 'new-refresh',
    userId: 'u1',
    tenantId: 't1',
    role: 'EMPLOYEE',
    expiresIn: 3600,
    storeId: 's1',
    passwordChangeRequired: false,
  );

  setUp(() {
    mockRepo = _MockAuthRepo();
    mockStorage = _MockTokenStorage();
    useCase = ChangePasswordUseCase(mockRepo, mockStorage);

    when(() => mockStorage.saveToken(any())).thenAnswer((_) async {});
    when(() => mockStorage.saveRefreshToken(any())).thenAnswer((_) async {});
    when(() => mockStorage.saveUserId(any())).thenAnswer((_) async {});
    when(() => mockStorage.saveTenantId(any())).thenAnswer((_) async {});
    when(() => mockStorage.saveStoreId(any())).thenAnswer((_) async {});
  });

  test('execute returns new tokens on success', () async {
    when(() => mockRepo.changePassword(
          currentPassword: any(named: 'currentPassword'),
          newPassword: any(named: 'newPassword'),
        )).thenAnswer((_) async => validTokens);

    final result = await useCase.execute(
      currentPassword: 'tempPwd1',
      newPassword: 'NewPass99',
    );

    expect(result, validTokens);
    verify(() => mockStorage.saveToken('new-access')).called(1);
    verify(() => mockStorage.saveRefreshToken('new-refresh')).called(1);
    verify(() => mockStorage.saveStoreId('s1')).called(1);
  });

  test('execute throws ArgumentError when newPassword too short', () async {
    expect(
      () => useCase.execute(currentPassword: 'temp', newPassword: 'Ab1'),
      throwsA(isA<ArgumentError>()),
    );
    verifyNever(
        () => mockRepo.changePassword(
              currentPassword: any(named: 'currentPassword'),
              newPassword: any(named: 'newPassword'),
            ));
  });

  test('execute throws ArgumentError when newPassword has no digit', () async {
    expect(
      () => useCase.execute(
          currentPassword: 'temp', newPassword: 'abcdefgh'),
      throwsA(isA<ArgumentError>()),
    );
  });

  test('execute propagates AuthException from repository', () async {
    when(() => mockRepo.changePassword(
          currentPassword: any(named: 'currentPassword'),
          newPassword: any(named: 'newPassword'),
        )).thenThrow(Exception('INVALID_CREDENTIALS'));

    expect(
      () => useCase.execute(
          currentPassword: 'wrong', newPassword: 'NewPass99'),
      throwsA(isA<Exception>()),
    );
  });
}

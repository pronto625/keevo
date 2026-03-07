import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/model/login_result.dart';
import 'package:keevo/features/auth/domain/model/login_session_response.dart';
import 'package:keevo/features/auth/domain/model/membership_dto.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/domain/usecase/login_usecase.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockAuthRepository extends Mock implements AuthRepository {}
class MockTokenStorage extends Mock implements TokenStorage {}

void main() {
  late MockAuthRepository mockRepository;
  late MockTokenStorage mockTokenStorage;
  late LoginUseCase useCase;

  setUp(() {
    mockRepository = MockAuthRepository();
    mockTokenStorage = MockTokenStorage();
    useCase = LoginUseCase(mockRepository, mockTokenStorage);
  });

  const validPhone = '+237600000001';
  const validPassword = 'SecurePass123!';
  const tokens = AuthTokens(
    accessToken: 'eyJ.signed.token',
    refreshToken: 'opaque-refresh-token',
    userId: 'user-uuid',
    tenantId: 'kv_abc123',
    role: 'OWNER',
    expiresIn: 86400,
  );

  // Single-membership session — triggers auto-select (AC8)
  final singleMembershipSession = LoginSessionResponse(
    loginToken: 'login-token',
    memberships: [
      const MembershipDto(
        tenantCode: 'KV-ABC123',
        tenantName: 'Boutique Simon',
        role: 'OWNER',
        schemaName: 'kv_abc123',
      ),
    ],
  );

  // Multi-membership session — returns NeedsTenantSelectionResult (AC9)
  final multiMembershipSession = LoginSessionResponse(
    loginToken: 'login-token',
    memberships: [
      const MembershipDto(
        tenantCode: 'KV-ABC123',
        tenantName: 'Boutique Simon',
        role: 'OWNER',
        schemaName: 'kv_abc123',
      ),
      const MembershipDto(
        tenantCode: 'KV-XYZ789',
        tenantName: 'Boutique Électronique',
        role: 'OWNER',
        schemaName: 'kv_xyz789',
      ),
    ],
  );

  group('LoginUseCase', () {
    group('execute() — success path', () {
      setUp(() {
        when(() => mockRepository.login(
              phoneNumber: validPhone,
              password: validPassword,
            )).thenAnswer((_) async => singleMembershipSession);
        when(() => mockRepository.selectTenant(
              loginToken: 'login-token',
              tenantCode: 'KV-ABC123',
            )).thenAnswer((_) async => tokens);
        when(() => mockTokenStorage.saveToken(any())).thenAnswer((_) async {});
        when(() => mockTokenStorage.saveRefreshToken(any()))
            .thenAnswer((_) async {});
        when(() => mockTokenStorage.saveUserId(any())).thenAnswer((_) async {});
        when(() => mockTokenStorage.saveTenantId(any()))
            .thenAnswer((_) async {});
      });

      test('AC8: single membership → returns AuthenticatedResult', () async {
        final result = await useCase.execute(
          phoneNumber: validPhone,
          password: validPassword,
        );
        expect(result, isA<AuthenticatedResult>());
        final authenticated = result as AuthenticatedResult;
        expect(authenticated.tokens.accessToken, equals('eyJ.signed.token'));
        expect(authenticated.tokens.role, equals('OWNER'));
      });

      test('AC5: stores all tokens securely in flutter_secure_storage',
          () async {
        await useCase.execute(
          phoneNumber: validPhone,
          password: validPassword,
        );
        verify(() => mockTokenStorage.saveToken('eyJ.signed.token')).called(1);
        verify(() => mockTokenStorage.saveRefreshToken('opaque-refresh-token'))
            .called(1);
        verify(() => mockTokenStorage.saveUserId('user-uuid')).called(1);
        verify(() => mockTokenStorage.saveTenantId('kv_abc123')).called(1);
      });

      test('trims leading/trailing whitespace from phone number', () async {
        when(() => mockRepository.login(
              phoneNumber: validPhone,
              password: validPassword,
            )).thenAnswer((_) async => singleMembershipSession);

        await useCase.execute(
          phoneNumber: '  $validPhone  ',
          password: validPassword,
        );

        verify(() => mockRepository.login(
              phoneNumber: validPhone,
              password: validPassword,
            )).called(1);
      });

      test('AC9: multiple memberships → returns NeedsTenantSelectionResult without calling selectTenant',
          () async {
        when(() => mockRepository.login(
              phoneNumber: validPhone,
              password: validPassword,
            )).thenAnswer((_) async => multiMembershipSession);

        final result = await useCase.execute(
          phoneNumber: validPhone,
          password: validPassword,
        );

        expect(result, isA<NeedsTenantSelectionResult>());
        final picker = result as NeedsTenantSelectionResult;
        expect(picker.loginToken, equals('login-token'));
        expect(picker.memberships, hasLength(2));
        verifyNever(() => mockRepository.selectTenant(
              loginToken: any(named: 'loginToken'),
              tenantCode: any(named: 'tenantCode'),
            ));
        verifyNever(() => mockTokenStorage.saveToken(any()));
      });
    });

    group('execute() — error path', () {
      test('propagates INVALID_CREDENTIALS AuthException', () async {
        when(() => mockRepository.login(
              phoneNumber: any(named: 'phoneNumber'),
              password: any(named: 'password'),
            )).thenThrow(const AuthException(
          domainCode: 'INVALID_CREDENTIALS',
          message: 'Numéro ou mot de passe incorrect',
        ));

        expect(
          () => useCase.execute(
              phoneNumber: validPhone, password: 'WrongPass!'),
          throwsA(
            isA<AuthException>()
                .having((e) => e.domainCode, 'domainCode', 'INVALID_CREDENTIALS'),
          ),
        );
      });

      test('propagates ACCOUNT_LOCKED AuthException', () async {
        when(() => mockRepository.login(
              phoneNumber: any(named: 'phoneNumber'),
              password: any(named: 'password'),
            )).thenThrow(const AuthException(
          domainCode: 'ACCOUNT_LOCKED',
          message: 'Compte verrouillé',
        ));

        expect(
          () => useCase.execute(
              phoneNumber: validPhone, password: validPassword),
          throwsA(
            isA<AuthException>()
                .having((e) => e.domainCode, 'domainCode', 'ACCOUNT_LOCKED'),
          ),
        );
      });

      test('does NOT store tokens on error', () async {
        when(() => mockRepository.login(
              phoneNumber: any(named: 'phoneNumber'),
              password: any(named: 'password'),
            )).thenThrow(const AuthException(
          domainCode: 'INVALID_CREDENTIALS',
          message: 'Wrong credentials',
        ));

        try {
          await useCase.execute(
              phoneNumber: validPhone, password: 'wrong');
        } on AuthException {
          // expected
        }

        verifyNever(() => mockTokenStorage.saveToken(any()));
        verifyNever(() => mockTokenStorage.saveRefreshToken(any()));
      });
    });
  });
}

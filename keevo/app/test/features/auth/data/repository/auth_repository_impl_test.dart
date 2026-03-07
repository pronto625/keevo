import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/auth/data/datasource/remote_auth_datasource.dart';
import 'package:keevo/features/auth/data/repository/auth_repository_impl.dart';
import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/model/auth_tokens.dart';
import 'package:keevo/features/auth/domain/model/login_session_response.dart';
import 'package:keevo/features/auth/domain/model/membership_dto.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────

class MockRemoteAuthDataSource extends Mock implements RemoteAuthDataSource {}

void main() {
  late MockRemoteAuthDataSource mockDataSource;
  late AuthRepositoryImpl repository;

  setUp(() {
    mockDataSource = MockRemoteAuthDataSource();
    repository = AuthRepositoryImpl(mockDataSource);
  });

  const validPhone = '+237600000001';
  const validPassword = 'SecurePass123!';

  // Two-step login session stub (Story 1.7)
  final session = LoginSessionResponse(
    loginToken: 'short-lived-login-token',
    memberships: [
      const MembershipDto(
        tenantCode: 'KV-ABC123',
        tenantName: 'Boutique Simon',
        role: 'OWNER',
        schemaName: 'kv_abc123',
      ),
    ],
  );

  const tokens = AuthTokens(
    accessToken: 'eyJ.signed.token',
    refreshToken: 'opaque-refresh-token',
    userId: 'user-uuid',
    tenantId: 'kv_abc123',
    role: 'OWNER',
    expiresIn: 86400,
  );

  group('AuthRepositoryImpl.login()', () {
    test('maps 200 → LoginSessionResponse with loginToken and memberships', () async {
      when(() => mockDataSource.login(
            phoneNumber: validPhone,
            password: validPassword,
          )).thenAnswer((_) async => session);

      final result = await repository.login(
        phoneNumber: validPhone,
        password: validPassword,
      );

      expect(result.loginToken, equals('short-lived-login-token'));
      expect(result.memberships, hasLength(1));
      expect(result.memberships.first.tenantCode, equals('KV-ABC123'));
      expect(result.memberships.first.role, equals('OWNER'));
    });

    test('propagates AuthException(INVALID_CREDENTIALS) on 401', () async {
      when(() => mockDataSource.login(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenThrow(const AuthException(
        domainCode: 'INVALID_CREDENTIALS',
        message: 'Wrong credentials',
      ));

      expect(
        () => repository.login(phoneNumber: validPhone, password: 'wrong'),
        throwsA(
          isA<AuthException>().having(
            (e) => e.domainCode,
            'domainCode',
            'INVALID_CREDENTIALS',
          ),
        ),
      );
    });

    test('propagates AuthException(ACCOUNT_LOCKED) when account is locked',
        () async {
      when(() => mockDataSource.login(
            phoneNumber: any(named: 'phoneNumber'),
            password: any(named: 'password'),
          )).thenThrow(const AuthException(
        domainCode: 'ACCOUNT_LOCKED',
        message: 'Compte verrouillé',
      ));

      expect(
        () => repository.login(
            phoneNumber: validPhone, password: validPassword),
        throwsA(
          isA<AuthException>()
              .having((e) => e.domainCode, 'domainCode', 'ACCOUNT_LOCKED'),
        ),
      );
    });
  });

  group('AuthRepositoryImpl.selectTenant()', () {
    test('maps 200 → AuthTokens', () async {
      when(() => mockDataSource.selectTenant(
            loginToken: 'login-token',
            tenantCode: 'KV-ABC123',
          )).thenAnswer((_) async => tokens);

      final result = await repository.selectTenant(
        loginToken: 'login-token',
        tenantCode: 'KV-ABC123',
      );

      expect(result.accessToken, equals('eyJ.signed.token'));
      expect(result.userId, equals('user-uuid'));
    });

    test('propagates AuthException(TOKEN_EXPIRED) on failure', () async {
      when(() => mockDataSource.selectTenant(
            loginToken: any(named: 'loginToken'),
            tenantCode: any(named: 'tenantCode'),
          )).thenThrow(const AuthException(
        domainCode: 'TOKEN_EXPIRED',
        message: 'Login token expired',
      ));

      expect(
        () => repository.selectTenant(
            loginToken: 'expired', tenantCode: 'KV-ABC123'),
        throwsA(
          isA<AuthException>().having(
            (e) => e.domainCode,
            'domainCode',
            'TOKEN_EXPIRED',
          ),
        ),
      );
    });
  });

  group('AuthRepositoryImpl.refreshToken()', () {
    test('maps 200 → new AuthTokens', () async {
      when(() => mockDataSource.refreshToken('old-refresh-token'))
          .thenAnswer((_) async => tokens);

      final result = await repository.refreshToken('old-refresh-token');

      expect(result.accessToken, equals('eyJ.signed.token'));
    });

    test('propagates AuthException(REFRESH_TOKEN_INVALID) on failure',
        () async {
      when(() => mockDataSource.refreshToken(any()))
          .thenThrow(const AuthException(
        domainCode: 'REFRESH_TOKEN_INVALID',
        message: 'Token expiré',
      ));

      expect(
        () => repository.refreshToken('expired-token'),
        throwsA(
          isA<AuthException>().having(
            (e) => e.domainCode,
            'domainCode',
            'REFRESH_TOKEN_INVALID',
          ),
        ),
      );
    });
  });
}

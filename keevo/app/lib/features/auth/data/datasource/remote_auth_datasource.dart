import 'package:dio/dio.dart';

import '../../domain/exception/auth_exception.dart';
import '../../domain/model/auth_tokens.dart';
import '../../domain/model/login_session_response.dart';
import '../../domain/model/membership_dto.dart';
import '../../domain/model/registration_result.dart';

/// RemoteAuthDataSource — HTTP adapter to the backend auth API.
///
/// Uses Dio for HTTP — supports interceptors, timeouts and typed responses.
/// Maps JSON responses to domain models.
/// Translates DioException HTTP error codes to [AuthException].
class RemoteAuthDataSource {
  final Dio _dio;

  RemoteAuthDataSource({required Dio dio}) : _dio = dio;

  /// POST /api/v1/auth/register
  Future<RegistrationResult> register({
    required String phoneNumber,
    required String password,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/register',
        data: {
          'phoneNumber': phoneNumber,
          'password': password,
        },
      );

      final body = response.data!;
      return RegistrationResult(
        tenantCode: body['tenantCode'] as String,
        token: body['token'] as String,
        userId: body['userId'] as String,
        tenantId: body['tenantId'] as String,
      );
    } on DioException catch (e) {
      throw _mapRegisterError(e);
    }
  }

  /// POST /api/v1/auth/login (Step 1 of two-step login — Story 1.7)
  ///
  /// Returns [LoginSessionResponse] with a short-lived loginToken (5 min)
  /// and the user's tenant memberships.
  /// Throws [AuthException] with INVALID_CREDENTIALS or ACCOUNT_LOCKED.
  Future<LoginSessionResponse> login({
    required String phoneNumber,
    required String password,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/login',
        data: {
          'phoneNumber': phoneNumber,
          'password': password,
        },
      );
      return _mapLoginSession(response.data!);
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  /// POST /api/v1/auth/select-tenant (Step 2 of two-step login — Story 1.7)
  ///
  /// Exchanges a valid [loginToken] + [tenantCode] for a full [AuthTokens].
  /// Throws [AuthException] on TOKEN_EXPIRED, TOKEN_INVALID, TENANT_NOT_FOUND.
  Future<AuthTokens> selectTenant({
    required String loginToken,
    required String tenantCode,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/select-tenant',
        data: {
          'loginToken': loginToken,
          'tenantCode': tenantCode,
        },
      );
      return _mapAuthTokens(response.data!);
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  /// POST /api/v1/auth/refresh
  ///
  /// Exchanges a valid refresh token for a new [AuthTokens] pair.
  Future<AuthTokens> refreshToken(String rawRefreshToken) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/refresh',
        data: {'refreshToken': rawRefreshToken},
      );
      return _mapAuthTokens(response.data!);
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  // ── Mapping helpers ─────────────────────────────────────────────────────

  LoginSessionResponse _mapLoginSession(Map<String, dynamic> body) {
    final rawMemberships = body['memberships'] as List<dynamic>? ?? [];
    final memberships = rawMemberships.map((m) {
      final map = m as Map<String, dynamic>;
      return MembershipDto(
        tenantCode: map['tenantCode'] as String,
        tenantName: map['tenantName'] as String,
        role: map['role'] as String,
        schemaName: map['schemaName'] as String,
      );
    }).toList();
    return LoginSessionResponse(
      loginToken: body['loginToken'] as String,
      memberships: memberships,
    );
  }

  AuthTokens _mapAuthTokens(Map<String, dynamic> body) {
    return AuthTokens(
      accessToken: body['accessToken'] as String,
      refreshToken: body['refreshToken'] as String,
      userId: body['userId'] as String,
      tenantId: body['tenantId'] as String,
      role: body['role'] as String,
      expiresIn: body['expiresIn'] as int,
    );
  }

  AuthException _mapAuthError(DioException e) {
    final statusCode = e.response?.statusCode;
    final body = e.response?.data as Map<String, dynamic>?;
    final domainCode = body?['domainCode'] as String?;

    if (statusCode == 401) {
      if (domainCode == 'ACCOUNT_LOCKED') {
        return AuthException(
          domainCode: 'ACCOUNT_LOCKED',
          message: 'Compte verrouillé temporairement après trop de tentatives',
        );
      }
      if (domainCode == 'REFRESH_TOKEN_INVALID') {
        return AuthException(
          domainCode: 'REFRESH_TOKEN_INVALID',
          message: 'Session expirée, veuillez vous reconnecter',
        );
      }
      return AuthException(
        domainCode: 'INVALID_CREDENTIALS',
        message: 'Numéro ou mot de passe incorrect',
      );
    }

    return AuthException(
      domainCode: domainCode ?? 'INTERNAL_ERROR',
      message: body?['error'] as String? ?? 'Erreur inattendue',
    );
  }

  AuthException _mapRegisterError(DioException e) {
    final statusCode = e.response?.statusCode;
    final body = e.response?.data as Map<String, dynamic>?;

    switch (statusCode) {
      case 409:
        return const AuthException(
          domainCode: 'USER_ALREADY_EXISTS',
          message: 'Un compte avec ce numéro existe déjà',
        );
      case 422:
      case 400:
        return const AuthException(
          domainCode: 'VALIDATION_ERROR',
          message: 'Données invalides',
        );
      default:
        return AuthException(
          domainCode: body?['domainCode'] as String? ?? 'INTERNAL_ERROR',
          message: body?['error'] as String? ?? 'Erreur inattendue',
        );
    }
  }
}

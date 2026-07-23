import 'package:dio/dio.dart';

import '../../domain/exception/auth_exception.dart';
import '../../domain/model/account_profile.dart';
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

  /// POST /api/v1/auth/change-password (Story 3.5 — AC4)
  ///
  /// Changes the employee's password and returns fresh [AuthTokens].
  Future<AuthTokens> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/change-password',
        data: {
          'currentPassword': currentPassword,
          'newPassword': newPassword,
        },
      );
      return _mapAuthTokens(response.data!);
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  /// GET /api/v1/auth/profile (Story 8.6 AC3)
  ///
  /// Returns the authenticated user's [AccountProfile].
  /// Requires valid JWT (injected by AuthInterceptor).
  Future<AccountProfile> getProfile() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/auth/profile',
      );
      return AccountProfile.fromJson(response.data!);
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  /// POST /api/v1/auth/forgot-password (Story 14.12)
  ///
  /// Always returns 200 {"sent":true} — anti-enumeration.
  /// Rate-limited server-side: 1/min + 5/hour per phone.
  Future<bool> forgotPassword({required String phoneNumber}) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/forgot-password',
        data: {'phoneNumber': phoneNumber},
      );
      // Null-safe: if server returns 200 without body (proxy/LB edge case),
      // fail-open to `true` — consistent with D1 anti-enumeration (always success).
      return (response.data?['sent'] as bool?) ?? true;
    } on DioException catch (e) {
      throw _mapAuthError(e);
    }
  }

  /// POST /api/v1/auth/reset-password (Story 14.12)
  ///
  /// Resets the user's password using an OTP code.
  /// Throws [AuthException] on INVALID_OR_EXPIRED_CODE, CODE_LOCKED, or VALIDATION_FAILED.
  Future<void> resetPassword({
    required String phoneNumber,
    required String code,
    required String newPassword,
  }) async {
    try {
      await _dio.post<Map<String, dynamic>>(
        '/api/v1/auth/reset-password',
        data: {
          'phoneNumber': phoneNumber,
          'code': code,
          'newPassword': newPassword,
        },
      );
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
      storeId: body['storeId'] as String?,
      passwordChangeRequired: body['passwordChangeRequired'] as bool? ?? false,
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

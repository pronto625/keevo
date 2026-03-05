import 'package:dio/dio.dart';

import '../../domain/exception/auth_exception.dart';
import '../../domain/model/auth_tokens.dart';
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

  /// POST /api/v1/auth/login
  ///
  /// Returns [AuthTokens] on success.
  /// Throws [AuthException] with INVALID_CREDENTIALS or ACCOUNT_LOCKED.
  Future<AuthTokens> login({
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

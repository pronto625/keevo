import 'package:dio/dio.dart';

import '../../domain/exception/auth_exception.dart';
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
      final statusCode = e.response?.statusCode;
      final body = e.response?.data as Map<String, dynamic>?;

      switch (statusCode) {
        case 409:
          throw const AuthException(
            domainCode: 'USER_ALREADY_EXISTS',
            message: 'Un compte avec ce numéro existe déjà',
          );
        case 422:
        case 400:
          throw const AuthException(
            domainCode: 'VALIDATION_ERROR',
            message: 'Données invalides',
          );
        default:
          throw AuthException(
            domainCode: body?['domainCode'] as String? ?? 'INTERNAL_ERROR',
            message: body?['error'] as String? ?? 'Erreur inattendue',
          );
      }
    }
  }
}

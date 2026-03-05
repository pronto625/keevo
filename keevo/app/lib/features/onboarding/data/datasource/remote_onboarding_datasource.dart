import 'package:dio/dio.dart';

import '../../domain/exception/onboarding_exception.dart';
import '../../domain/model/onboarding_result.dart';
import '../../domain/model/sector_type.dart';

/// RemoteOnboardingDataSource — HTTP adapter to the backend onboarding API.
///
/// Uses Dio for HTTP — supports interceptors, timeouts and typed responses.
/// Maps JSON responses to domain models.
/// Translates DioException HTTP error codes to [OnboardingException].
class RemoteOnboardingDataSource {
  final Dio _dio;

  RemoteOnboardingDataSource({required Dio dio}) : _dio = dio;

  /// POST /api/v1/onboarding/complete
  ///
  /// Returns [OnboardingResult] on success.
  /// Throws [OnboardingException] on HTTP / network errors.
  Future<OnboardingResult> completeOnboarding({
    required SectorType sectorType,
    required String storeName,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/onboarding/complete',
        data: {
          'sectorType': sectorType.apiCode,
          'storeName': storeName,
        },
      );

      final body = response.data!;
      return OnboardingResult(
        tenantId: body['tenantId'] as String,
        sectorType: body['sectorType'] as String,
        storeName: body['storeName'] as String,
        categoriesCreated: body['categoriesCreated'] as int,
      );
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  // ── Mapping helpers ─────────────────────────────────────────────────────

  OnboardingException _mapError(DioException e) {
    final data = e.response?.data;
    final domainCode = (data is Map ? data['domainCode'] as String? : null) ??
        'ONBOARDING_ERROR';
    final message =
        (data is Map ? data['message'] as String? : null) ?? e.message ?? '';

    return OnboardingException(domainCode: domainCode, message: message);
  }
}

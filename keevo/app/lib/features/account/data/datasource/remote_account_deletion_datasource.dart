import 'package:dio/dio.dart';

/// RemoteAccountDeletionDataSource — self-service account deletion endpoints
/// (Story 14.5, FR91).
///
/// Both endpoints are OWNER-only on the server (`AccountController`):
/// - `POST /api/v1/account/delete`        → 409 `DELETION_ALREADY_REQUESTED`
/// - `POST /api/v1/account/delete/cancel` → 409 `DELETION_NOT_PENDING`
///
/// The responses carry only a French `message`; the scheduled date is
/// computed client-side (now + 30 days, same rule as the server).
class RemoteAccountDeletionDataSource {
  final Dio _dio;

  const RemoteAccountDeletionDataSource({required Dio dio}) : _dio = dio;

  Future<void> requestDeletion() async {
    await _dio.post<Map<String, dynamic>>('/api/v1/account/delete');
  }

  Future<void> cancelDeletion() async {
    await _dio.post<Map<String, dynamic>>('/api/v1/account/delete/cancel');
  }
}

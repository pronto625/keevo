import 'package:dio/dio.dart';

import '../domain/device_session.dart';

/// Story 16.11 — « Appareils connectés » API.
///
/// - mine: `GET/DELETE /api/v1/auth/sessions[/{sessionId}]`
/// - owner, for an employee: `GET/DELETE /api/v1/employees/{id}/sessions[/{sessionId}]`
class RemoteDeviceSessionsDataSource {
  final Dio _dio;

  RemoteDeviceSessionsDataSource(this._dio);

  Future<List<DeviceSession>> mine() => _list('/api/v1/auth/sessions');

  Future<void> closeMine(String sessionId) =>
      _dio.delete<void>('/api/v1/auth/sessions/$sessionId');

  /// Closes every other session of mine; returns how many.
  Future<int> closeMyOthers() => _closeAll('/api/v1/auth/sessions');

  Future<List<DeviceSession>> ofEmployee(String employeeId) =>
      _list('/api/v1/employees/$employeeId/sessions');

  Future<void> closeOfEmployee(String employeeId, String sessionId) =>
      _dio.delete<void>('/api/v1/employees/$employeeId/sessions/$sessionId');

  Future<int> closeAllOfEmployee(String employeeId) =>
      _closeAll('/api/v1/employees/$employeeId/sessions');

  Future<List<DeviceSession>> _list(String path) async {
    final response = await _dio.get<Map<String, dynamic>>(path);
    final data = response.data?['data'];
    if (data is! List) return const [];
    return data
        .whereType<Map<String, dynamic>>()
        .map(DeviceSession.fromJson)
        .toList();
  }

  Future<int> _closeAll(String path) async {
    final response = await _dio.delete<Map<String, dynamic>>(path);
    final data = response.data?['data'];
    return data is Map ? (data['closed'] as num?)?.toInt() ?? 0 : 0;
  }
}

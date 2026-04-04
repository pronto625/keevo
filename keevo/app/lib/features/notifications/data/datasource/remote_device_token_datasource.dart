import 'package:dio/dio.dart';

class RemoteDeviceTokenDataSource {
  final Dio _dio;
  RemoteDeviceTokenDataSource({required Dio dio}) : _dio = dio;

  Future<void> registerToken({
    required String token,
    required String platform,
    String? deviceName,
  }) async {
    await _dio.post<Map<String, dynamic>>(
      '/api/v1/devices/token',
      data: {
        'token': token,
        'platform': platform,
        if (deviceName != null) 'deviceName': deviceName,
      },
    );
  }

  Future<void> deleteToken(String token) async {
    await _dio.delete<Map<String, dynamic>>(
      '/api/v1/devices/token',
      data: {'token': token},
    );
  }
}

import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../../domain/model/company_profile.dart';

/// `/api/v1/account/company` (Story 14.2) — online only: the company identity
/// lives on the server, it is read by the invoice and export documents there.
class CompanyProfileApi {
  CompanyProfileApi(this._dio);

  final Dio _dio;

  static const _path = '/api/v1/account/company';

  Future<CompanyProfile> fetch() async {
    final response = await _dio.get<Map<String, dynamic>>(_path);
    return _profile(response.data);
  }

  /// Blank [designation] clears it (the shop name is shown again).
  Future<CompanyProfile> updateDesignation(String? designation) async {
    final response = await _dio.put<Map<String, dynamic>>(
      _path,
      data: {'designation': designation?.trim() ?? ''},
    );
    return _profile(response.data);
  }

  /// The logo bytes, null when the company has none (404).
  Future<Uint8List?> fetchLogo() async {
    try {
      final response = await _dio.get<List<int>>(
        '$_path/logo',
        options: Options(responseType: ResponseType.bytes),
      );
      final data = response.data;
      return data == null ? null : Uint8List.fromList(data);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  /// [bytes] already compressed ([CompanyLogoCompressor]); [png] picks the
  /// declared type.
  Future<CompanyProfile> uploadLogo(Uint8List bytes, {required bool png}) async {
    final response = await _dio.put<Map<String, dynamic>>(
      '$_path/logo',
      data: FormData.fromMap({
        'file': MultipartFile.fromBytes(
          bytes,
          filename: png ? 'logo.png' : 'logo.jpg',
          contentType: DioMediaType('image', png ? 'png' : 'jpeg'),
        ),
      }),
    );
    return _profile(response.data);
  }

  Future<CompanyProfile> deleteLogo() async {
    final response = await _dio.delete<Map<String, dynamic>>('$_path/logo');
    return _profile(response.data);
  }

  static CompanyProfile _profile(Map<String, dynamic>? body) {
    final data = body?['data'];
    if (data is! Map<String, dynamic>) {
      throw StateError('Réponse inattendue du serveur');
    }
    return CompanyProfile.fromJson(data);
  }
}

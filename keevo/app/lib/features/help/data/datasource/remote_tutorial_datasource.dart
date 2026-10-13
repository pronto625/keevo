import 'package:dio/dio.dart';

import '../../domain/model/tutorial.dart';

/// RemoteTutorialDataSource — `GET /api/v1/help/tutorials` (v1s-14-6).
class RemoteTutorialDataSource {
  final Dio _dio;

  RemoteTutorialDataSource({required Dio dio}) : _dio = dio;

  Future<List<Tutorial>> fetchTutorials() async {
    final response =
        await _dio.get<Map<String, dynamic>>('/api/v1/help/tutorials');
    final data = response.data!['data'] as List<dynamic>;
    return data
        .map((e) => Tutorial.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}

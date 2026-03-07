import 'package:dio/dio.dart';

import '../../domain/model/subscription_info.dart';
import '../../domain/repository/subscription_repository.dart';

/// RemoteSubscriptionRepository — fetches subscription data from the backend REST API.
class RemoteSubscriptionRepository implements SubscriptionRepository {
  final Dio _dio;

  const RemoteSubscriptionRepository({required Dio dio}) : _dio = dio;

  @override
  Future<SubscriptionInfo> getMySubscription() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/subscription/me',
    );
    final data = response.data!['data'] as Map<String, dynamic>;
    return SubscriptionInfo.fromJson(data);
  }
}

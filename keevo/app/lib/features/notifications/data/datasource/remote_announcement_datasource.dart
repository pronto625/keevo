import 'package:dio/dio.dart';

/// A Keevo announcement (global push SENT to the company) — Story 9-5.
class RemoteAnnouncement {
  final String id;
  final String title;
  final String body;
  final DateTime sentAt;

  const RemoteAnnouncement({
    required this.id,
    required this.title,
    required this.body,
    required this.sentAt,
  });

  factory RemoteAnnouncement.fromJson(Map<String, dynamic> json) =>
      RemoteAnnouncement(
        id: json['id'] as String,
        title: json['title'] as String? ?? '',
        body: json['body'] as String? ?? '',
        sentAt: DateTime.parse(json['sentAt'] as String),
      );
}

/// GET /api/v1/notifications/announcements — the company comes from the JWT.
class RemoteAnnouncementDataSource {
  final Dio _dio;
  RemoteAnnouncementDataSource({required Dio dio}) : _dio = dio;

  Future<List<RemoteAnnouncement>> fetch({required DateTime since}) async {
    final res = await _dio.get<Map<String, dynamic>>(
      '/api/v1/notifications/announcements',
      queryParameters: {'since': since.toUtc().toIso8601String()},
    );
    final data = res.data?['data'];
    if (data is! List) return const [];
    return data
        .whereType<Map<String, dynamic>>()
        .map(RemoteAnnouncement.fromJson)
        .toList();
  }
}

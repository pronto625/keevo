/// Tutorial — one short French video of « Aide & Tutoriels » (FR7, v1s-14-6).
///
/// The video is hosted outside Keevo (YouTube…) and opened in the external
/// app/browser. A blank or non-http(s) [url] means « Bientôt disponible ».
class Tutorial {
  final String id;
  final String module;
  final String title;
  final String description;
  final int durationSeconds;
  final String url;

  const Tutorial({
    required this.id,
    required this.module,
    required this.title,
    this.description = '',
    this.durationSeconds = 0,
    this.url = '',
  });

  /// Only absolute http(s) links with a host are ever opened.
  bool get isAvailable {
    final uri = Uri.tryParse(url.trim());
    if (uri == null || uri.host.isEmpty) return false;
    return uri.scheme == 'http' || uri.scheme == 'https';
  }

  factory Tutorial.fromJson(Map<String, dynamic> json) => Tutorial(
        id: json['id'] as String? ?? '',
        module: json['module'] as String? ?? '',
        title: json['title'] as String? ?? '',
        description: json['description'] as String? ?? '',
        durationSeconds: (json['durationSeconds'] as num?)?.toInt() ?? 0,
        url: json['url'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'module': module,
        'title': title,
        'description': description,
        'durationSeconds': durationSeconds,
        'url': url,
      };
}

/// The tutorial list plus where it came from.
class TutorialCatalog {
  final List<Tutorial> tutorials;

  /// True when served from the local cache because the server was unreachable.
  final bool fromCache;

  /// When the list was last fetched from the server.
  final DateTime? fetchedAt;

  const TutorialCatalog({
    required this.tutorials,
    this.fromCache = false,
    this.fetchedAt,
  });
}

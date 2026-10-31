import 'dart:io';

import 'package:dio/dio.dart';

import 'product_photo_files.dart';

/// Local copies of server product photos, for display (FR21).
///
/// The photo endpoint is authenticated, so the image is fetched with the app
/// [Dio] client (token + refresh) rather than `Image.network`, then kept in
/// `product_images/cache/` — shown offline afterwards, and never downloaded
/// twice: a new photo has a new URL (`?v=`), hence a new file.
class ProductPhotoCache {
  ProductPhotoCache({
    required Dio dio,
    ProductPhotoFiles? files,
    DateTime Function()? clock,
  })  : _dio = dio,
        _files = files ?? ProductPhotoFiles(),
        _clock = clock ?? DateTime.now;

  final Dio _dio;
  final ProductPhotoFiles _files;
  final DateTime Function() _clock;

  /// After a failed download (offline…), the URL is not retried before this
  /// delay — a grid of cards rebuilds often.
  static const retryAfter = Duration(seconds: 30);

  final _inFlight = <String, Future<File?>>{};
  final _failedAt = <String, DateTime>{};

  /// The local file of [url], downloading it if needed; null when it is not
  /// available (offline, deleted on the server…) — the caller then shows the
  /// initials placeholder.
  Future<File?> resolve(String url) {
    final running = _inFlight[url];
    if (running != null) return running;
    final failed = _failedAt[url];
    if (failed != null && _clock().difference(failed) < retryAfter) {
      return _cachedOnly(url);
    }
    // Block body: an arrow would return the removed future itself, which
    // whenComplete would then wait for — a deadlock.
    final future = _resolve(url).whenComplete(() {
      _inFlight.remove(url);
    });
    _inFlight[url] = future;
    return future;
  }

  Future<File?> _cachedOnly(String url) async {
    final file = await _files.cacheFileFor(url);
    return file.existsSync() ? file : null;
  }

  Future<File?> _resolve(String url) async {
    final file = await _files.cacheFileFor(url);
    if (file.existsSync() && file.lengthSync() > 0) return file;
    try {
      final response = await _dio.get<List<int>>(
        url,
        options: Options(responseType: ResponseType.bytes),
      );
      final bytes = response.data;
      if (bytes == null || bytes.isEmpty) {
        _failedAt[url] = _clock();
        return null;
      }
      final part = File('${file.path}.part');
      await part.writeAsBytes(bytes, flush: true);
      await part.rename(file.path);
      _failedAt.remove(url);
      return file;
    } catch (_) {
      _failedAt[url] = _clock();
      return null;
    }
  }
}

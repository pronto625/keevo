import 'dart:io';
import 'dart:typed_data';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// On-device files of product photos.
///
/// * `product_images/<productId>_<millis>.jpg` — a thumbnail taken on this
///   device and not yet on the server (its path is the product `photoUrl`
///   until the upload succeeds);
/// * `product_images/cache/<url key>.jpg` — the local copy of a server photo
///   (`/api/v1/products/<id>/photo?v=<version>`): kept for offline display,
///   one file per version so a new photo is a new file.
class ProductPhotoFiles {
  ProductPhotoFiles({Future<Directory> Function()? rootDir})
      : _rootDir = rootDir ?? getApplicationDocumentsDirectory;

  final Future<Directory> Function() _rootDir;

  static const _dirName = 'product_images';
  static const _cacheDirName = 'cache';

  /// Server photo path (relative to the API base URL) or absolute URL.
  static bool isServerUrl(String? url) =>
      url != null &&
      (url.startsWith('/api/') ||
          url.startsWith('http://') ||
          url.startsWith('https://'));

  /// A file on this device (not uploaded yet, or a legacy local-only photo).
  static bool isLocalPath(String? url) =>
      url != null &&
      url.isNotEmpty &&
      !isServerUrl(url) &&
      (url.startsWith('/') || url.startsWith('file://'));

  static String localFilePath(String url) =>
      url.startsWith('file://') ? Uri.parse(url).toFilePath() : url;

  Future<Directory> _dir([String? sub]) async {
    final root = await _rootDir();
    final dir = Directory(sub == null
        ? p.join(root.path, _dirName)
        : p.join(root.path, _dirName, sub));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return dir;
  }

  /// Writes a new thumbnail of [productId] taken on this device; returns its path.
  Future<String> savePending(String productId, Uint8List bytes) async {
    final dir = await _dir();
    final file = File(p.join(
        dir.path, '${productId}_${DateTime.now().microsecondsSinceEpoch}.jpg'));
    await file.writeAsBytes(bytes, flush: true);
    return file.path;
  }

  /// Local copy of the server photo [url].
  Future<File> cacheFileFor(String url) async {
    final dir = await _dir(_cacheDirName);
    final key = url
        .replaceFirst(RegExp(r'^https?://[^/]+'), '')
        .replaceAll(RegExp(r'[^A-Za-z0-9]+'), '_');
    return File(p.join(dir.path, '$key.jpg'));
  }

  /// After an upload: the device thumbnail becomes the local copy of [url]
  /// (no download needed on this device).
  Future<void> adoptAsCache(String localPath, String url) async {
    final source = File(localPath);
    if (!source.existsSync()) return;
    final target = await cacheFileFor(url);
    try {
      await source.rename(target.path);
    } on FileSystemException {
      await source.copy(target.path);
      await source.delete();
    }
  }

  /// Deletes a device file, ignoring a missing one.
  Future<void> deleteQuietly(String? path) async {
    if (path == null || !isLocalPath(path)) return;
    try {
      final f = File(localFilePath(path));
      if (f.existsSync()) await f.delete();
    } on FileSystemException {
      // best effort
    }
  }
}

import 'dart:io';

/// Best effort: removes exports older than 24 h under [exportsDir] (files
/// are sensitive and the server keeps them 24 h too), except [keep]. Errors
/// are ignored. Shared by the server download (v1s-18-4) and the on-device
/// export (v1s-18-5).
Future<void> purgeOldExports(Directory exportsDir,
    {required String keep}) async {
  final limit = DateTime.now().subtract(const Duration(hours: 24));
  try {
    await for (final entry in exportsDir.list(followLinks: false)) {
      if (entry.path == keep) continue;
      try {
        final modified = (await entry.stat()).modified;
        if (modified.isBefore(limit)) await entry.delete(recursive: true);
      } catch (_) {}
    }
  } catch (_) {}
}

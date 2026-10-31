import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Story 15.4c — ports: what a module package needs from the app but cannot
/// import (a module depends on `keevo_core` only). Each one throws until the
/// app overrides it; the app wires them in `buildRootScope`
/// (`keevo/app/lib/root_scope.dart`), so production and tests that use the
/// root scope share the same composition.

UnimplementedError _missingPort(String name) => UnimplementedError(
  'Port keevo_core "$name" not provided: the app must override it '
  '(buildRootScope, keevo/app/lib/root_scope.dart) or the test must.',
);

/// The authenticated API client of the app (base URL, auth interceptor,
/// token refresh) — the app's `dioProvider`.
final apiDioProvider = Provider<Dio>(
  (ref) => throw _missingPort('apiDioProvider'),
);

/// Hands a downloaded file to the system share / open sheet.
typedef FileSharer = Future<void> Function(File file);

/// The app's file share path (the export feature's sharer).
final fileSharerProvider = Provider<FileSharer>(
  (ref) => throw _missingPort('fileSharerProvider'),
);

/// The app's temporary directory (where downloaded files are written before
/// being shared).
final tempDirProvider = FutureProvider<Directory>(
  (ref) => throw _missingPort('tempDirProvider'),
);

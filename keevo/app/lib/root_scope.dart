import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// The app's root [ProviderScope] (built by `main`).
///
/// Story 15.4b — Riverpod 3 retries failing providers by default; Keevo turns
/// that off app-wide ([noProviderRetry]): several providers rely on "a failure
/// is not cached, the next read asks again" and on showing the error at once.
ProviderScope buildRootScope({
  required List<Override> overrides,
  required Widget child,
}) =>
    ProviderScope(retry: noProviderRetry, overrides: overrides, child: child);

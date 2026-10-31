import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/state/provider_retry.dart';

import 'features/auth/presentation/provider/auth_provider.dart';
import 'features/export/presentation/provider/export_providers.dart';

/// Story 15.4c — the app's implementations of the `keevo_core` ports that
/// module packages (keevo_invoicing, …) read instead of importing the app:
/// API client, file sharer, temp directory. Each follows the app provider,
/// so overriding `dioProvider` / `exportFileSharerProvider` /
/// `exportTempDirProvider` (tests) also reaches the modules.
final List<Override> modulePortOverrides = [
  apiDioProvider.overrideWith((ref) => ref.watch(dioProvider)),
  fileSharerProvider.overrideWith((ref) => ref.watch(exportFileSharerProvider)),
  tempDirProvider.overrideWith(
    (ref) => ref.watch(exportTempDirProvider.future),
  ),
];

/// The app's root [ProviderScope] (built by `main`).
///
/// Story 15.4b — Riverpod 3 retries failing providers by default; Keevo turns
/// that off app-wide ([noProviderRetry]): several providers rely on "a failure
/// is not cached, the next read asks again" and on showing the error at once.
///
/// Story 15.4c — the module ports are wired here ([modulePortOverrides]).
/// A port the caller overrides itself (e.g. a test) keeps only the caller's
/// override: Riverpod refuses the same provider overridden twice.
ProviderScope buildRootScope({
  required List<Override> overrides,
  required Widget child,
}) {
  // `origin` (the overridden provider) is the only public way to tell which
  // provider an Override targets.
  // ignore: invalid_use_of_visible_for_testing_member
  final overridden = {for (final o in overrides) o.origin};
  return ProviderScope(
    retry: noProviderRetry,
    overrides: [
      for (final port in modulePortOverrides)
        // ignore: invalid_use_of_visible_for_testing_member
        if (!overridden.contains(port.origin)) port,
      ...overrides,
    ],
    child: child,
  );
}

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';

/// 13.10b — minimal view of the tenant's module states, read from
/// `GET /api/v1/tenants/me/modules` (owner only) once per session and cached.
/// The full entitlements provider (module list, badges, hidden entry points)
/// is Story 15.1.
class TenantModuleStates {
  /// Module id → `ENABLED` | `READ_ONLY` | `DISABLED`.
  final Map<String, String> states;

  const TenantModuleStates(this.states);

  /// No module known (employee session, nothing fetched).
  static const none = TenantModuleStates({});

  /// True when the module's screens can be read: ENABLED, or READ_ONLY during
  /// the grace period (reads still pass the server gate).
  bool isReadable(String moduleId) {
    final state = states[moduleId];
    return state == 'ENABLED' || state == 'READ_ONLY';
  }
}

/// Module id of the multi-store stock overview (`/stock/overview`).
const kMultiStoreModuleId = 'multi-store';

/// The tenant's module states. An employee session reads nothing (the
/// endpoint is owner-only) and gets [TenantModuleStates.none]. A failure is
/// not cached: callers invalidate the provider and fall back to the free path.
///
/// Reset on sign-out / new sign-in ([SignOutService], session claims).
final tenantModuleStatesProvider =
    FutureProvider<TenantModuleStates>((ref) async {
  if (!ref.watch(isOwnerSessionProvider)) return TenantModuleStates.none;
  final resp = await ref
      .read(dioProvider)
      .get<Map<String, dynamic>>('/api/v1/tenants/me/modules');
  final data = resp.data?['data'];
  final states = <String, String>{};
  if (data is List) {
    for (final item in data) {
      if (item is Map) {
        final id = item['moduleId'];
        final state = item['state'];
        if (id is String && state is String) states[id] = state;
      }
    }
  }
  return TenantModuleStates(states);
});

/// True when the multi-store stock overview is available to this session
/// (owner whose `multi-store` module is readable). Any failure, an offline
/// device or a slow answer (> [timeout]) → false: the caller opens the free
/// local list, and the next call asks the server again.
Future<bool> isMultiStoreOverviewAvailable(
  ProviderContainer container, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  try {
    final modules = await container
        .read(tenantModuleStatesProvider.future)
        .timeout(timeout);
    return modules.isReadable(kMultiStoreModuleId);
  } on TimeoutException {
    // Still loading: the answer is cached for the next tap.
    return false;
  } catch (_) {
    // Failed (offline, server error): not cached, asked again next time.
    container.invalidate(tenantModuleStatesProvider);
    return false;
  }
}

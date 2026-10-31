import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/core_providers.dart';
import '../storage/app_constants.dart';
import 'sync_gate_state.dart';

/// Clock used by [daysSinceLastSyncProvider] (overridable in tests).
final syncGateClockProvider = Provider<DateTime Function()>((_) => DateTime.now);

/// Days since the last successful sync cycle (push + pull completed).
///
/// Reads [kLastSyncAtKey] from SharedPreferences synchronously.
/// Returns 0 if the key is absent (first install — gate is open by default).
///
/// v1s-16-13: clamped to ≥ 0 (a last sync "in the future" after a clock
/// change), and re-evaluated at the next 24 h boundary so an app left open
/// moves through the gate states without waiting for a sync. The sync
/// trigger still invalidates it after every successful cycle.
final daysSinceLastSyncProvider = Provider<int>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  final now = ref.watch(syncGateClockProvider)();
  final ms = prefs.getInt(kLastSyncAtKey);
  if (ms == null) return 0; // never synced → open gate (first install)
  final lastSync = DateTime.fromMillisecondsSinceEpoch(ms);
  final elapsed = now.difference(lastSync);
  final days = elapsed.isNegative ? 0 : elapsed.inDays;

  final nextBoundary = Duration(days: days + 1) - elapsed;
  final timer = Timer(nextBoundary, ref.invalidateSelf);
  ref.onDispose(timer.cancel);
  return days;
});

/// Current gate state derived from [daysSinceLastSyncProvider].
///
/// Observer pattern: watched by SyncIndicator, OfflineGateBanner,
/// and write notifiers (POS, stock, transfer, catalog).
final syncGateStateProvider = Provider<SyncGateState>((ref) {
  final days = ref.watch(daysSinceLastSyncProvider);
  return SyncGateState.fromDaysSinceLastSync(days);
});

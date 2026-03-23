import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/providers.dart';
import '../storage/app_constants.dart';
import 'sync_gate_state.dart';

/// Days since the last successful sync cycle (push + pull completed).
///
/// Reads [kLastSyncAtKey] from SharedPreferences synchronously.
/// Returns 0 if the key is absent (first install — gate is open by default).
final daysSinceLastSyncProvider = Provider<int>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  final ms = prefs.getInt(kLastSyncAtKey);
  if (ms == null) return 0; // never synced → open gate (first install)
  final lastSync = DateTime.fromMillisecondsSinceEpoch(ms);
  return DateTime.now().difference(lastSync).inDays;
});

/// Current gate state derived from [daysSinceLastSyncProvider].
///
/// Observer pattern: watched by SyncIndicator, OfflineGateBanner,
/// and write notifiers (POS, stock, transfer, catalog).
final syncGateStateProvider = Provider<SyncGateState>((ref) {
  final days = ref.watch(daysSinceLastSyncProvider);
  return SyncGateState.fromDaysSinceLastSync(days);
});

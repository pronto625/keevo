import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../di/providers.dart';
import '../storage/app_constants.dart';
import 'sync_status.dart';

// sharedPreferencesProvider is defined in core/di/providers.dart and
// overridden in main.dart. Import it from there — do NOT redeclare here.

/// Raw connectivity stream — List<ConnectivityResult> in connectivity_plus v6+.
///
/// Observer pattern: SyncStatusProvider watches this stream and rebuilds
/// on every network state change. The current state is emitted first:
/// `onConnectivityChanged` only fires on a change, so without it the sync
/// indicator stayed on « Vérification… » for the whole session (device test).
final connectivityStreamProvider =
    StreamProvider<List<ConnectivityResult>>((ref) async* {
  final connectivity = Connectivity();
  try {
    yield await connectivity.checkConnectivity();
  } catch (_) {
    // Unknown until the first change event.
  }
  yield* connectivity.onConnectivityChanged;
});

/// First offline date read from SharedPreferences (production) or overridden
/// directly in tests via [ProviderContainer.overrides].
///
/// Returns null if the device has never gone offline (or came back online).
/// In production, reads [kFirstOfflineDateKey] from the pre-initialized
/// SharedPreferences instance provided by [sharedPreferencesProvider].
final firstOfflineDateProvider = Provider<DateTime?>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  final ms = prefs.getInt(kFirstOfflineDateKey);
  if (ms == null) return null;
  return DateTime.fromMillisecondsSinceEpoch(ms);
});

/// Days offline counter — derived from [firstOfflineDateProvider].
///
/// Returns 0 when online (firstOfflineDate is null).
final daysOfflineProvider = Provider<int>((ref) {
  final firstOfflineDate = ref.watch(firstOfflineDateProvider);
  if (firstOfflineDate == null) return 0;
  return DateTime.now().difference(firstOfflineDate).inDays;
});

/// Primary sync status provider — watches connectivity stream.
///
/// Strategy pattern: SyncService is injected separately; this provider
/// only determines the DISPLAY status based on connectivity + offline duration.
///
/// Connectivity_plus v6+ note: emits List<ConnectivityResult> not a single value.
/// Online = list contains wifi | mobile | ethernet.
final syncStatusProvider = StreamProvider<SyncStatus>((ref) async* {
  // Watch the latest connectivity value (not `.stream`): the connectivity
  // provider is often already listened to (sync trigger), and `.stream` only
  // replays future events — the initial state was missed and the indicator
  // stayed on « Vérification… » (device test). Each change rebuilds this
  // provider, which keeps showing the previous status meanwhile.
  final results = await ref.watch(connectivityStreamProvider.future);
  if (_isConnected(results)) {
    yield SyncStatus.online;
    return;
  }
  // Device is offline — compute days offline from firstOfflineDateProvider
  final firstOfflineDate = ref.read(firstOfflineDateProvider);
  final int days = firstOfflineDate == null
      ? 0 // First moment of going offline — same day
      : DateTime.now().difference(firstOfflineDate).inDays;
  yield SyncStatus.fromDaysOffline(days == 0 ? 1 : days);
});

/// Returns true if at least one result indicates network connectivity.
///
/// Extracted as a pure static helper for testability.
bool _isConnected(List<ConnectivityResult> results) => results.any(
      (r) =>
          r == ConnectivityResult.mobile ||
          r == ConnectivityResult.wifi ||
          r == ConnectivityResult.ethernet,
    );

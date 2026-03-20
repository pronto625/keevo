import 'dart:async';
import 'dart:math';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/catalog/presentation/provider/stock_provider.dart';
import '../../features/inventory/presentation/provider/global_stock_provider.dart';
import '../di/providers.dart';
import 'sync_status_provider.dart';

part 'sync_trigger_notifier.g.dart';

// ── State ─────────────────────────────────────────────────────────────────────

sealed class SyncTriggerState {
  const SyncTriggerState();
  const factory SyncTriggerState.idle() = SyncTriggerIdle;
  const factory SyncTriggerState.syncing() = SyncTriggerSyncing;
  const factory SyncTriggerState.criticalFailure({required Duration duration}) =
      SyncTriggerCriticalFailure;
}

class SyncTriggerIdle extends SyncTriggerState {
  const SyncTriggerIdle();
}

class SyncTriggerSyncing extends SyncTriggerState {
  const SyncTriggerSyncing();
}

class SyncTriggerCriticalFailure extends SyncTriggerState {
  final Duration duration;
  const SyncTriggerCriticalFailure({required this.duration});
}

// ── Notifier ──────────────────────────────────────────────────────────────────

@Riverpod(keepAlive: true)
class SyncTriggerNotifier extends _$SyncTriggerNotifier {
  Timer? _retryTimer;
  int _consecutiveFailures = 0;
  DateTime? _firstFailureAt;

  @override
  SyncTriggerState build() {
    ref.listen(connectivityStreamProvider, (prev, next) {
      next.whenData((results) {
        final isOnline = results.any((r) =>
            r == ConnectivityResult.mobile ||
            r == ConnectivityResult.wifi ||
            r == ConnectivityResult.ethernet);
        if (isOnline) _onConnectivityRestored();
      });
    });

    ref.onDispose(() {
      _retryTimer?.cancel();
    });

    return const SyncTriggerState.idle();
  }

  void _onConnectivityRestored() {
    // Debounce 3 seconds
    _retryTimer?.cancel();
    _retryTimer = Timer(const Duration(seconds: 3), () {
      triggerPush();
    });
  }

  Future<void> triggerPush() async {
    state = const SyncTriggerState.syncing();
    try {
      final syncService = ref.read(syncServiceProvider);
      await syncService.push();
      _consecutiveFailures = 0;
      _firstFailureAt = null;
      // After successful push, sync_queue is empty — invalidate data providers
      // so they re-fetch now-correct backend data (guards will pass through).
      ref.invalidate(stockNotifierProvider);
      ref.invalidate(productListForPickerProvider);
      ref.invalidate(productListProvider);
      ref.invalidate(globalStockOverviewProvider);
      ref.invalidate(storeStockDetailProvider);
      state = const SyncTriggerState.idle();
    } catch (_) {
      _consecutiveFailures++;
      _firstFailureAt ??= DateTime.now();
      _scheduleRetry();
    }
  }

  void _scheduleRetry() {
    final delayMs = min(
      (pow(2, _consecutiveFailures) * 1000).toInt(),
      300000, // max 5 min
    );
    // Add ±20% jitter
    final jitter = (delayMs * 0.2 * (Random().nextDouble() * 2 - 1)).toInt();
    _retryTimer = Timer(Duration(milliseconds: delayMs + jitter), triggerPush);

    // Check critical failure threshold
    if (_consecutiveFailures >= 10 && _firstFailureAt != null) {
      final duration = DateTime.now().difference(_firstFailureAt!);
      if (duration.inHours >= 24) {
        state = SyncTriggerState.criticalFailure(duration: duration);
      }
    }
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_provider.dart';
import '../../../../core/sync/sync_gate_state.dart';
import '../../../../core/theme/app_theme.dart';

const _kOfflineGateDismissedKey = 'offline_gate_banner_dismissed_date';

String _todayKey() {
  final now = DateTime.now();
  return '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
}

/// Whether the day-5 warning banner was dismissed today (v1s-16-13: shared
/// with [ShellBanners], which must know it to pick the next banner).
class OfflineGateDismissedToday extends Notifier<bool> {
  @override
  bool build() {
    final prefs = ref.watch(sharedPreferencesProvider);
    return prefs.getString(_kOfflineGateDismissedKey) == _todayKey();
  }

  Future<void> dismiss() async {
    state = true;
    await ref
        .read(sharedPreferencesProvider)
        .setString(_kOfflineGateDismissedKey, _todayKey());
  }
}

final offlineGateDismissedTodayProvider =
    NotifierProvider<OfflineGateDismissedToday, bool>(
        OfflineGateDismissedToday.new);

/// OfflineGateBanner — persistent banner shown in MainShell when gate is warning or critical.
///
/// - [SyncGateState.warning] (day 5): amber banner, dismissable once per day.
/// - [SyncGateState.critical] (day 6): red banner, NOT dismissable.
/// - [SyncGateState.open] or [SyncGateState.blocked]: nothing rendered.
class OfflineGateBanner extends ConsumerWidget {
  const OfflineGateBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final gate = ref.watch(syncGateStateProvider);
    final days = ref.watch(daysSinceLastSyncProvider);

    if (!gate.showsBanner) return const SizedBox.shrink();

    // Warning state is dismissable; critical is not
    final isCritical = gate == SyncGateState.critical;

    if (!isCritical && ref.watch(offlineGateDismissedTodayProvider)) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    final backgroundColor =
        isCritical ? cs.errorContainer_ : cs.warningContainer;
    // v1s-16-3 : paire conteneur / on-conteneur (AA en clair et en sombre).
    final textColor =
        isCritical ? cs.onErrorContainer_ : cs.onWarningContainer;
    final iconColor = textColor;
    final message = isCritical
        ? 'Dernière chance : synchronisez demain ou l\'accès sera limité (Jour $days/7)'
        : 'Synchronisation requise dans ${7 - days} jour${(7 - days) > 1 ? 's' : ''} (Jour $days/7)';

    return Material(
      color: Colors.transparent,
      child: Container(
        width: double.infinity,
        color: backgroundColor,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(
              isCritical
                  ? Icons.error_outline_rounded
                  : Icons.warning_amber_rounded,
              size: 18,
              color: iconColor,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                message,
                style: TextStyle(
                  color: textColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            if (!isCritical)
              GestureDetector(
                onTap: () => ref
                    .read(offlineGateDismissedTodayProvider.notifier)
                    .dismiss(),
                child: Icon(Icons.close, size: 16, color: textColor),
              ),
          ],
        ),
      ),
    );
  }
}

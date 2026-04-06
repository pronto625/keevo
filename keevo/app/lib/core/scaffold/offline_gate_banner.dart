import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_gate_provider.dart';
import '../../../../core/sync/sync_gate_state.dart';

/// OfflineGateBanner — persistent banner shown in MainShell when gate is warning or critical.
///
/// - [SyncGateState.warning] (day 5): amber banner, dismissable once per day.
/// - [SyncGateState.critical] (day 6): red banner, NOT dismissable.
/// - [SyncGateState.open] or [SyncGateState.blocked]: nothing rendered.
class OfflineGateBanner extends ConsumerStatefulWidget {
  const OfflineGateBanner({super.key});

  @override
  ConsumerState<OfflineGateBanner> createState() => _OfflineGateBannerState();
}

class _OfflineGateBannerState extends ConsumerState<OfflineGateBanner> {
  static const _dismissedKey = 'offline_gate_banner_dismissed_date';
  bool _dismissedToday = false;

  @override
  void initState() {
    super.initState();
    _restoreDismissState();
  }

  void _restoreDismissState() {
    final prefs = ref.read(sharedPreferencesProvider);
    final dismissed = prefs.getString(_dismissedKey);
    if (dismissed == null) return;
    final today = _today();
    if (dismissed == today) {
      setState(() => _dismissedToday = true);
    }
  }

  String _today() {
    final now = DateTime.now();
    return '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
  }

  Future<void> _dismiss() async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setString(_dismissedKey, _today());
    setState(() => _dismissedToday = true);
  }

  @override
  Widget build(BuildContext context) {
    final gate = ref.watch(syncGateStateProvider);
    final days = ref.watch(daysSinceLastSyncProvider);

    if (!gate.showsBanner) return const SizedBox.shrink();

    // Warning state is dismissable; critical is not
    final isCritical = gate == SyncGateState.critical;

    if (!isCritical && _dismissedToday) return const SizedBox.shrink();

    final backgroundColor =
        isCritical ? const Color(0xFFFFE3E3) : const Color(0xFFFFF3BF);
    final textColor = isCritical ? const Color(0xFFFA5252) : Colors.black87;
    final message = isCritical
        ? '🔴 Dernière chance : synchronisez demain ou l\'accès sera limité (Jour $days/7)'
        : '⚠ Synchronisation requise dans ${7 - days} jour${(7 - days) > 1 ? 's' : ''} (Jour $days/7)';

    return Material(
      color: Colors.transparent,
      child: Container(
        width: double.infinity,
        color: backgroundColor,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
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
                onTap: _dismiss,
                child: Icon(Icons.close, size: 16, color: textColor),
              ),
          ],
        ),
      ),
    );
  }
}

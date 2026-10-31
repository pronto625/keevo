import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/scaffold/offline_gate_banner.dart';
import 'package:keevo_core/sync/sync_gate_provider.dart';
import 'package:keevo_core/sync/sync_gate_state.dart';

import '../../features/account/presentation/provider/account_deletion_provider.dart';
import '../../features/account/presentation/widget/deletion_pending_banner.dart';
import '../../features/settings/presentation/provider/account_status_provider.dart';
import '../../features/settings/presentation/widget/suspension_banner.dart';
import '../../features/sync_indicator/presentation/widget/sync_warning_banner.dart';
import '../sync/sync_trigger_notifier.dart';

/// The banners [MainShell] can show above the page, highest priority first
/// (v1s-16-13). Only one shows at a time so the content stays visible on
/// small screens; clearing a condition reveals the next one.
enum ShellBanner {
  suspended,
  deletionPending,
  offlineGateCritical,
  syncCriticalFailure,
  offlineGateWarning,
  trialExpired,
}

/// Picks the banner to show: account-blocking states first, then the
/// time-bound deletion (it has an action), then the offline-gate severity;
/// trial-expired is informational. Null when nothing applies.
ShellBanner? pickShellBanner({
  required AccountStatus accountStatus,
  required bool deletionPending,
  required SyncGateState gate,
  required bool syncCriticalFailure,
  required bool offlineWarningDismissed,
}) {
  if (accountStatus == AccountStatus.suspended) return ShellBanner.suspended;
  if (deletionPending) return ShellBanner.deletionPending;
  if (gate == SyncGateState.critical) return ShellBanner.offlineGateCritical;
  if (syncCriticalFailure) return ShellBanner.syncCriticalFailure;
  if (gate == SyncGateState.warning && !offlineWarningDismissed) {
    return ShellBanner.offlineGateWarning;
  }
  if (accountStatus == AccountStatus.trialExpired) {
    return ShellBanner.trialExpired;
  }
  return null;
}

final shellBannerProvider = Provider<ShellBanner?>((ref) => pickShellBanner(
      accountStatus: ref.watch(accountStatusProvider),
      deletionPending:
          ref.watch(deletionPendingStatusProvider).value != null,
      gate: ref.watch(syncGateStateProvider),
      syncCriticalFailure:
          ref.watch(syncTriggerNotifierProvider) is SyncTriggerCriticalFailure,
      offlineWarningDismissed: ref.watch(offlineGateDismissedTodayProvider),
    ));

/// The single banner slot at the top of [MainShell].
class ShellBanners extends ConsumerWidget {
  const ShellBanners({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return switch (ref.watch(shellBannerProvider)) {
      ShellBanner.suspended || ShellBanner.trialExpired =>
        const SuspensionBanner(),
      ShellBanner.deletionPending => const DeletionPendingBanner(),
      ShellBanner.offlineGateCritical ||
      ShellBanner.offlineGateWarning =>
        const OfflineGateBanner(),
      ShellBanner.syncCriticalFailure => const SyncWarningBanner(),
      null => const SizedBox.shrink(),
    };
  }
}

/// Shell body: the banner slot above [child]. A banner sits at the top of the
/// screen, so it keeps clear of the status bar itself (H2: the suspension text
/// was drawn under the clock) and the page below no longer pads for it twice.
class ShellBannerLayout extends ConsumerWidget {
  const ShellBannerLayout({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(shellBannerProvider) == null) return child;
    return Column(
      children: [
        const SafeArea(bottom: false, child: ShellBanners()),
        Expanded(
          child: MediaQuery.removePadding(
              context: context, removeTop: true, child: child),
        ),
      ],
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_trigger_notifier.dart';

/// SyncWarningBanner — Amber persistent banner shown when sync has failed
/// critically (>=10 failures over >=24h).
///
/// Observer pattern: watches [syncTriggerNotifierProvider] state.
/// Shows a "Réessayer" action that calls [SyncTriggerNotifier.triggerPush()].
class SyncWarningBanner extends ConsumerWidget {
  const SyncWarningBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final syncState = ref.watch(syncTriggerNotifierProvider);

    if (syncState is! SyncTriggerCriticalFailure) {
      return const SizedBox.shrink();
    }

    final hours = syncState.duration.inHours;
    final durationLabel = hours >= 24
        ? '${(hours / 24).floor()} jour(s)'
        : '$hours heure(s)';

    return MaterialBanner(
      backgroundColor: Colors.amber.shade100,
      content: Text(
        '⚠ Synchronisation en échec depuis $durationLabel. '
        'Vérifiez votre connexion.',
      ),
      actions: [
        TextButton(
          onPressed: () {
            ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
          },
          child: const Text('Réessayer'),
        ),
      ],
    );
  }
}

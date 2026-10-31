import 'package:keevo_core/theme/app_theme.dart';
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

    // Fond ambre fixe quel que soit le thème : texte/icône en `onWarning`
    // pour rester lisibles en mode sombre.
    return MaterialBanner(
      backgroundColor: AppTheme.warning,
      leading: const Icon(Icons.warning_amber_rounded, color: AppTheme.onWarning),
      content: Text(
        'Synchronisation en échec depuis $durationLabel. '
        'Vérifiez votre connexion.',
        style: const TextStyle(color: AppTheme.onWarning),
      ),
      actions: [
        TextButton(
          style: TextButton.styleFrom(foregroundColor: AppTheme.onWarning),
          onPressed: () {
            ref.read(syncTriggerNotifierProvider.notifier).triggerPush();
          },
          child: const Text('Réessayer'),
        ),
      ],
    );
  }
}

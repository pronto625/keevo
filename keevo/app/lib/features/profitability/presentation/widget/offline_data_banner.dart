import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// OfflineDataBanner — discreet banner shown by the report rankings
/// (Boutiques, Équipe) when the data comes from the local Drift database.
class OfflineDataBanner extends StatelessWidget {
  const OfflineDataBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Fond ambre dilué : sur l'ancien fond ambre plein, l'icône et le texte
    // (eux aussi ambre) étaient invisibles.
    final accent =
        cs.brightness == Brightness.dark ? cs.warning : AppTheme.onWarning;
    return ColoredBox(
      color: cs.warningContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.cloud_off_outlined, size: 16, color: accent),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Mode hors ligne — données de la dernière synchronisation affichées',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: accent,
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

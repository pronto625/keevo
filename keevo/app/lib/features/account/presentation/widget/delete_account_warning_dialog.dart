import 'package:flutter/material.dart';

import 'package:keevo_core/theme/app_theme.dart';

/// Step 1 of the FR91 flow (Story 14.5, AC7): irreversible-action warning.
///
/// Resolves to `true` when the owner taps "Continuer". Holds no controller,
/// so a plain dialog builder is fine here; the phone confirmation (step 2)
/// lives in [DeleteAccountPage], a stateful page with its own `dispose()`.
Future<bool> showDeleteAccountWarningDialog(BuildContext context) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (ctx) => const _DeleteAccountWarningDialog(),
  );
  return confirmed ?? false;
}

/// Everything the purge removes — listed in the dialog and on the page.
const deletedDataItems = <String>[
  'Votre boutique et ses paramètres',
  'Toutes vos ventes et clôtures',
  'Votre stock et vos mouvements',
  'Vos employés et leurs accès',
  'Vos rapports et votre historique',
];

class _DeleteAccountWarningDialog extends StatelessWidget {
  const _DeleteAccountWarningDialog();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Supprimer mon compte ?'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Les données suivantes seront définitivement supprimées dans '
            '30 jours :',
            style: TextStyle(fontSize: 14, color: cs.onSurface),
          ),
          const SizedBox(height: 12),
          for (final item in deletedDataItems)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.remove_circle_outline_rounded,
                      size: 16, color: cs.error),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(item,
                        style: TextStyle(fontSize: 13, color: cs.onSurface)),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 8),
          Text(
            'Vous pouvez annuler d\'ici là depuis Paramètres > Mon Compte. '
            'Pendant ce délai, votre compte passe en consultation uniquement.',
            style: TextStyle(fontSize: 13, color: cs.muted),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Annuler'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, true),
          style: TextButton.styleFrom(foregroundColor: AppTheme.errorColor),
          child: const Text('Continuer'),
        ),
      ],
    );
  }
}

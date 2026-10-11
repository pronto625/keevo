import 'package:flutter/material.dart';

/// Asks before a different account signs in on a device that still holds the
/// previous account's unsynced operations: going on deletes them.
///
/// Returns true when the user accepts losing them.
Future<bool> confirmDiscardPreviousAccountData(
  BuildContext context,
  int pendingOperations,
) async {
  final plural = pendingOperations > 1;
  final accepted = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      key: const Key('previousAccountDataDialog'),
      title: const Text('Opérations non synchronisées'),
      content: Text(
        '$pendingOperations opération${plural ? 's' : ''} de l\'ancien compte '
        '${plural ? 'seront perdues' : 'sera perdue'}. Elles n\'ont pas été '
        'envoyées au serveur. Pour les garder, annulez et reconnectez-vous '
        'avec l\'ancien compte.',
      ),
      actions: [
        TextButton(
          key: const Key('keepPreviousAccountData'),
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Annuler'),
        ),
        TextButton(
          key: const Key('discardPreviousAccountData'),
          onPressed: () => Navigator.of(context).pop(true),
          style: TextButton.styleFrom(
            foregroundColor: Theme.of(context).colorScheme.error,
          ),
          child: const Text('Continuer'),
        ),
      ],
    ),
  );
  return accepted ?? false;
}

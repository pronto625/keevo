import 'package:flutter/material.dart';

/// Standard « Abandonner les modifications ? » confirmation, shown when the
/// user leaves a form holding unsaved edits. Resolves to `true` only when
/// the user chooses to discard.
Future<bool> showDiscardChangesDialog(BuildContext context) async {
  final discard = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Abandonner les modifications ?'),
      content: const Text('Les modifications non enregistrées seront perdues.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Continuer'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('Abandonner'),
        ),
      ],
    ),
  );
  return discard ?? false;
}

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/device_session.dart';

/// « Dernière activité le 01/11/2026 à 14:05 » (Douala time, UTC+1).
String deviceLastUsedLabel(DateTime? lastUsedAt) {
  if (lastUsedAt == null) return 'Dernière activité inconnue';
  final wat = lastUsedAt.toUtc().add(const Duration(hours: 1));
  return 'Dernière activité le ${DateFormat('dd/MM/yyyy').format(wat)} '
      'à ${DateFormat('HH:mm').format(wat)}';
}

/// One device row: label, last activity, « Cet appareil » badge or a
/// « Déconnecter » button.
class DeviceSessionTile extends StatelessWidget {
  final DeviceSession session;
  final VoidCallback? onDisconnect;

  const DeviceSessionTile({super.key, required this.session, this.onDisconnect});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isPhone = !session.label.toLowerCase().contains('windows') &&
        !session.label.toLowerCase().contains('linux') &&
        !session.label.toLowerCase().contains('mac');
    return ListTile(
      leading: Icon(
        isPhone ? Icons.smartphone_rounded : Icons.laptop_rounded,
        color: cs.primary,
      ),
      title: Text(session.label, maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: Text(session.current
          ? 'Cet appareil · ${deviceLastUsedLabel(session.lastUsedAt)}'
          : deviceLastUsedLabel(session.lastUsedAt)),
      trailing: session.current || onDisconnect == null
          ? null
          : TextButton(
              onPressed: onDisconnect,
              style: TextButton.styleFrom(foregroundColor: cs.error),
              child: const Text('Déconnecter'),
            ),
    );
  }
}

/// Asks before closing a device session. True when confirmed.
Future<bool> confirmDisconnect(BuildContext context, {required String label}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Déconnecter cet appareil ?'),
      content: Text('« $label » devra se reconnecter avec le mot de passe.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(ctx).pop(true),
          child: const Text('Déconnecter'),
        ),
      ],
    ),
  );
  return ok ?? false;
}

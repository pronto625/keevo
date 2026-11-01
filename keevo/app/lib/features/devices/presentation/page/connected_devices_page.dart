import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/widget/app_error_widget.dart';

import '../provider/device_sessions_provider.dart';
import '../widget/device_session_tile.dart';

/// Story 16.11 — « Appareils connectés » (Paramètres > Compte, every role):
/// the devices signed in to my account, this one first. « Déconnecter »
/// closes a device's session: its next request asks for the password again.
/// Needs the network (the list lives on the server).
class ConnectedDevicesPage extends ConsumerWidget {
  static const title = 'Appareils connectés';

  const ConnectedDevicesPage({super.key});

  Future<void> _closeOne(
      BuildContext context, WidgetRef ref, String sessionId, String label) async {
    if (!await confirmDisconnect(context, label: label)) return;
    try {
      await ref.read(deviceSessionsDataSourceProvider).closeMine(sessionId);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Appareil déconnecté')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(appErrorMessage(e))));
    }
    ref.invalidate(myDeviceSessionsProvider);
  }

  Future<void> _closeOthers(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Déconnecter les autres appareils ?'),
        content: const Text(
            'Tous vos autres appareils devront se reconnecter avec le mot de passe. '
            'Cet appareil reste connecté.'),
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
    if (ok != true) return;
    try {
      await ref.read(deviceSessionsDataSourceProvider).closeMyOthers();
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Toutes les autres sessions fermées')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(appErrorMessage(e))));
    }
    ref.invalidate(myDeviceSessionsProvider);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sessions = ref.watch(myDeviceSessionsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text(title)),
      body: sessions.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(
          error: e,
          title: 'Liste indisponible',
          message: 'Connectez-vous à internet pour voir vos appareils.',
          onRetry: () => ref.invalidate(myDeviceSessionsProvider),
        ),
        data: (list) {
          final others = list.where((s) => !s.current).length;
          return RefreshIndicator(
            onRefresh: () => ref.refresh(myDeviceSessionsProvider.future),
            child: ListView(
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                const Padding(
                  padding: EdgeInsets.fromLTRB(16, 8, 16, 8),
                  child: Text(
                      'Vous restez connecté sur chaque appareil tant que vous '
                      'l\'ouvrez au moins une fois par an. Déconnectez un '
                      'appareil perdu ou que vous n\'utilisez plus.'),
                ),
                for (final s in list)
                  DeviceSessionTile(
                    key: ValueKey(s.sessionId),
                    session: s,
                    onDisconnect: () => _closeOne(context, ref, s.sessionId, s.label),
                  ),
                if (others > 0)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: OutlinedButton.icon(
                      onPressed: () => _closeOthers(context, ref),
                      icon: const Icon(Icons.logout_rounded),
                      label: const Text('Déconnecter tous les autres appareils'),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

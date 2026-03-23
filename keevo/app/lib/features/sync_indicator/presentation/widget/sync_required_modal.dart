import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_gate_provider.dart';
import '../../../../core/sync/sync_gate_state.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../../../../core/sync/sync_status_provider.dart';

/// SyncRequiredModal — blocking dialog shown when [SyncGateState.blocked].
///
/// Opens when a write operation is blocked by [SyncGateGuard.assertWriteAllowed].
/// Stays open until the sync succeeds and [syncGateStateProvider] transitions
/// to [SyncGateState.open].
class SyncRequiredModal extends ConsumerStatefulWidget {
  const SyncRequiredModal({super.key});

  /// Shows the modal and does not return until dismissed.
  static Future<void> show(BuildContext context) async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => const SyncRequiredModal(),
    );
  }

  @override
  ConsumerState<SyncRequiredModal> createState() => _SyncRequiredModalState();
}

class _SyncRequiredModalState extends ConsumerState<SyncRequiredModal> {
  bool _isSyncing = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    // Listen for gate state to transition to open — auto-dismiss modal and show success SnackBar.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.listenManual(syncGateStateProvider, (prev, next) {
        if (next == SyncGateState.open && mounted) {
          final messenger = ScaffoldMessenger.of(context);
          Navigator.of(context, rootNavigator: true).pop();
          messenger.showSnackBar(
            const SnackBar(
              content: Text('✅ Synchronisation réussie — accès complet restauré'),
              backgroundColor: Color(0xFF51CF66),
              duration: Duration(seconds: 4),
            ),
          );
        }
      });
    });
  }

  bool _isOnline(WidgetRef ref) {
    final connectivity = ref.read(connectivityStreamProvider);
    return connectivity.valueOrNull?.any((r) =>
            r == ConnectivityResult.mobile ||
            r == ConnectivityResult.wifi ||
            r == ConnectivityResult.ethernet) ??
        false;
  }

  Future<void> _triggerSync() async {
    setState(() {
      _isSyncing = true;
      _errorMessage = null;
    });
    try {
      await ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
    } catch (e) {
      if (mounted) {
        setState(() {
          _isSyncing = false;
          _errorMessage = 'La synchronisation a échoué. Vérifiez votre connexion.';
        });
      }
      return;
    }
    if (mounted) {
      setState(() => _isSyncing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isOnline = _isOnline(ref);
    ref.watch(connectivityStreamProvider); // rebuild on connectivity change

    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.sync_problem, color: Color(0xFFFA5252)),
          SizedBox(width: 8),
          Text('Synchronisation requise'),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Votre appareil n\'a pas synchronisé depuis plus de 7 jours. '
            'Vous devez synchroniser avant de pouvoir effectuer des opérations d\'écriture.',
          ),
          if (_isSyncing) ...[
            const SizedBox(height: 16),
            const Row(
              children: [
                SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                SizedBox(width: 12),
                Text('Synchronisation en cours...'),
              ],
            ),
          ],
          if (_errorMessage != null) ...[
            const SizedBox(height: 12),
            Text(
              _errorMessage!,
              style: const TextStyle(color: Color(0xFFFA5252), fontSize: 13),
            ),
          ],
          if (!isOnline) ...[
            const SizedBox(height: 12),
            const Row(
              children: [
                Icon(Icons.wifi_off, size: 16, color: Colors.grey),
                SizedBox(width: 6),
                Text(
                  'Hors-ligne — connexion requise',
                  style: TextStyle(color: Colors.grey, fontSize: 12),
                ),
              ],
            ),
          ],
        ],
      ),
      actions: [
        if (!_isSyncing)
          TextButton(
            onPressed: () => Navigator.of(context, rootNavigator: true).pop(),
            child: const Text('Continuer en lecture seule'),
          ),
        FilledButton(
          onPressed: (!isOnline || _isSyncing) ? null : _triggerSync,
          child: Text(
            !isOnline
                ? 'Hors-ligne — connexion requise'
                : _isSyncing
                    ? 'Synchronisation...'
                    : _errorMessage != null
                        ? 'Réessayer'
                        : 'Synchroniser maintenant',
          ),
        ),
      ],
    );
  }
}

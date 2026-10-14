import '../../../../core/theme/app_theme.dart';
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
  bool _closed = false;
  String? _errorMessage;
  ProviderSubscription<SyncGateState>? _gateSub;

  @override
  void initState() {
    super.initState();
    // Gate opens (a sync from anywhere — background pull, this modal) →
    // dismiss the modal with the success message.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _gateSub = ref.listenManual(syncGateStateProvider, (prev, next) {
        if (!next.isWriteBlocked) _closeWithSuccess();
      });
      // J23: a background sync may already have opened the gate before the
      // modal was shown — re-read the last-sync time now.
      _recheckGate();
    });
  }

  @override
  void dispose() {
    _gateSub?.close();
    super.dispose();
  }

  /// Re-reads the last-sync time (it is cached by the gate provider) and
  /// closes the modal when writes are allowed again. Returns true if open.
  bool _recheckGate() {
    ref.invalidate(daysSinceLastSyncProvider);
    final open = !ref.read(syncGateStateProvider).isWriteBlocked;
    if (open) _closeWithSuccess();
    return open;
  }

  void _closeWithSuccess() {
    if (_closed || !mounted) return;
    _closed = true;
    final messenger = ScaffoldMessenger.maybeOf(context);
    Navigator.of(context, rootNavigator: true).pop();
    messenger?.showSnackBar(
      const SnackBar(
        content: Row(
          children: [
            Icon(Icons.check_circle_rounded, color: AppTheme.onBrand, size: 20),
            SizedBox(width: 8),
            Expanded(
              child: Text(
                'Synchronisation réussie — accès complet restauré',
                style: TextStyle(color: AppTheme.onBrand),
              ),
            ),
          ],
        ),
        backgroundColor: AppTheme.successStrong,
        duration: Duration(seconds: 4),
      ),
    );
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
    } catch (_) {
      // triggerSync handles its own failures (retry); the gate decides below.
    }
    if (!mounted) return;
    // J23: the sync cycle reports failures through its retry, not by
    // throwing — the gate itself says whether the sync succeeded.
    if (_recheckGate()) return;
    setState(() {
      _isSyncing = false;
      _errorMessage = 'La synchronisation a échoué. Vérifiez votre connexion.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final isOnline = _isOnline(ref);
    ref.watch(connectivityStreamProvider); // rebuild on connectivity change

    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.sync_problem_rounded, color: AppTheme.errorColor),
          SizedBox(width: 8),
          Expanded(child: Text('Synchronisation requise')),
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
              style: const TextStyle(color: AppTheme.errorColor, fontSize: 13),
            ),
          ],
          if (!isOnline) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(Icons.wifi_off, size: 16, color: Theme.of(context).colorScheme.onSurfaceVariant),
                const SizedBox(width: 6),
                Text(
                  'Hors-ligne — connexion requise',
                  style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 12),
                ),
              ],
            ),
          ],
        ],
      ),
      actions: [
        if (!_isSyncing)
          TextButton(
            onPressed: () {
              _closed = true;
              Navigator.of(context, rootNavigator: true).pop();
            },
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

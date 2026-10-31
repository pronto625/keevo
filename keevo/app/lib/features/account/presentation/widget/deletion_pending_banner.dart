import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import '../provider/account_deletion_provider.dart';
import '../util/deletion_date_format.dart';

/// DeletionPendingBanner — "Suppression programmée le [date] — [N] jours
/// restants. [Annuler la suppression]" (Story 14.5, AC8).
///
/// Renders nothing unless [deletionPendingStatusProvider] says a deletion is
/// pending. Independent of `SuspensionBanner`/`account_status_provider.dart`
/// (Story 14.9). Colors come from the theme (`KeevoColors`), no hard-coded
/// white — same discipline as `OfflineGateBanner`.
class DeletionPendingBanner extends ConsumerStatefulWidget {
  const DeletionPendingBanner({super.key});

  @override
  ConsumerState<DeletionPendingBanner> createState() =>
      _DeletionPendingBannerState();
}

class _DeletionPendingBannerState extends ConsumerState<DeletionPendingBanner> {
  bool _cancelling = false;

  Future<void> _cancel() async {
    setState(() => _cancelling = true);
    final messenger = ScaffoldMessenger.maybeOf(context);
    try {
      await ref.read(accountDeletionRepositoryProvider).cancelDeletion();
      // A50: renew the token so its tenantStatus claim is ACTIVE again —
      // writes (and the queued operations) are allowed from now on.
      await renewSessionAfterAccountChange(ref);
      ref.invalidate(deletionPendingStatusProvider);
      messenger?.showSnackBar(const SnackBar(
        content: Text('Suppression annulée — votre compte est de nouveau actif.'),
      ));
    } catch (e) {
      messenger?.showSnackBar(SnackBar(content: Text(appErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _cancelling = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = ref.watch(deletionPendingStatusProvider).valueOrNull;
    if (status == null) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    // v1s-16-3 : on-conteneur AA (l'ambre #F59F00 sur #FFF9DB était à 2:1).
    final iconColor = cs.onWarningContainer;

    final date = status.scheduledAt;
    final days = status.daysRemaining();
    final message = date == null
        ? 'Suppression de votre compte programmée.'
        : 'Suppression programmée le ${formatDeletionDate(date)} — '
            '$days jour${days == 1 ? '' : 's'} restant${days == 1 ? '' : 's'}.';

    return Material(
      color: Colors.transparent,
      child: Container(
        width: double.infinity,
        color: cs.warningContainer,
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
        child: Row(
          children: [
            Icon(Icons.auto_delete_outlined, size: 18, color: iconColor),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                message,
                style: TextStyle(
                  color: cs.onSurface,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            TextButton(
              onPressed: _cancelling ? null : _cancel,
              style: TextButton.styleFrom(
                foregroundColor: cs.onSurface,
                visualDensity: VisualDensity.compact,
                textStyle: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
              child: _cancelling
                  ? SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: cs.onSurface,
                      ),
                    )
                  : const Text('Annuler la suppression'),
            ),
          ],
        ),
      ),
    );
  }
}

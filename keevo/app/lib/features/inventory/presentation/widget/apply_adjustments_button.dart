import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/router/module_routes.dart';
import '../../domain/model/inventory_gap_report_model.dart';
import '../provider/validate_inventory_provider.dart';
import 'inventory_validation_success_overlay.dart';

/// Après validation d'un inventaire : revient sur « Inventaire » en DÉPILANT
/// les pages du parcours (comptage, rapport d'écarts) au lieu de remplacer
/// toute la pile par `/inventory`. Sinon « Inventaire » devenait la racine de
/// navigation et le retour Android fermait l'application (J1).
///
/// Si `/inventory` n'est pas dans la pile (ouverture directe), on la recrée
/// au-dessus de l'onglet Plus, d'où elle est normalement ouverte.
///
/// Story 15.8 — `/inventory` appartient au module `counting` : quand il ne
/// peut plus être lu ([inventoryAvailable] faux), on revient sur Plus sans
/// rouvrir « Inventaire » (qui mènerait à « module non activé »).
@visibleForTesting
void returnToInventory(GoRouter router, {bool inventoryAvailable = true}) {
  String location() => router.routerDelegate.currentConfiguration.uri.path;
  if (!inventoryAvailable) {
    router.go('/settings');
    return;
  }
  while (location() != '/inventory' && router.canPop()) {
    router.pop();
  }
  if (location() == '/inventory') return;
  router.go('/settings');
  router.push('/inventory');
}

/// ApplyAdjustmentsButton — triggers inventory validation.
///
/// Shows confirmation dialog with N adjustments count and "irréversible" warning.
/// On success, shows InventoryValidationSuccessOverlay then navigates to /inventory.
/// Story 6.4 — AC1.
class ApplyAdjustmentsButton extends ConsumerStatefulWidget {
  final String sessionId;
  final InventoryGapSummaryModel summary;

  const ApplyAdjustmentsButton({
    super.key,
    required this.sessionId,
    required this.summary,
  });

  @override
  ConsumerState<ApplyAdjustmentsButton> createState() =>
      _ApplyAdjustmentsButtonState();
}

class _ApplyAdjustmentsButtonState
    extends ConsumerState<ApplyAdjustmentsButton> {
  bool _isApplying = false;

  int get _totalGaps =>
      widget.summary.totalShortage + widget.summary.totalSurplus;

  bool get _isAllConcordant => _totalGaps == 0;

  @override
  Widget build(BuildContext context) {
    return FilledButton.icon(
      onPressed: _isApplying ? null : () => _showConfirmDialog(context),
      icon: _isApplying
          ? SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            )
          : const Icon(Icons.check_circle_outline),
      label: Text(
        _isApplying
            ? 'Application en cours…'
            : _isAllConcordant
                ? 'Valider l\'inventaire'
                : 'Appliquer les ajustements',
      ),
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(48),
      ),
    );
  }

  Future<void> _showConfirmDialog(BuildContext context) async {
    final Widget dialogContent = _isAllConcordant
        ? Text.rich(
            TextSpan(
              children: [
                const TextSpan(
                    text:
                        'Tous les stocks correspondent. Valider et clôturer cette session ? Cette action est '),
                const TextSpan(
                  text: 'irréversible',
                  style: TextStyle(
                    color: AppTheme.errorColor,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const TextSpan(text: '.'),
              ],
            ),
          )
        : Text.rich(
            TextSpan(
              children: [
                const TextSpan(text: 'Appliquer '),
                TextSpan(
                  text: '$_totalGaps',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                    text: ' ajustements de stock ? Cette action est '),
                const TextSpan(
                  text: 'irréversible',
                  style: TextStyle(
                    color: AppTheme.errorColor,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const TextSpan(text: '.'),
              ],
            ),
          );

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Confirmer les ajustements'),
        content: dialogContent,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Confirmer'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;
    await _applyAdjustments();
  }

  Future<void> _applyAdjustments() async {
    setState(() => _isApplying = true);

    // Capture router before async gap — widget may be disposed after validate
    final router = GoRouter.of(context);

    try {
      final adjustmentsApplied = await ref
          .read(validateInventoryNotifierProvider.notifier)
          .validate(widget.sessionId);

      if (!mounted) return;

      InventoryValidationSuccessOverlay.show(
        context,
        adjustmentsApplied: adjustmentsApplied,
        onComplete: () {
          ref
              .read(validateInventoryNotifierProvider.notifier)
              .invalidateAll();
          // Story 15.8 — read when leaving (the states may have changed
          // while validating).
          returnToInventory(router,
              inventoryAvailable: isRouteAvailableNow(ref, '/inventory'));
        },
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isApplying = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(appErrorMessage(e)),
          backgroundColor: AppTheme.errorStrong,
        ),
      );
    }
  }
}

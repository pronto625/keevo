import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../domain/model/inventory_gap_report_model.dart';
import '../provider/validate_inventory_provider.dart';
import 'inventory_validation_success_overlay.dart';

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

  @override
  Widget build(BuildContext context) {
    return FilledButton.icon(
      onPressed: _isApplying ? null : () => _showConfirmDialog(context),
      icon: _isApplying
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.white,
              ),
            )
          : const Icon(Icons.check_circle_outline),
      label: Text(
        _isApplying ? 'Application en cours…' : 'Appliquer les ajustements',
      ),
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(48),
      ),
    );
  }

  Future<void> _showConfirmDialog(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Confirmer les ajustements'),
        content: Text.rich(
          TextSpan(
            children: [
              const TextSpan(text: 'Appliquer '),
              TextSpan(
                text: '$_totalGaps',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              const TextSpan(text: ' ajustements de stock ? Cette action est '),
              const TextSpan(
                text: 'irréversible',
                style: TextStyle(
                  color: Color(0xFFFA5252),
                  fontWeight: FontWeight.w600,
                ),
              ),
              const TextSpan(text: '.'),
            ],
          ),
        ),
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
          router.go('/inventory');
        },
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isApplying = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Erreur: $e'),
          backgroundColor: const Color(0xFFFA5252),
        ),
      );
    }
  }
}

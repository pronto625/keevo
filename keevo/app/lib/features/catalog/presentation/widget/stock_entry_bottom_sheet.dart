import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../features/sync_indicator/presentation/widget/sync_required_modal.dart';
import '../provider/stock_provider.dart';

/// StockEntryBottomSheet — modal for recording a stock delivery (+quantity).
///
/// Story 2.3.
class StockEntryBottomSheet extends ConsumerStatefulWidget {
  final String productId;
  final String? storeId;
  final String? productName;

  const StockEntryBottomSheet({
    super.key,
    required this.productId,
    this.storeId,
    this.productName,
  });

  static Future<void> show(
    BuildContext context,
    WidgetRef ref, {
    required String productId,
    String? storeId,
    String? productName,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => StockEntryBottomSheet(
        productId: productId,
        storeId: storeId,
        productName: productName,
      ),
    );
  }

  @override
  ConsumerState<StockEntryBottomSheet> createState() =>
      _StockEntryBottomSheetState();
}

class _StockEntryBottomSheetState extends ConsumerState<StockEntryBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  final _qtyController = TextEditingController();
  final _notesController = TextEditingController();
  bool _loading = false;

  @override
  void dispose() {
    _qtyController.dispose();
    _notesController.dispose();
    super.dispose();
  }

  void _increment() {
    final v = int.tryParse(_qtyController.text) ?? 0;
    _qtyController.text = '${v + 1}';
  }

  void _decrement() {
    final v = int.tryParse(_qtyController.text) ?? 0;
    if (v > 1) _qtyController.text = '${v - 1}';
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    final storeId = widget.storeId;
    if (storeId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Impossible de déterminer le dépôt. Réessayez.')),
      );
      return;
    }

    // Store context-dependent refs BEFORE the async gap.
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);

    setState(() => _loading = true);
    final quantity = int.parse(_qtyController.text.trim());
    final notes = _notesController.text.trim().isEmpty
        ? null
        : _notesController.text.trim();

    final success = await ref
        .read(stockNotifierProvider(widget.productId).notifier)
        .recordEntry(
          storeId: storeId,
          quantity: quantity,
          notes: notes,
        );

    if (mounted) {
      setState(() => _loading = false);
      if (success) {
        navigator.pop();
        messenger.showSnackBar(
          SnackBar(
            content: Text('Entrée enregistrée : +$quantity unités'),
            backgroundColor: AppTheme.success,
            duration: const Duration(seconds: 3),
          ),
        );
      } else {
        final blocked = ref.read(stockNotifierProvider(widget.productId)).blockedByGate;
        if (blocked) {
          SyncRequiredModal.show(context);
        } else {
          messenger.showSnackBar(
            const SnackBar(
              content: Text('Erreur lors de l\'enregistrement'),
              backgroundColor: AppTheme.errorColor,
            ),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final bottom = MediaQuery.of(context).viewInsets.bottom;

    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Handle bar ────────────────────────────────────────────────
          const SizedBox(height: 10),
          Container(
            height: 4,
            width: 44,
            decoration: BoxDecoration(
              color: cs.onSurfaceVariant.withOpacity(0.35),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 14),

          // ── Header ───────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(9),
                  decoration: BoxDecoration(
                    color: const Color(0xFFE8F5E9),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child:
                      const Icon(Icons.add_circle_rounded, color: AppTheme.success, size: 22),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Entrée de stock',
                          style: theme.textTheme.titleMedium
                              ?.copyWith(fontWeight: FontWeight.w600)),
                      if (widget.productName != null)
                        Text(
                          widget.productName!,
                          style: theme.textTheme.bodySmall
                              ?.copyWith(color: AppTheme.success),
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(context).pop(),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ),
          const Divider(height: 20),

          // ── Form body ─────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // ── Quantity stepper ──────────────────────────────────
                  Text('Quantité reçue',
                      style: theme.textTheme.labelMedium
                          ?.copyWith(color: cs.onSurfaceVariant)),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      _StepperBtn(icon: Icons.remove, onTap: _decrement),
                      const SizedBox(width: 10),
                      Expanded(
                        child: TextFormField(
                          controller: _qtyController,
                          keyboardType: TextInputType.number,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.headlineSmall
                              ?.copyWith(fontWeight: FontWeight.w700),
                          decoration: const InputDecoration(
                            border: OutlineInputBorder(),
                            hintText: '0',
                            contentPadding:
                                EdgeInsets.symmetric(vertical: 14),
                          ),
                          validator: (v) {
                            if (v == null || v.trim().isEmpty) {
                              return 'Champ requis';
                            }
                            final n = int.tryParse(v.trim());
                            if (n == null || n <= 0) {
                              return 'Quantité positive requise';
                            }
                            return null;
                          },
                        ),
                      ),
                      const SizedBox(width: 10),
                      _StepperBtn(icon: Icons.add, onTap: _increment),
                    ],
                  ),
                  const SizedBox(height: 16),

                  // ── Notes ─────────────────────────────────────────────
                  TextFormField(
                    controller: _notesController,
                    decoration: const InputDecoration(
                      labelText: 'Référence / Notes (optionnel)',
                      hintText: 'ex: BL-2025-001',
                      border: OutlineInputBorder(),
                      prefixIcon: Icon(Icons.note_outlined),
                      contentPadding:
                          EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                    ),
                  ),
                  const SizedBox(height: 24),

                  // ── Submit ────────────────────────────────────────────
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: AppTheme.success,
                      foregroundColor: Colors.white,
                    ),
                    onPressed: _loading ? null : _submit,
                    icon: _loading
                        ? const SizedBox(
                            height: 18,
                            width: 18,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.check_rounded),
                    label: const Text('Enregistrer l\'entrée'),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

class _StepperBtn extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const _StepperBtn({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        width: 50,
        height: 50,
        decoration: BoxDecoration(
          border: Border.all(color: cs.outline),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(icon, size: 22),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../features/sync_indicator/presentation/widget/sync_required_modal.dart';
import '../provider/stock_provider.dart';

/// StockAdjustBottomSheet — modal for adjusting stock to an absolute quantity.
///
/// Used for inventory count corrections.
/// Story 2.3.
class StockAdjustBottomSheet extends ConsumerStatefulWidget {
  final String productId;
  final String? storeId;

  const StockAdjustBottomSheet({
    super.key,
    required this.productId,
    this.storeId,
  });

  static Future<void> show(
    BuildContext context,
    WidgetRef ref, {
    required String productId,
    String? storeId,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => StockAdjustBottomSheet(
        productId: productId,
        storeId: storeId,
      ),
    );
  }

  @override
  ConsumerState<StockAdjustBottomSheet> createState() =>
      _StockAdjustBottomSheetState();
}

class _StockAdjustBottomSheetState
    extends ConsumerState<StockAdjustBottomSheet> {
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

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    // storeId must be a real UUID — guaranteed by StockLevelWidget (Story 2.3 hotfix)
    final storeId = widget.storeId;
    if (storeId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Impossible de déterminer le dépôt. Réessayez.')),
      );
      return;
    }

    setState(() => _loading = true);
    final newQuantity = int.parse(_qtyController.text.trim());
    final notes = _notesController.text.trim();

    final success = await ref
        .read(stockNotifierProvider(widget.productId).notifier)
        .adjustStock(
          storeId: storeId,
          newQuantity: newQuantity,
          notes: notes,
        );

    if (mounted) {
      setState(() => _loading = false);
      if (success) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Stock ajusté avec succès')),
        );
      } else {
        final blocked = ref.read(stockNotifierProvider(widget.productId)).blockedByGate;
        if (blocked) {
          SyncRequiredModal.show(context);
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Erreur lors de l\'ajustement'),
              backgroundColor: Colors.red,
            ),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottom),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Ajustement de stock',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              'Définissez la quantité réelle en stock (après comptage physique).',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _qtyController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Nouvelle quantité *',
                hintText: 'ex: 42',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.inventory_2_outlined),
              ),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return 'Champ requis';
                final n = int.tryParse(v.trim());
                if (n == null || n < 0) return 'Quantité >= 0 requise';
                return null;
              },
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _notesController,
              decoration: const InputDecoration(
                labelText: 'Raison * (audit)',
                hintText: 'ex: Comptage physique du 09/03/2026',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.description_outlined),
              ),
              validator: (v) {
                if (v == null || v.trim().isEmpty) {
                  return 'Raison obligatoire pour l\'audit';
                }
                return null;
              },
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: _loading ? null : _submit,
                child: _loading
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Confirmer l\'ajustement'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

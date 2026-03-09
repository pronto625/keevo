import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../provider/stock_provider.dart';

/// StockEntryBottomSheet — modal for recording a stock delivery (+quantity).
///
/// Story 2.3.
class StockEntryBottomSheet extends ConsumerStatefulWidget {
  final String productId;
  final String? storeId;

  const StockEntryBottomSheet({
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
      builder: (_) => StockEntryBottomSheet(
        productId: productId,
        storeId: storeId,
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
    final quantity = int.parse(_qtyController.text.trim());
    final notes =
        _notesController.text.trim().isEmpty ? null : _notesController.text.trim();

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
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Entrée de stock enregistrée')),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Erreur lors de l\'enregistrement'),
            backgroundColor: Colors.red,
          ),
        );
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
            Text('Entrée de stock',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            TextFormField(
              controller: _qtyController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Quantité reçue *',
                hintText: 'ex: 50',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.add_circle_outline),
              ),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return 'Champ requis';
                final n = int.tryParse(v.trim());
                if (n == null || n <= 0) return 'Quantité positive requise';
                return null;
              },
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _notesController,
              decoration: const InputDecoration(
                labelText: 'Référence / Notes',
                hintText: 'ex: BL-2025-001',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.note_outlined),
              ),
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
                    : const Text('Enregistrer l\'entrée'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

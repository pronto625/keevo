import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../provider/stock_provider.dart';

/// StockThresholdBottomSheet — configure the minimum stock alert threshold.
///
/// Threshold of 0 disables the alert.
/// Story 2.3.
class StockThresholdBottomSheet extends ConsumerStatefulWidget {
  final String productId;

  const StockThresholdBottomSheet({super.key, required this.productId});

  static Future<void> show(
    BuildContext context,
    WidgetRef ref, {
    required String productId,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => StockThresholdBottomSheet(productId: productId),
    );
  }

  @override
  ConsumerState<StockThresholdBottomSheet> createState() =>
      _StockThresholdBottomSheetState();
}

class _StockThresholdBottomSheetState
    extends ConsumerState<StockThresholdBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  final _thresholdController = TextEditingController();
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    // Pre-fill with current threshold if available
    final state =
        ref.read(stockNotifierProvider(widget.productId));
    final currentThreshold = state.levels.isNotEmpty
        ? state.levels.first.minimumThreshold
        : 0;
    _thresholdController.text = '$currentThreshold';
  }

  @override
  void dispose() {
    _thresholdController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _loading = true);

    final threshold = int.parse(_thresholdController.text.trim());
    final success = await ref
        .read(stockNotifierProvider(widget.productId).notifier)
        .setThreshold(threshold);

    if (mounted) {
      setState(() => _loading = false);
      if (success) {
        // Grab the messenger before popping: the sheet's context goes away.
        final messenger = ScaffoldMessenger.of(context);
        Navigator.of(context).pop();
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              threshold == 0
                  ? 'Alerte stock désactivée'
                  : 'Seuil d\'alerte défini à $threshold unités',
            ),
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Erreur lors de la mise à jour du seuil'),
            backgroundColor: AppTheme.errorStrong,
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
            Row(
              children: [
                const SoftIconBadge(
                  icon: Icons.notifications_outlined,
                  color: AppTheme.warning,
                  size: 36,
                ),
                const SizedBox(width: 10),
                Text('Seuil d\'alerte stock',
                    style: Theme.of(context)
                        .textTheme
                        .titleLarge
                        ?.copyWith(fontWeight: FontWeight.w700)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Recevez une alerte quand le stock atteint ce seuil. '
              'Entrez 0 pour désactiver.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _thresholdController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Seuil minimum (unités)',
                hintText: 'ex: 10',
                prefixIcon: Icon(Icons.warning_amber_outlined),
                helperText: '0 = pas d\'alerte',
              ),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return 'Champ requis';
                final n = int.tryParse(v.trim());
                if (n == null || n < 0) return 'Valeur >= 0 requise';
                return null;
              },
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  ),
                ),
                onPressed: _loading ? null : _submit,
                child: _loading
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Enregistrer le seuil'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

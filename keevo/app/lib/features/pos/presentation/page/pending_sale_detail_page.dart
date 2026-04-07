import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../catalog/domain/model/product_status.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/model/sale_model.dart';
import '../provider/pos_providers.dart';

/// PendingSaleDetailPage — OWNER views sale details + validates/cancels.
/// Story 4.3 AC6-AC7.
class PendingSaleDetailPage extends ConsumerStatefulWidget {
  final String saleId;
  final Sale? sale;

  const PendingSaleDetailPage({
    super.key,
    required this.saleId,
    this.sale,
  });

  @override
  ConsumerState<PendingSaleDetailPage> createState() =>
      _PendingSaleDetailPageState();
}

class _PendingSaleDetailPageState extends ConsumerState<PendingSaleDetailPage> {
  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);
  static final _dateFormat = DateFormat('dd/MM/yyyy HH:mm');

  bool _loading = false;

  @override
  Widget build(BuildContext context) {
    final sale = widget.sale;
    if (sale == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Vente en attente')),
        body: const Center(child: Text('Vente non trouvée')),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Détail vente en attente'),
        centerTitle: true,
      ),
      body: Column(
        children: [
          // — Header
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [AppTheme.warning, AppTheme.warning],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
            ),
            child: Column(
              children: [
                Icon(Icons.pending_actions,
                    size: 40, color: AppTheme.warning),
                const SizedBox(height: 8),
                Text(
                  _currencyFormat.format(sale.totalAmount),
                  style: const TextStyle(
                      fontSize: 28, fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 4),
                Text(
                  _dateFormat.format(sale.createdAt),
                  style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
                ),
                const SizedBox(height: 4),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: AppTheme.warning,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'EN ATTENTE DE VALIDATION',
                    style: TextStyle(
                      color: AppTheme.warning,
                      fontWeight: FontWeight.w600,
                      fontSize: 12,
                    ),
                  ),
                ),
              ],
            ),
          ),

          // — Items list
          Expanded(
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: sale.items.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (_, index) {
                final item = sale.items[index];
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(item.productName,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text(
                    '${item.quantity} × ${_currencyFormat.format(item.appliedUnitPrice)}',
                    style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 13),
                  ),
                  trailing: Text(
                    _currencyFormat.format(item.subtotal),
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                );
              },
            ),
          ),

          // — Actions
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: () => _showCancelDialog(sale.id),
                            style: OutlinedButton.styleFrom(
                              foregroundColor: AppTheme.errorColor,
                              side: const BorderSide(color: AppTheme.errorColor),
                              padding: const EdgeInsets.symmetric(vertical: 14),
                              shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(12)),
                            ),
                            icon: const Icon(Icons.cancel_outlined, size: 20),
                            label: const Text('Annuler'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          flex: 2,
                          child: FilledButton.icon(
                            onPressed: () => _showValidateDialog(sale.id),
                            style: FilledButton.styleFrom(
                              backgroundColor: AppTheme.warning,
                              padding: const EdgeInsets.symmetric(vertical: 14),
                              shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(12)),
                            ),
                            icon: const Icon(Icons.check_circle_outline,
                                size: 20),
                            label: const Text('Valider la vente'),
                          ),
                        ),
                      ],
                    ),
            ),
          ),
        ],
      ),
    );
  }

  void _showValidateDialog(String saleId) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Valider la vente'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: 'Justification (optionnel)',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Retour')),
          FilledButton(
            onPressed: () {
              final text = controller.text.trim();
              Navigator.pop(ctx);
              _doValidate(saleId, text);
            },
            child: const Text('Confirmer'),
          ),
        ],
      ),
    );
  }

  void _showCancelDialog(String saleId) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Annuler la vente'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: 'Justification (optionnel)',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Retour')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppTheme.errorColor),
            onPressed: () {
              final text = controller.text.trim();
              Navigator.pop(ctx);
              _doCancel(saleId, text);
            },
            child: const Text('Confirmer annulation'),
          ),
        ],
      ),
    );
  }

  Future<void> _doValidate(String saleId, String justification) async {
    setState(() => _loading = true);
    try {
      final sale = widget.sale!;
      final productRepo = ref.read(productRepositoryProvider);

      // ── Step 1: Detect DRAFT products in this sale ──
      final drafts = <SaleItemModel>[];
      for (final item in sale.items) {
        final product = await productRepo.getById(item.productId);
        if (product != null && product.status == ProductStatus.draft) {
          drafts.add(item);
        }
      }

      // ── Step 2: Promote each DRAFT product (with stock entry dialog) ──
      final remappings = <String, String>{};
      final initialStockEntries = <String, int>{};
      if (drafts.isNotEmpty) {
        for (final item in drafts) {
          if (!mounted) return;
          final result = await _showPromotionDialog(item);
          if (result == null) {
            // User cancelled promotion — abort entire validation
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Validation annulée')),
              );
            }
            return;
          }
          final (newId, initialStock) = result;
          if (newId != item.productId) {
            remappings[item.productId] = newId;
          }
          if (initialStock > 0) {
            initialStockEntries[newId] = initialStock;
          }
        }
      }

      // ── Step 3: Validate the sale (backend + local) ──
      final saleRepo = ref.read(saleRepositoryProvider);
      await saleRepo.validateSale(
        saleId,
        justification,
        productIdRemappings: remappings.isNotEmpty ? remappings : null,
        initialStockEntries: initialStockEntries.isNotEmpty ? initialStockEntries : null,
      );

      // Invalidate pending sale lists so UI refreshes
      final storeId = ref.read(activeStoreIdProvider);
      ref.invalidate(pendingSalesProvider(storeId));
      ref.invalidate(pendingSalesCountProvider(storeId));
      // Refresh catalog so promoted products show as ACTIVE
      ref.invalidate(productListProvider);
      ref.invalidate(pendingDraftsCountProvider);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Vente validée avec succès')),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// Shows a promotion dialog for a single draft product.
  /// Returns (newProductId, initialStock) on success, or null if cancelled.
  Future<(String, int)?> _showPromotionDialog(SaleItemModel item) async {
    final storeId = widget.sale?.storeId;
    final stockController = TextEditingController();
    try {
      return await showDialog<(String, int)?>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
          title: Row(
            children: [
              Icon(Icons.check_circle_rounded,
                  color: AppTheme.success, size: 28),
              const SizedBox(width: 12),
              const Expanded(child: Text('Valider ce produit ?')),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '«\u202F${item.productName}\u202F» est un brouillon. '
                'Il doit être validé avant de confirmer cette vente.',
                style: const TextStyle(height: 1.4),
              ),
              if (storeId != null) ...[
                const SizedBox(height: 16),
                TextField(
                  controller: stockController,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Stock initial',
                    hintText: '0',
                    suffixText: 'unités',
                    helperText:
                        'Quantité disponible (doit couvrir la vente)',
                    border: OutlineInputBorder(),
                  ),
                ),
              ],
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, null),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () async {
                final initialStock =
                    int.tryParse(stockController.text) ?? 0;
                try {
                  // Promote the product
                  final actions = ref.read(productActionsProvider);
                  final newId =
                      await actions.promoteToActive(item.productId);

                  // Stock entry will be handled by the backend
                  // during validateSale (using the sale's authoritative storeId)
                  ref.invalidate(stockNotifierProvider(newId));

                  if (ctx.mounted) Navigator.pop(ctx, (newId, initialStock));
                } catch (e) {
                  if (ctx.mounted) {
                    ScaffoldMessenger.of(ctx).showSnackBar(
                      SnackBar(
                        content: Text('Erreur promotion: $e'),
                        backgroundColor: AppTheme.errorColor,
                      ),
                    );
                  }
                }
              },
              style: FilledButton.styleFrom(
                backgroundColor: AppTheme.success,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: const Text('Valider le produit'),
            ),
          ],
        ),
      );
    } finally {
      stockController.dispose();
    }
  }

  Future<void> _doCancel(String saleId, String justification) async {
    setState(() => _loading = true);
    try {
      final repo = ref.read(saleRepositoryProvider);
      await repo.cancelSale(saleId, justification);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Vente annulée')),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }
}

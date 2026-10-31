import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../catalog/domain/model/product_status.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/model/sale_model.dart';
import '../../../dashboard/presentation/provider/dashboard_providers.dart';
import '../provider/day_closure_providers.dart';
import '../provider/pos_providers.dart';
import '../widget/pos_app_bar.dart';
import 'package:keevo_core/di/core_providers.dart';

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
      NumberFormat.currency(locale: 'fr_CM', symbol: 'XAF', decimalDigits: 0);
  static final _dateFormat = DateFormat('dd/MM/yyyy HH:mm');

  bool _loading = false;

  @override
  Widget build(BuildContext context) {
    final isEmployee = ref.watch(currentUserRoleProvider) == 'EMPLOYEE';
    final providedSale = widget.sale;
    if (providedSale != null) {
      return _buildSaleScaffold(context, providedSale, isEmployee);
    }

    final saleAsync = ref.watch(saleByIdProvider(widget.saleId));
    return saleAsync.when(
      loading: () => const Scaffold(
        appBar: PosAppBar(title: 'Vente en attente'),
        body: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Scaffold(
        appBar: const PosAppBar(title: 'Vente en attente'),
        body: AppErrorWidget(
          error: e,
          title: 'Erreur de chargement de la vente',
          onRetry: () => ref.invalidate(saleByIdProvider(widget.saleId)),
        ),
      ),
      data: (loadedSale) {
        if (loadedSale == null) {
          return const Scaffold(
            appBar: PosAppBar(title: 'Vente en attente'),
            body: Center(child: Text('Vente non trouvée')),
          );
        }
        return _buildSaleScaffold(context, loadedSale, isEmployee);
      },
    );
  }

  Widget _buildSaleScaffold(BuildContext context, Sale sale, bool isEmployee) {
    final cs = Theme.of(context).colorScheme;
    // Bandeau d'état en ambre dilué plutôt qu'en aplat saturé ; texte en
    // ambre foncé (clair) ou ambre vif (sombre) pour rester lisible.
    final onBand = cs.brightness == Brightness.dark
        ? cs.warning
        : AppTheme.onWarning;
    return Scaffold(
      appBar: const PosAppBar(title: 'Détail vente en attente'),
      body: Column(
        children: [
          // — Header
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            color: cs.warningContainer,
            child: Column(
              children: [
                Icon(Icons.pending_actions_rounded, size: 40, color: onBand),
                const SizedBox(height: 8),
                if (!isEmployee)
                  Text(
                    _currencyFormat.format(sale.totalAmount),
                    style: TextStyle(
                        color: onBand,
                        fontSize: 28,
                        fontWeight: FontWeight.w800),
                  ),
                const SizedBox(height: 4),
                Text(
                  _dateFormat.format(sale.createdAt),
                  style: TextStyle(color: onBand.withValues(alpha: 0.65)),
                ),
                const SizedBox(height: 4),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: onBand.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'EN ATTENTE DE VALIDATION',
                    style: TextStyle(
                      color: onBand,
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
                    isEmployee
                        ? 'Qté: ${item.quantity}'
                        : '${item.quantity} × ${_currencyFormat.format(item.appliedUnitPrice)}',
                    style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 13),
                  ),
                  trailing: isEmployee
                      ? null
                      : Text(
                          _currencyFormat.format(item.subtotal),
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                );
              },
            ),
          ),

          // — Actions (validation/cancellation are owner-only)
          if (!isEmployee)
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
                              side:
                                  const BorderSide(color: AppTheme.errorColor),
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
                            onPressed: () => _showValidateDialog(sale),
                            style: FilledButton.styleFrom(
                              backgroundColor: AppTheme.warning,
                              foregroundColor: AppTheme.onWarning,
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

  void _showValidateDialog(Sale sale) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Valider la vente'),
        content: const Text('Confirmer la validation de cette vente ?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Retour')),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              _doValidate(sale, '');
            },
            child: const Text('Confirmer'),
          ),
        ],
      ),
    );
  }

  void _showCancelDialog(String saleId) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Annuler la vente'),
        content: const Text(
            'Cette action est irréversible. Confirmer l’annulation ?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Retour')),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.errorStrong,
              foregroundColor: AppTheme.onBrand,
            ),
            onPressed: () {
              Navigator.pop(ctx);
              _doCancel(saleId, '');
            },
            child: const Text('Confirmer annulation'),
          ),
        ],
      ),
    );
  }

  Future<void> _doValidate(Sale sale, String justification) async {
    final saleId = sale.id;
    setState(() => _loading = true);
    try {
      final productRepo = ref.read(productRepositoryProvider);

      // ── Step 1: Detect DRAFT products in this sale ──
      final drafts = <SaleItemModel>[];
      for (final item in sale.items) {
        final product = await productRepo.getById(item.productId);
        if (product != null && product.status == ProductStatus.draft) {
          drafts.add(item);
        }
      }

      // ── Step 2: Ask the initial stock of every DRAFT product first, and only
      // promote once all were confirmed — cancelling a later dialog must not
      // leave earlier products promoted with the sale still pending.
      final remappings = <String, String>{};
      final initialStockEntries = <String, int>{};
      if (drafts.isNotEmpty) {
        final initialStocks = <SaleItemModel, int>{};
        for (final item in drafts) {
          if (!mounted) return;
          final initialStock = await _showPromotionDialog(item, sale.storeId);
          if (initialStock == null) {
            // User cancelled promotion — abort entire validation
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Validation annulée')),
              );
            }
            return;
          }
          initialStocks[item] = initialStock;
        }
        final actions = ref.read(productActionsProvider);
        for (final MapEntry(key: item, value: initialStock)
            in initialStocks.entries) {
          final newId = await actions.promoteToActive(item.productId);
          // Stock entry is applied by validateSale (backend, with the sale's
          // authoritative storeId).
          ref.invalidate(stockNotifierProvider(newId));
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
        initialStockEntries:
            initialStockEntries.isNotEmpty ? initialStockEntries : null,
      );

      // Invalidate pending sale lists so UI refreshes
      final storeId = ref.read(activeStoreIdProvider);
      ref.invalidate(pendingSalesProvider(storeId));
      ref.invalidate(pendingSalesCountProvider(storeId));
      // Refresh catalog so promoted products show as ACTIVE
      ref.invalidate(productListProvider);
      ref.invalidate(pendingDraftsCountProvider);
      // Validation moved stock and today's totals.
      ref.invalidate(stockNotifierProvider);
      ref.invalidate(todaySummaryProvider);
      ref.invalidate(dayClosureStateProvider);
      ref.invalidate(dashboardSnapshotProvider);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Vente validée avec succès')),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(appErrorMessage(e))),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// Asks confirmation + initial stock for a single draft product.
  /// Returns the initial stock, or null if cancelled.
  Future<int?> _showPromotionDialog(SaleItemModel item, String? storeId) async {
    final stockController = TextEditingController();
    try {
      return await showDialog<int?>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => AlertDialog(
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
                    helperText: 'Quantité disponible (doit couvrir la vente)',
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
              onPressed: () => Navigator.pop(
                  ctx, int.tryParse(stockController.text) ?? 0),
              style: FilledButton.styleFrom(
                backgroundColor: AppTheme.successStrong,
                foregroundColor: AppTheme.onBrand,
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
      // Defer dispose so the TextField's animation completes before releasing the controller.
      WidgetsBinding.instance
          .addPostFrameCallback((_) => stockController.dispose());
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
          SnackBar(content: Text(appErrorMessage(e))),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }
}

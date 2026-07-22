import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../provider/day_closure_providers.dart';
import '../provider/pos_providers.dart';

/// SaleDetailPage — Displays details of a single sale.
///
/// Story 4.4 AC7 — View sale items, quantities, applied prices, discount, client.
class SaleDetailPage extends ConsumerWidget {
  final String saleId;
  final Sale? sale;

  const SaleDetailPage({super.key, required this.saleId, this.sale});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // If sale was passed directly (from sales history), use it
    if (sale != null) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Détails de la vente'),
          backgroundColor: AppTheme.primary,
          foregroundColor: Colors.white,
          elevation: 0,
        ),
        body: _SaleDetailContent(sale: sale!),
      );
    }

    // Fallback: fetch by ID (deep link or local)
    final saleAsync = ref.watch(saleByIdProvider(saleId));

    return Scaffold(
      appBar: AppBar(
        title: const Text('Détails de la vente'),
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: saleAsync.when(
        data: (sale) {
          if (sale == null) {
            return const Center(child: Text('Vente non trouvée'));
          }
          return _SaleDetailContent(sale: sale);
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
      ),
    );
  }
}

class _SaleDetailContent extends ConsumerWidget {
  final Sale sale;

  const _SaleDetailContent({required this.sale});

  static const _currencyFormat = _CurrencyFormat();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final dateFormat = DateFormat("EEEE d MMMM yyyy 'à' HH:mm", 'fr_FR');
    final isOwner = ref.watch(currentUserRoleProvider) == 'OWNER';

    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header with status and date
          Container(
            color: _statusColor.withValues(alpha: 0.1),
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    _buildStatusChip(),
                    const Spacer(),
                    _buildPaymentChip(),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  dateFormat.format(sale.occurredAt),
                  style: TextStyle(
                    color: theme.colorScheme.onSurface,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
          ),
          // Client info (if any)
          if (sale.clientId != null) ...[
            _buildSectionHeader('Client'),
            ListTile(
              leading: CircleAvatar(
                backgroundColor: theme.colorScheme.outlineVariant,
                child: Icon(Icons.person_outline, color: theme.colorScheme.onSurfaceVariant),
              ),
              title: Text(sale.clientId!), // TODO: resolve client name
              subtitle: const Text('Client enregistré'),
            ),
            const Divider(height: 1),
          ],
          // Items section
          _buildSectionHeader('Articles (${sale.items.length})'),
          ...sale.items.map((item) => _buildItemTile(item, theme)),
          const Divider(height: 1),
          // Totals section
          _buildSectionHeader('Résumé'),
          _buildTotalRow(
            context,
            'Sous-total',
            sale.items.fold<int>(0, (sum, item) => sum + item.subtotal),
          ),
          if (sale.discountAmount > 0)
            _buildTotalRow(
              context,
              'Remise',
              -sale.discountAmount,
              isDiscount: true,
            ),
          const Divider(height: 24),
          _buildTotalRow(
            context,
            'Total',
            sale.totalAmount,
            isTotal: true,
          ),
          // Story v1s-13-5 (AC8) — OWNER-only Annuler/Corriger on COMPLETED sales.
          if (isOwner && sale.status == 'COMPLETED') ...[
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => _showCancelDialog(context, ref),
                      icon: const Icon(Icons.cancel_outlined),
                      label: const Text('Annuler entièrement'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppTheme.errorColor,
                        side: const BorderSide(color: AppTheme.errorColor),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () => _showCorrectSheet(context, ref),
                      icon: const Icon(Icons.edit_outlined),
                      label: const Text('Corriger un article'),
                      style: FilledButton.styleFrom(
                        backgroundColor: AppTheme.warning,
                        foregroundColor: AppTheme.onWarning,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  void _refreshAfterOwnerAction(WidgetRef ref) {
    ref.invalidate(saleByIdProvider(sale.id));
    ref.invalidate(salesHistoryProvider);
  }

  void _showCancelDialog(BuildContext context, WidgetRef ref) {
    final controller = TextEditingController();
    showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) {
          final justification = controller.text.trim();
          final canConfirm = justification.length >= 10;
          return AlertDialog(
            title: const Text('Annuler la vente'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                    'Cette action restaure le stock des articles et est irréversible. '
                    'Une justification est requise (10 caractères minimum).'),
                const SizedBox(height: 12),
                TextField(
                  controller: controller,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    labelText: 'Justification',
                    border: OutlineInputBorder(),
                  ),
                  onChanged: (_) => setState(() {}),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Retour'),
              ),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: AppTheme.errorColor),
                onPressed: canConfirm
                    ? () {
                        Navigator.pop(ctx);
                        _doCancel(context, ref, controller.text.trim());
                      }
                    : null,
                child: const Text('Confirmer annulation'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _doCancel(
      BuildContext context, WidgetRef ref, String justification) async {
    try {
      await ref.read(saleRepositoryProvider).cancelSale(sale.id, justification);
      _refreshAfterOwnerAction(ref);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Vente annulée, stock restauré')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(appErrorMessage(e))),
        );
      }
    }
  }

  void _showCorrectSheet(BuildContext context, WidgetRef ref) {
    final justificationController = TextEditingController();
    final qtyControllers = <String, TextEditingController>{
      for (final item in sale.items)
        item.id: TextEditingController(text: '${item.quantity}'),
    };

    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) {
          final itemQuantities = <String, int>{};
          for (final item in sale.items) {
            final newQty = int.tryParse(qtyControllers[item.id]!.text) ?? item.quantity;
            if (newQty != item.quantity) {
              itemQuantities[item.id] = newQty;
            }
          }
          final justification = justificationController.text.trim();
          final canConfirm =
              itemQuantities.isNotEmpty && justification.length >= 10;

          return Padding(
            padding: EdgeInsets.only(
              left: 16,
              right: 16,
              top: 16,
              bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Corriger un article',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                  const SizedBox(height: 12),
                  ...sale.items.map((item) => Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              flex: 2,
                              child: Text(item.productName,
                                  overflow: TextOverflow.ellipsis),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextField(
                                controller: qtyControllers[item.id],
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                  labelText: 'Qté',
                                  border: OutlineInputBorder(),
                                  isDense: true,
                                ),
                                onChanged: (_) => setState(() {}),
                              ),
                            ),
                          ],
                        ),
                      )),
                  const SizedBox(height: 12),
                  TextField(
                    controller: justificationController,
                    maxLines: 2,
                    decoration: const InputDecoration(
                      labelText: 'Justification (10 caractères minimum)',
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: canConfirm
                          ? () {
                              Navigator.pop(ctx);
                              _doCorrect(context, ref, justification, itemQuantities);
                            }
                          : null,
                      child: const Text('Enregistrer la correction'),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _doCorrect(BuildContext context, WidgetRef ref,
      String justification, Map<String, int> itemQuantities) async {
    try {
      await ref
          .read(saleRepositoryProvider)
          .correctSale(sale.id, justification, itemQuantities);
      _refreshAfterOwnerAction(ref);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Vente corrigée')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(appErrorMessage(e))),
        );
      }
    }
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        title,
        style: const TextStyle(
          fontWeight: FontWeight.w600,
          fontSize: 14,
          color: AppTheme.primary,
        ),
      ),
    );
  }

  Widget _buildStatusChip() {
    Color color;
    String label;
    IconData icon;

    switch (sale.status) {
      case 'PENDING_VALIDATION':
        color = AppTheme.warning;
        label = 'En attente';
        icon = Icons.pending_outlined;
        break;
      case 'CANCELLED':
        color = AppTheme.errorColor;
        label = 'Annulée';
        icon = Icons.cancel_outlined;
        break;
      default:
        color = AppTheme.success;
        label = 'Complétée';
        icon = Icons.check_circle_outline;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.2),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.w600,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPaymentChip() {
    final isMomo = sale.paymentMode == PaymentModeEnum.mobileMoney;
    final color = isMomo ? AppTheme.warning : AppTheme.success;
    final icon = isMomo ? Icons.phone_android : Icons.payments_outlined;
    final label = isMomo ? 'Mobile Money' : 'Espèces';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.w600,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildItemTile(SaleItemModel item, ThemeData theme) {
    final hasDiscount = item.appliedUnitPrice < item.catalogueUnitPrice;

    return ListTile(
      leading: CircleAvatar(
        backgroundColor: AppTheme.primary.withValues(alpha: 0.1),
        child: Text(
          '${item.quantity}',
          style: const TextStyle(
            color: AppTheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
      ),
      title: Text(
        item.productName,
        style: const TextStyle(fontWeight: FontWeight.w500),
      ),
      subtitle: hasDiscount
          ? Row(
              children: [
                Text(
                  _currencyFormat.format(item.catalogueUnitPrice),
                  style: TextStyle(
                    decoration: TextDecoration.lineThrough,
                    color: theme.colorScheme.onSurfaceVariant,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  _currencyFormat.format(item.appliedUnitPrice),
                  style: TextStyle(
                    color: AppTheme.warning,
                    fontSize: 12,
                  ),
                ),
              ],
            )
          : Text(
              '${_currencyFormat.format(item.appliedUnitPrice)} / unité',
              style: TextStyle(
                color: theme.colorScheme.onSurfaceVariant,
                fontSize: 12,
              ),
            ),
      trailing: Text(
        _currencyFormat.format(item.subtotal),
        style: const TextStyle(
          fontWeight: FontWeight.w600,
          fontSize: 15,
        ),
      ),
    );
  }

  Widget _buildTotalRow(BuildContext context, String label, int amount,
      {bool isTotal = false, bool isDiscount = false}) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: isTotal ? 16 : 14,
              fontWeight: isTotal ? FontWeight.w700 : FontWeight.w400,
              color: isTotal ? cs.onSurface : cs.onSurface,
            ),
          ),
          Text(
            _currencyFormat.format(amount),
            style: TextStyle(
              fontSize: isTotal ? 18 : 14,
              fontWeight: isTotal ? FontWeight.w700 : FontWeight.w500,
              color: isDiscount
                  ? AppTheme.warning
                  : isTotal
                      ? AppTheme.primary
                      : cs.onSurface,
            ),
          ),
        ],
      ),
    );
  }

  Color get _statusColor {
    switch (sale.status) {
      case 'PENDING_VALIDATION':
        return AppTheme.warning;
      case 'CANCELLED':
        return AppTheme.errorColor;
      default:
        return AppTheme.success;
    }
  }
}

/// Simple currency formatter helper.
class _CurrencyFormat {
  const _CurrencyFormat();

  String format(int amount) {
    final formatter = NumberFormat.currency(
      locale: 'fr_FR',
      symbol: 'FCFA',
      decimalDigits: 0,
    );
    return formatter.format(amount);
  }
}

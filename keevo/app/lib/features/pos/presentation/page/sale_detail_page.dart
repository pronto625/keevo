import 'package:dio/dio.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../../dashboard/presentation/provider/dashboard_providers.dart';
import '../provider/day_closure_providers.dart';
import '../provider/pos_providers.dart';
import '../widget/pos_app_bar.dart';

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
        appBar: const PosAppBar(title: 'Détails de la vente'),
        body: _SaleDetailContent(sale: sale!),
      );
    }

    // Fallback: fetch by ID (deep link or local)
    final saleAsync = ref.watch(saleByIdProvider(saleId));

    return Scaffold(
      appBar: const PosAppBar(title: 'Détails de la vente'),
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

class _SaleDetailContent extends ConsumerStatefulWidget {
  final Sale sale;

  const _SaleDetailContent({required this.sale});

  @override
  ConsumerState<_SaleDetailContent> createState() => _SaleDetailContentState();
}

class _SaleDetailContentState extends ConsumerState<_SaleDetailContent> {
  static const _currencyFormat = _CurrencyFormat();

  /// Sale as currently displayed — replaced locally after a confirmed
  /// cancel/correction so the page never offers actions on a stale status.
  late Sale _sale;
  bool _locallyUpdated = false;
  bool _busy = false;

  Sale get sale => _sale;

  @override
  void initState() {
    super.initState();
    _sale = widget.sale;
  }

  @override
  void didUpdateWidget(covariant _SaleDetailContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_locallyUpdated) _sale = widget.sale;
  }

  @override
  Widget build(BuildContext context) {
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
            _buildSectionHeader(context, 'Client'),
            ListTile(
              leading: const SoftIconBadge(icon: Icons.person_outline_rounded),
              title: Text(
                ref.watch(saleClientNameProvider(sale.clientId!)).valueOrNull ??
                    'Client',
              ),
              subtitle: const Text('Client enregistré'),
            ),
            const Divider(height: 1),
          ],
          // Items section
          _buildSectionHeader(context, 'Articles (${sale.items.length})'),
          ...sale.items.map((item) => _buildItemTile(item, theme)),
          const Divider(height: 1),
          // Totals section
          _buildSectionHeader(context, 'Résumé'),
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
                      onPressed: _busy ? null : _showCancelDialog,
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
                      onPressed: _busy ? null : _showCorrectSheet,
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

  void _refreshAfterOwnerAction() {
    ref.invalidate(saleByIdProvider(sale.id));
    ref.invalidate(salesHistoryProvider);
    ref.invalidate(stockNotifierProvider);
    ref.invalidate(todaySummaryProvider);
    ref.invalidate(dayClosureStateProvider);
    ref.invalidate(dashboardSnapshotProvider);
  }

  /// The local cache (read by [saleByIdProvider]) does not know what the
  /// concurrent writer changed: pull it first (best effort), then drop any
  /// earlier local override so the re-read sale is displayed.
  Future<void> _resyncAfterConflict() async {
    try {
      await ref.read(syncServiceProvider).pull();
    } catch (e) {
      debugPrint('[SaleDetail] pull after 409 failed: $e');
    }
    if (!mounted) return;
    setState(() => _locallyUpdated = false);
    _refreshAfterOwnerAction();
  }

  Future<void> _showCancelDialog() async {
    final justification = await showDialog<String>(
      context: context,
      builder: (_) => const _CancelSaleDialog(),
    );
    if (justification == null || !mounted) return;
    await _runOwnerAction(
      () => ref.read(saleRepositoryProvider).cancelSale(sale.id, justification),
      updated: _copySale(sale, status: 'CANCELLED'),
      successMessage: 'Vente annulée, stock restauré',
    );
  }

  Future<void> _showCorrectSheet() async {
    final result = await showModalBottomSheet<_Correction>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _CorrectSaleSheet(items: sale.items),
    );
    if (result == null || !mounted) return;
    await _runOwnerAction(
      () => ref
          .read(saleRepositoryProvider)
          .correctSale(sale.id, result.justification, result.itemQuantities),
      updated: _copySale(sale, itemQuantities: result.itemQuantities),
      successMessage: 'Vente corrigée',
    );
  }

  Future<void> _runOwnerAction(Future<void> Function() action,
      {required Sale updated, required String successMessage}) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      setState(() {
        _sale = updated;
        _locallyUpdated = true;
      });
      _refreshAfterOwnerAction();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(successMessage)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(appErrorMessage(e))),
      );
      // v1s-13-10: 409 (OPTIMISTIC_LOCK / concurrent cancel-correct) → silent
      // refresh so the page shows the sale as it is now (13-1 convention).
      if (e is DioException && e.response?.statusCode == 409) {
        await _resyncAfterConflict();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Mirrors what the backend (and the local cache) apply: new status, or new
  /// item quantities with subtotal = applied price × qty and total = Σ − remise.
  static Sale _copySale(Sale s,
      {String? status, Map<String, int> itemQuantities = const {}}) {
    final items = s.items.map((i) {
      final qty = itemQuantities[i.id];
      if (qty == null) return i;
      return SaleItemModel(
        id: i.id,
        productId: i.productId,
        variantId: i.variantId,
        productName: i.productName,
        catalogueUnitPrice: i.catalogueUnitPrice,
        appliedUnitPrice: i.appliedUnitPrice,
        quantity: qty,
        subtotal: i.appliedUnitPrice * qty,
      );
    }).toList();
    final total = itemQuantities.isEmpty
        ? s.totalAmount
        : items.fold<int>(0, (sum, i) => sum + i.subtotal) - s.discountAmount;
    return Sale(
      id: s.id,
      storeId: s.storeId,
      employeeId: s.employeeId,
      clientId: s.clientId,
      paymentMode: s.paymentMode,
      mobileMoneyRef: s.mobileMoneyRef,
      totalAmount: total,
      discountAmount: s.discountAmount,
      status: status ?? s.status,
      items: items,
      occurredAt: s.occurredAt,
      createdAt: s.createdAt,
    );
  }

  Widget _buildSectionHeader(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        title,
        style: TextStyle(
          fontWeight: FontWeight.w600,
          fontSize: 14,
          color: Theme.of(context).colorScheme.primary,
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
        backgroundColor: AppTheme.softTint(theme.brightness),
        child: Text(
          '${item.quantity}',
          style: TextStyle(
            color: theme.colorScheme.primary,
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
                      ? cs.primary
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

class _Correction {
  final String justification;
  final Map<String, int> itemQuantities;
  const _Correction(this.justification, this.itemQuantities);
}

/// Cancel dialog — owns its controller so it is disposed with the dialog.
class _CancelSaleDialog extends StatefulWidget {
  const _CancelSaleDialog();

  @override
  State<_CancelSaleDialog> createState() => _CancelSaleDialogState();
}

class _CancelSaleDialogState extends State<_CancelSaleDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final justification = _controller.text.trim();
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
            controller: _controller,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: 'Justification',
            ),
            onChanged: (_) => setState(() {}),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Retour'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppTheme.errorColor),
          onPressed:
              canConfirm ? () => Navigator.pop(context, justification) : null,
          child: const Text('Confirmer annulation'),
        ),
      ],
    );
  }
}

/// Correction sheet — owns its controllers so they are disposed with it.
class _CorrectSaleSheet extends StatefulWidget {
  final List<SaleItemModel> items;

  const _CorrectSaleSheet({required this.items});

  @override
  State<_CorrectSaleSheet> createState() => _CorrectSaleSheetState();
}

class _CorrectSaleSheetState extends State<_CorrectSaleSheet> {
  final _justificationController = TextEditingController();
  late final Map<String, TextEditingController> _qtyControllers = {
    for (final item in widget.items)
      item.id: TextEditingController(text: '${item.quantity}'),
  };

  @override
  void dispose() {
    _justificationController.dispose();
    for (final c in _qtyControllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final itemQuantities = <String, int>{};
    // Every quantity must be a whole number of at least 1 (the server refuses
    // anything else): an empty or zero field blocks the confirmation.
    var allValid = true;
    for (final item in widget.items) {
      final newQty = int.tryParse(_qtyControllers[item.id]!.text);
      if (newQty == null || newQty < 1) {
        allValid = false;
      } else if (newQty != item.quantity) {
        itemQuantities[item.id] = newQty;
      }
    }
    final justification = _justificationController.text.trim();
    final canConfirm =
        allValid && itemQuantities.isNotEmpty && justification.length >= 10;

    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: MediaQuery.of(context).viewInsets.bottom + 16,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Corriger un article',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 12),
            ...widget.items.map((item) => Padding(
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
                          controller: _qtyControllers[item.id],
                          keyboardType: TextInputType.number,
                          inputFormatters: [
                            FilteringTextInputFormatter.digitsOnly,
                          ],
                          decoration: const InputDecoration(
                            labelText: 'Qté',
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
              controller: _justificationController,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Justification (10 caractères minimum)',
              ),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: canConfirm
                    ? () => Navigator.pop(
                        context, _Correction(justification, itemQuantities))
                    : null,
                child: const Text('Enregistrer la correction'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

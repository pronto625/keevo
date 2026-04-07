import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
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
        error: (e, _) => Center(child: Text('Erreur: $e')),
      ),
    );
  }
}

class _SaleDetailContent extends StatelessWidget {
  final Sale sale;

  const _SaleDetailContent({required this.sale});

  final _currencyFormat = const _CurrencyFormat();
  
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dateFormat = DateFormat("EEEE d MMMM yyyy 'à' HH:mm", 'fr_FR');

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
          const SizedBox(height: 24),
        ],
      ),
    );
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

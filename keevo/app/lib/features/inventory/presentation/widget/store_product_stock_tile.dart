import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/store_product_stock_model.dart';

/// StoreProductStockTile — a single product-stock row inside an expanded StoreStockCard.
/// Also used in search results; provide [onTap] to enable navigation (AC3).
/// Story 3.2.
class StoreProductStockTile extends StatelessWidget {
  final StoreProductStockModel entry;

  /// Optional tap handler used in search results to highlight the store card.
  final VoidCallback? onTap;

  const StoreProductStockTile({super.key, required this.entry, this.onTap});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final content = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
      child: Row(
        children: [
          // Small dot indicator
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: _statusColor(entry.status),
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              entry.productName,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w500,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          _QtyChip(quantity: entry.quantity, status: entry.status),
          const SizedBox(width: 8),
          _StatusBadge(status: entry.status),
        ],
      ),
    );
    if (onTap != null) {
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: content,
      );
    }
    return content;
  }

  static Color _statusColor(String status) {
    switch (status) {
      case 'CRITIQUE':
        return AppTheme.errorColor;
      case 'BAS':
        return AppTheme.warning;
      default:
        return AppTheme.success;
    }
  }
}

class _QtyChip extends StatelessWidget {
  final int quantity;
  final String status;
  const _QtyChip({required this.quantity, required this.status});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isCritical = status == 'CRITIQUE';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: isCritical
            ? AppTheme.errorColor.withOpacity(0.08)
            : theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        '$quantity',
        style: theme.textTheme.labelMedium?.copyWith(
          fontWeight: FontWeight.w700,
          color: isCritical ? AppTheme.errorColor : null,
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String status;
  const _StatusBadge({required this.status});

  Color _color() {
    switch (status) {
      case 'CRITIQUE':
        return AppTheme.errorColor;
      case 'BAS':
        return AppTheme.warning;
      default:
        return AppTheme.success;
    }
  }

  String _label() {
    switch (status) {
      case 'CRITIQUE':
        return 'Critique';
      case 'BAS':
        return 'Bas';
      default:
        return 'OK';
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _color();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.35), width: 1),
      ),
      child: Text(
        _label(),
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.2,
            ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../domain/model/inventory_gap_row_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';

/// UX2 color tokens.
const _colorSuccess = Color(0xFF51CF66);
const _colorWarning = Color(0xFFFCC419);
const _colorError = Color(0xFFFA5252);

/// ProductDetailBottomSheet — detailed view of a single gap row.
///
/// Shows product photo/avatar, name, SKU, category, variant,
/// theoretical vs physical vs écart, and XAF gap value.
/// Story 6.3 — AC2.
class ProductDetailBottomSheet extends StatelessWidget {
  final InventoryGapRowModel row;

  const ProductDetailBottomSheet({super.key, required this.row});

  /// Show as a modal bottom sheet.
  static void show(BuildContext context, InventoryGapRowModel row) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => ProductDetailBottomSheet(row: row),
    );
  }

  Color get _ecartColor {
    if (row.isConcordant) return _colorSuccess;
    if (row.isSurplus) return _colorWarning;
    return _colorError;
  }

  String get _ecartLabel {
    if (row.ecart == 0) return '= 0';
    if (row.ecart > 0) return '+${row.ecart}';
    return '${row.ecart}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Drag handle
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 16),

            // Avatar / photo
            _buildAvatar(),
            const SizedBox(height: 16),

            // Product name
            Text(
              row.productName,
              style: theme.textTheme.headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w600),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),

            // SKU + Category row
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                if (row.sku != null && row.sku!.isNotEmpty)
                  Chip(
                    label: Text(row.sku!),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    labelPadding:
                        const EdgeInsets.symmetric(horizontal: 8),
                    labelStyle: theme.textTheme.labelSmall,
                  ),
                if (row.categoryName != null)
                  Chip(
                    label: Text(row.categoryName!),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    labelPadding:
                        const EdgeInsets.symmetric(horizontal: 8),
                    labelStyle: theme.textTheme.labelSmall,
                  ),
                if (row.variantLabel != null)
                  Chip(
                    label: Text(row.variantLabel!),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    labelPadding:
                        const EdgeInsets.symmetric(horizontal: 8),
                    labelStyle: theme.textTheme.labelSmall,
                  ),
              ],
            ),
            const SizedBox(height: 16),

            const Divider(),
            const SizedBox(height: 16),

            // 3-column row: Keevo | Réel | Écart
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _ValueColumn(
                  label: 'Keevo',
                  value: '${row.theoretical}',
                  color: theme.colorScheme.onSurface,
                ),
                _ValueColumn(
                  label: 'Réel',
                  value: '${row.physical}',
                  color: theme.colorScheme.onSurface,
                ),
                _ValueColumn(
                  label: 'Écart',
                  value: _ecartLabel,
                  color: _ecartColor,
                ),
              ],
            ),
            const SizedBox(height: 16),

            // XAF gap value
            if (row.gapValueXaf > 0)
              Text(
                '${row.ecart.abs()} × ${formatXaf(row.unitPriceXaf)} = '
                '${row.isShortage ? '−' : '+'}${formatXaf(row.gapValueXaf)}',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildAvatar() {
    if (row.photoUrl != null && row.photoUrl!.isNotEmpty) {
      return CircleAvatar(
        radius: 40,
        backgroundImage: NetworkImage(row.photoUrl!),
      );
    }
    final colorIndex =
        row.productId.hashCode.abs() % Colors.primaries.length;
    final bgColor = Colors.primaries[colorIndex];
    final initials = _initials(row.productName);
    return CircleAvatar(
      radius: 40,
      backgroundColor: bgColor,
      child: Text(
        initials,
        style: const TextStyle(
            color: Colors.white, fontWeight: FontWeight.bold, fontSize: 24),
      ),
    );
  }

  String _initials(String name) {
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.length >= 2) {
      return '${parts[0][0]}${parts[1][0]}'.toUpperCase();
    }
    return name.isNotEmpty ? name[0].toUpperCase() : '?';
  }
}

class _ValueColumn extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _ValueColumn({
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        Text(
          label,
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.bold,
            color: color,
          ),
        ),
      ],
    );
  }
}

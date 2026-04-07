import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

import '../../domain/model/inventory_gap_row_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';

/// UX2 color tokens.
const _colorSuccess = AppTheme.success;
const _colorWarning = AppTheme.warning;
const _colorError = AppTheme.errorColor;

/// GapRowTile — read-only product row in the gap report.
///
/// Same visual layout as InventoryRow but without TextFormField.
/// Shows "Keevo : X → Réel : Y", écart badge, and XAF gap value.
/// Story 6.3 — AC1, AC2.
class GapRowTile extends StatelessWidget {
  final InventoryGapRowModel row;
  final VoidCallback? onTap;

  const GapRowTile({super.key, required this.row, this.onTap});

  Color get _backgroundColor {
    if (row.isConcordant) return const Color(0xFFE8F5E9);
    if (row.isSurplus) return const Color(0xFFFFFDE7);
    return const Color(0xFFFFEBEE);
  }

  Color get _badgeColor {
    if (row.isConcordant) return _colorSuccess;
    if (row.isSurplus) return _colorWarning;
    return _colorError;
  }

  String get _ecartLabel {
    if (row.ecart == 0) return '= 0';
    if (row.ecart > 0) return '+${row.ecart}';
    return '${row.ecart}';
  }

  String get _semanticEcart {
    if (row.ecart == 0) return 'Écart: concordant';
    if (row.ecart > 0) return 'Écart: surplus de ${row.ecart}';
    return 'Écart: pénurie de ${row.ecart.abs()}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return InkWell(
      onTap: onTap,
      child: Container(
        color: _backgroundColor,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            // Avatar (48dp)
            _buildAvatar(),
            const SizedBox(width: 12),

            // Product info
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    row.productName,
                    style: theme.textTheme.bodyLarge
                        ?.copyWith(fontWeight: FontWeight.w600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Text(
                        'Keevo : ${row.theoretical} → Réel : ${row.physical}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                      if (row.variantLabel != null) ...[
                        const SizedBox(width: 6),
                        Chip(
                          label: Text(row.variantLabel!),
                          visualDensity: VisualDensity.compact,
                          materialTapTargetSize:
                              MaterialTapTargetSize.shrinkWrap,
                          padding: EdgeInsets.zero,
                          labelPadding:
                              const EdgeInsets.symmetric(horizontal: 6),
                          labelStyle: theme.textTheme.labelSmall,
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(width: 8),

            // Écart badge + XAF value
            Semantics(
              label: _semanticEcart,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: _badgeColor.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      _ecartLabel,
                      style: TextStyle(
                        color: _badgeColor,
                        fontWeight: FontWeight.bold,
                        fontSize: 14,
                      ),
                    ),
                  ),
                  if (row.gapValueXaf > 0) ...[
                    const SizedBox(height: 2),
                    Text(
                      '${row.isShortage ? '−' : '+'}${formatXaf(row.gapValueXaf)}',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
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
        radius: 24,
        backgroundImage: NetworkImage(row.photoUrl!),
      );
    }
    final colorIndex =
        row.productId.hashCode.abs() % Colors.primaries.length;
    final bgColor = Colors.primaries[colorIndex];
    final initials = _initials(row.productName);
    return CircleAvatar(
      radius: 24,
      backgroundColor: bgColor,
      child: Text(
        initials,
        style: const TextStyle(
            color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
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

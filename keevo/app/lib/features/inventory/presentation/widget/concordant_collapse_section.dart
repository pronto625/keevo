import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

import '../../domain/model/inventory_gap_row_model.dart';

/// ConcordantCollapseSection — ExpansionTile for concordant products.
///
/// Collapsed by default. Green background. Shows product names with "= 0" badge.
/// Story 6.3 — AC1.
class ConcordantCollapseSection extends StatelessWidget {
  final int count;
  final List<InventoryGapRowModel> rows;

  const ConcordantCollapseSection({
    super.key,
    required this.count,
    required this.rows,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Semantics(
      label: 'Section concordants, $count produits',
      child: Card(
        color: const Color(0xFFE8F5E9), // green.shade50
        child: ExpansionTile(
          key: const PageStorageKey('concordant-section'),
          initiallyExpanded: false,
          leading: const Icon(Icons.check_circle, color: AppTheme.success),
          title: Text(
            'Concordants : $count produits',
            style: theme.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
          children: rows
              .map((row) => ListTile(
                    dense: true,
                    title: Text(
                      row.variantLabel != null
                          ? '${row.productName} ${row.variantLabel}'
                          : row.productName,
                      style: theme.textTheme.bodyMedium,
                    ),
                    trailing: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: AppTheme.success.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Text(
                        '= 0',
                        style: TextStyle(
                          color: AppTheme.success,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ))
              .toList(),
        ),
      ),
    );
  }
}

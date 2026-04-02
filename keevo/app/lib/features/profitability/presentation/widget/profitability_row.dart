import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/product_profitability_model.dart';

/// ProductProfitabilityRow — list tile for one product in the profitability list.
///
/// Story 7.4 — Task 17.1.
class ProductProfitabilityRow extends StatelessWidget {
  final ProductProfitabilityEntry entry;
  final VoidCallback? onTap;

  const ProductProfitabilityRow({
    super.key,
    required this.entry,
    this.onTap,
  });

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = entry.marginColor;

    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            // ── Left: product info ────────────────────────────────────────
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.productName,
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(fontWeight: FontWeight.w600),
                  ),
                  if (entry.categoryName != null)
                    Text(
                      entry.categoryName!,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurface.withAlpha(150),
                      ),
                    ),
                  const SizedBox(height: 4),
                  Text(
                    '${_currencyFmt.format(entry.totalRevenue)} · ${entry.unitsSold} unités',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),

            // ── Right: margin badge ───────────────────────────────────────
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: color.withAlpha(30),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: color, width: 1),
                  ),
                  child: Text(
                    '${entry.marginPercent.toStringAsFixed(1)}%',
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: color,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(height: 4),
                if (entry.isLoss)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: Colors.red.shade50,
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.warning_amber_rounded,
                            size: 10, color: Colors.red.shade700),
                        const SizedBox(width: 2),
                        Text(
                          'PERTE',
                          style: TextStyle(
                              fontSize: 10,
                              color: Colors.red.shade700,
                              fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                  )
                else
                  Text(
                    _currencyFmt.format(entry.grossMarginXaf),
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: color, fontWeight: FontWeight.w500),
                  ),
              ],
            ),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right, size: 16, color: Colors.black38),
          ],
        ),
      ),
    );
  }
}

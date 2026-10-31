import 'package:keevo_core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/product_profitability_model.dart';
import 'package:keevo_core/utils/fr_plural.dart';
import 'package:keevo_core/utils/percent_format.dart';

/// StorePerformanceRow — ranked store tile for the store comparison page.
///
/// #1 → gold, #2 → silver, #3 → bronze rank badge.
/// Delta badge: green for positive, red for negative.
///
/// Story 7.4 — Task 17.4.
class StorePerformanceRow extends StatelessWidget {
  final StorePerformanceEntry entry;

  const StorePerformanceRow({super.key, required this.entry});

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  static Color _rankColor(int rank, ColorScheme colorScheme) {
    switch (rank) {
      case 1:
        return AppTheme.medalGold; // gold
      case 2:
        return AppTheme.medalSilver; // silver
      case 3:
        return AppTheme.medalBronze; // bronze
      default:
        return colorScheme.outlineVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // A33: no sales in the preceding period → « — », no badge.
    final delta = entry.deltaPercent;
    final isPositive = delta == null || delta >= 0;
    final deltaColor =
        isPositive ? theme.colorScheme.success : theme.colorScheme.error;
    final deltaText =
        delta == null ? null : '${isPositive ? '+' : ''}${formatPercentFr(delta)}';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          // ── Rank badge ───────────────────────────────────────────────────
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: _rankColor(entry.rank, theme.colorScheme)
                  .withValues(alpha: 0.16),
              shape: BoxShape.circle,
              border: Border.all(
                  color: _rankColor(entry.rank, theme.colorScheme), width: 1.5),
            ),
            child: Text(
              '#${entry.rank}',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                color: entry.rank <= 3
                    ? _rankColor(entry.rank, theme.colorScheme)
                    : theme.colorScheme.onSurface,
                fontSize: 13,
              ),
            ),
          ),
          const SizedBox(width: 12),

          // ── Store info ───────────────────────────────────────────────────
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.storeName,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: theme.colorScheme.onSurface,
                  ),
                ),
                Text(
                  _currencyFmt.format(entry.totalRevenue),
                  style: theme.textTheme.bodySmall,
                ),
                if (entry.topProductName != null)
                  Text(
                    entry.topProductName!,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontStyle: FontStyle.italic,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),

          // ── Stats + delta ────────────────────────────────────────────────
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                frCount(entry.salesCount, 'vente', 'ventes'),
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 4),
              if (deltaText == null)
                Text(
                  '—',
                  semanticsLabel: 'Pas de comparaison possible',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                )
              else
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: deltaColor.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    deltaText,
                    style: TextStyle(
                      fontSize: 11,
                      color: deltaColor,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

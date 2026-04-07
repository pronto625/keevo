import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/product_profitability_model.dart';

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
        return const Color(0xFFFFD700); // gold
      case 2:
        return const Color(0xFFC0C0C0); // silver
      case 3:
        return const Color(0xFFCD7F32); // bronze
      default:
        return colorScheme.outlineVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final delta = entry.deltaPercent;
    final isPositive = delta >= 0;
    final deltaColor = isPositive ? AppTheme.success : AppTheme.errorColor;
    final deltaText =
        '${isPositive ? '+' : ''}${delta.toStringAsFixed(1)}%';

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
              color: _rankColor(entry.rank, theme.colorScheme).withAlpha(40),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: _rankColor(entry.rank, theme.colorScheme), width: 1.5),
            ),
            child: Text(
              '#${entry.rank}',
              style: TextStyle(
                fontWeight: FontWeight.bold,
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
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(fontWeight: FontWeight.w600),
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
                      color: theme.colorScheme.onSurface.withAlpha(150),
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
                '${entry.salesCount} ventes',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 4),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: deltaColor.withAlpha(25),
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: deltaColor, width: 1),
                ),
                child: Text(
                  deltaText,
                  style: TextStyle(
                    fontSize: 11,
                    color: deltaColor,
                    fontWeight: FontWeight.bold,
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

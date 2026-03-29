import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

/// MetricBadgeCard — compact metric tile with icon, value, label, and trend.
///
/// Story 7.1 — AC3.
class MetricBadgeCard extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;
  final String value;
  final double? trendPercent;
  final VoidCallback? onTap;

  const MetricBadgeCard({
    super.key,
    required this.icon,
    required this.color,
    required this.label,
    required this.value,
    this.trendPercent,
    this.onTap,
  });

  /// Factory — CA d'Hier
  factory MetricBadgeCard.yesterdayCA({
    required int amount,
    VoidCallback? onTap,
  }) {
    return MetricBadgeCard(
      icon: Icons.trending_up,
      color: const Color(0xFF4DABF7),
      label: "CA d'Hier",
      value: _currencyFormat.format(amount),
      onTap: onTap,
    );
  }

  /// Factory — Stock Bas
  factory MetricBadgeCard.lowStock({
    required int count,
    VoidCallback? onTap,
  }) {
    return MetricBadgeCard(
      icon: Icons.warning_amber_rounded,
      color: count > 0 ? const Color(0xFFFA5252) : const Color(0xFF868E96),
      label: 'Stock Bas',
      value: count.toString(),
      onTap: onTap,
    );
  }

  /// Factory — Transactions Ce Mois
  factory MetricBadgeCard.monthlyTransactions({
    required int count,
    double? trendPercent,
    VoidCallback? onTap,
  }) {
    return MetricBadgeCard(
      icon: Icons.receipt_long,
      color: const Color(0xFF3B5BDB),
      label: 'Transactions Ce Mois',
      value: count.toString(),
      trendPercent: trendPercent,
      onTap: onTap,
    );
  }

  /// Factory — Panier Moyen
  factory MetricBadgeCard.averageBasket({
    required int amount,
    double? trendPercent,
    VoidCallback? onTap,
  }) {
    return MetricBadgeCard(
      icon: Icons.shopping_basket,
      color: const Color(0xFF51CF66),
      label: 'Panier Moyen',
      value: _currencyFormat.format(amount),
      trendPercent: trendPercent,
      onTap: onTap,
    );
  }

  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(6),
                    decoration: BoxDecoration(
                      color: color.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Icon(icon, size: 18, color: color),
                  ),
                  const Spacer(),
                  if (trendPercent != null && trendPercent != 0)
                    _MiniTrend(trendPercent: trendPercent!),
                ],
              ),
              const SizedBox(height: 10),
              Text(
                value,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2),
              Text(
                label,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context)
                          .colorScheme
                          .onSurface
                          .withValues(alpha: 0.6),
                    ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MiniTrend extends StatelessWidget {
  final double trendPercent;
  const _MiniTrend({required this.trendPercent});

  @override
  Widget build(BuildContext context) {
    final isUp = trendPercent > 0;
    final color = isUp ? const Color(0xFF51CF66) : const Color(0xFFFA5252);
    return Text(
      '${isUp ? "↑" : "↓"}${trendPercent.abs().toStringAsFixed(0)}%',
      style: TextStyle(
        color: color,
        fontWeight: FontWeight.w600,
        fontSize: 12,
      ),
    );
  }
}

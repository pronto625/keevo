import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';

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
      color: AppTheme.primaryGradientEnd,
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
      color: count > 0 ? AppTheme.errorColor : AppTheme.grey600,
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
      color: AppTheme.primary,
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
      color: AppTheme.success,
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
    final cs = Theme.of(context).colorScheme;
    // Card "fintech" épurée : fond plein + ombre douce plutôt que le style
    // Material `Card` par défaut — icône dans un badge rond, badge de
    // tendance en pilule colorée, gros chiffre en avant (cf. références
    // fournies : dashboards à cartes métriques compactes).
    return Container(
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: [
          BoxShadow(
            color: cs.shadow.withValues(alpha: 0.06),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: color.withValues(alpha: 0.12),
                        shape: BoxShape.circle,
                      ),
                      alignment: Alignment.center,
                      child: Icon(icon, size: 18, color: color),
                    ),
                    const Spacer(),
                    if (trendPercent != null && trendPercent != 0)
                      _MiniTrend(trendPercent: trendPercent!),
                  ],
                ),
                const SizedBox(height: 14),
                Text(
                  value,
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  label,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
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
    final color = isUp ? AppTheme.success : AppTheme.errorColor;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        '${isUp ? "↑" : "↓"}${trendPercent.abs().toStringAsFixed(0)}%',
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w700,
          fontSize: 11,
        ),
      ),
    );
  }
}

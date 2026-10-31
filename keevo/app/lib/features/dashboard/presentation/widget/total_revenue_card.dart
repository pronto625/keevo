import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:keevo_core/theme/app_theme.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// TotalRevenueCard — « Chiffre d'affaires total » of the owner dashboard:
/// CA of all stores over the selected [RevenuePeriod] (COMPLETED sales), with
/// the number of sales as subtitle. Same visual language as
/// MorningSummaryHeroCard.
///
/// [revenue] null = loading / unavailable (an em dash is shown).
/// [onDeviceOnly] adds « Données de cet appareil »: the figure only covers
/// the sales synced to this phone (offline), never shown as a global total.
class TotalRevenueCard extends StatelessWidget {
  final RevenuePeriod period;
  final PeriodRevenue? revenue;
  final bool onDeviceOnly;
  final ValueChanged<RevenuePeriod> onPeriodChanged;

  const TotalRevenueCard({
    super.key,
    required this.period,
    required this.revenue,
    required this.onDeviceOnly,
    required this.onPeriodChanged,
  });

  static String salesCountLabel(int count) => switch (count) {
        0 => 'Aucune vente',
        1 => '1 vente',
        _ => '${NumberFormat.decimalPattern('fr_FR').format(count)} ventes',
      };

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final currencyFormat = NumberFormat.currency(
      locale: 'fr_FR',
      symbol: 'XAF',
      decimalDigits: 0,
    );
    final r = revenue;

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      padding: const EdgeInsets.all(20),
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
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: AppTheme.softTint(Theme.of(context).brightness),
                  shape: BoxShape.circle,
                ),
                alignment: Alignment.center,
                child: Icon(
                  Icons.account_balance_wallet_rounded,
                  size: 18,
                  color: cs.primary,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  "Chiffre d'affaires total",
                  style: textTheme.titleSmall?.copyWith(
                    color: cs.onSurface,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final p in RevenuePeriod.values)
                ChoiceChip(
                  label: Text(p.label),
                  selected: p == period,
                  onSelected: (_) => onPeriodChanged(p),
                  labelStyle: TextStyle(
                    fontSize: 12,
                    fontWeight:
                        p == period ? FontWeight.w600 : FontWeight.w400,
                  ),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            r == null ? '—' : currencyFormat.format(r.amount),
            style: TextStyle(
              fontSize: 30,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
              letterSpacing: -0.5,
            ),
          ),
          const SizedBox(height: 4),
          if (r != null)
            Text(
              salesCountLabel(r.salesCount),
              style: textTheme.bodyMedium?.copyWith(
                color: cs.onSurfaceVariant,
              ),
            ),
          if (r != null && onDeviceOnly) ...[
            const SizedBox(height: 6),
            Row(
              children: [
                Icon(Icons.phone_android_rounded,
                    size: 14, color: cs.onSurfaceVariant),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(
                    'Données de cet appareil',
                    style: textTheme.bodySmall?.copyWith(
                      color: cs.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

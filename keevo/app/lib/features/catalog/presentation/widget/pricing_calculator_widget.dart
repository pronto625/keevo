import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

import '../../../../core/utils/percent_format.dart';

/// Widget de calcul de marge en temps réel.
///
/// Reproduit fidèlement la logique du backend [PricingCalculator] :
///   - totalCost = buyPrice + transportCost
///   - grossMarginXaf = sellingPrice - totalCost
  ///   - marginPercentage = grossMarginXaf / totalCost * 100  (AC2: base=totalCost)
///
/// La couleur du bandeau suit [MarginThreshold] :
///   - LOSS   (<  0 %) → rouge
///   - LOW    (<  10%) → rouge
///   - MODERATE (< 20%) → orange
///   - PROFITABLE (≥ 20%) → vert
///
/// Sans prix d'achat (coût total à 0), la marge n'est pas calculable : taux et
/// marge affichés « — », badge neutre, et une invite à saisir le prix d'achat.
class PricingCalculatorWidget extends StatelessWidget {
  const PricingCalculatorWidget({
    super.key,
    required this.sellingPrice,
    required this.buyPrice,
    required this.transportCost,
  });

  final int sellingPrice;
  final int buyPrice;
  final int transportCost;

  // ---------- logique miroir du backend ----------

  int get _totalCost => buyPrice + transportCost;

  bool get _costUnknown => _totalCost <= 0;

  int get _grossMarginXaf => sellingPrice - _totalCost;

  double get _marginPercentage =>
      _totalCost == 0 ? 0.0 : _grossMarginXaf / _totalCost * 100;

  _MarginLevel get _level {
    final pct = _marginPercentage;
    if (pct < 0) return _MarginLevel.loss;
    if (pct < 10) return _MarginLevel.low;
    if (pct < 20) return _MarginLevel.moderate;
    return _MarginLevel.profitable;
  }

  // ---------- build ----------

  @override
  Widget build(BuildContext context) {
    final unknown = _costUnknown;
    final level = _level;
    final color = unknown
        ? Theme.of(context).colorScheme.onSurfaceVariant
        : level.color;
    final pct = _marginPercentage;
    final isLoss = !unknown && _grossMarginXaf < 0;

    return Container(
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.analytics_outlined, color: color, size: 18),
                const SizedBox(width: 8),
                Text(
                  'Calcul de marge',
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: color,
                        fontWeight: FontWeight.w600,
                      ),
                ),
                const Spacer(),
                if (!unknown) _Badge(label: level.label, color: color),
              ],
            ),
            const SizedBox(height: 12),
            _Row(
              label: 'Coût total',
              value: unknown ? '—' : _formatXaf(_totalCost),
              context: context,
            ),
            _Row(
              label: 'Marge brute',
              value: unknown ? '—' : _formatXaf(_grossMarginXaf),
              context: context,
              valueColor: isLoss ? AppTheme.errorColor : null,
            ),
            _Row(
              label: 'Taux de marge',
              value: unknown ? '—' : formatPercentFr(pct),
              context: context,
              valueColor: color,
              bold: true,
            ),
            if (unknown) ...[
              const SizedBox(height: 8),
              Text(
                "Renseignez le prix d'achat pour calculer la marge",
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: color,
                      fontStyle: FontStyle.italic,
                    ),
              ),
            ],
            if (isLoss) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(
                  color: AppTheme.errorColor.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(AppTheme.radiusS),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber_rounded,
                        color: AppTheme.errorColor, size: 16),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        'Prix de vente inférieur au coût — vous vendez à perte',
                        style: TextStyle(
                          color: AppTheme.errorColor,
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  static String _formatXaf(int value) {
    final abs = value.abs().toString().replaceAllMapped(
          RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))'),
          (m) => '${m[1]} ',
        );
    return value < 0 ? '- $abs XAF' : '$abs XAF';
  }
}

// ---------- helpers privés ----------

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.4,
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.label,
    required this.value,
    required this.context,
    this.valueColor,
    this.bold = false,
  });
  final String label;
  final String value;
  final BuildContext context;
  final Color? valueColor;
  final bool bold;

  @override
  Widget build(BuildContext ctx) {
    final baseStyle = Theme.of(ctx).textTheme.bodySmall;
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: baseStyle?.copyWith(color: Theme.of(ctx).colorScheme.onSurfaceVariant)),
          Text(
            value,
            style: baseStyle?.copyWith(
              color: valueColor,
              fontWeight: bold ? FontWeight.w600 : FontWeight.normal,
            ),
          ),
        ],
      ),
    );
  }
}

// ---------- enum local ----------

enum _MarginLevel {
  loss,
  low,
  moderate,
  profitable;

  Color get color {
    switch (this) {
      case _MarginLevel.loss:
      case _MarginLevel.low:
        return AppTheme.errorColor;
      case _MarginLevel.moderate:
        return AppTheme.warning;
      case _MarginLevel.profitable:
        return AppTheme.success;
    }
  }

  String get label {
    switch (this) {
      case _MarginLevel.loss:
        return 'PERTE';
      case _MarginLevel.low:
        return 'FAIBLE';
      case _MarginLevel.moderate:
        return 'MODÉRÉE';
      case _MarginLevel.profitable:
        return 'RENTABLE';
    }
  }
}

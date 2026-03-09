import 'package:flutter/material.dart';

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
    final level = _level;
    final color = level.color;
    final label = level.label;
    final pct = _marginPercentage;
    final isLoss = _grossMarginXaf < 0;

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: color.withOpacity(0.5)),
      ),
      color: color.withOpacity(0.06),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.analytics_rounded, color: color, size: 18),
                const SizedBox(width: 8),
                Text(
                  'Calcul de marge',
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: color,
                        fontWeight: FontWeight.w600,
                      ),
                ),
                const Spacer(),
                _Badge(label: label, color: color),
              ],
            ),
            const SizedBox(height: 12),
            _Row(
              label: 'Coût total',
              value: _formatXaf(_totalCost),
              context: context,
            ),
            _Row(
              label: 'Marge brute',
              value: _formatXaf(_grossMarginXaf),
              context: context,
              valueColor: isLoss ? Colors.red : null,
            ),
            _Row(
              label: 'Taux de marge',
              value: '${pct.toStringAsFixed(1)} %',
              context: context,
              valueColor: color,
              bold: true,
            ),
            if (isLoss) ...
              [
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 10, vertical: 8),
                  decoration: BoxDecoration(
                    color: Colors.red.shade50,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.red.shade300),
                  ),
                  child: const Row(
                    children: [
                      Icon(Icons.warning_amber_rounded,
                          color: Colors.red, size: 16),
                      SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          '\u26a0 Prix de vente inférieur au coût — vous vendez à perte',
                          style: TextStyle(
                            color: Colors.red,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            if (isLoss) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.red.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.red.shade200),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber_rounded,
                        color: Colors.red.shade700, size: 16),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        '⚠ Prix de vente inférieur au coût — vous vendez à perte',
                        style: TextStyle(
                          color: Colors.red.shade700,
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
        color: color.withOpacity(0.15),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withOpacity(0.4)),
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
          Text(label, style: baseStyle?.copyWith(color: Colors.black54)),
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
        return Colors.red;
      case _MarginLevel.moderate:
        return Colors.orange;
      case _MarginLevel.profitable:
        return Colors.green;
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

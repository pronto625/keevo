import 'package:flutter/material.dart';

import '../../domain/model/product_profitability_model.dart';

/// RankingMetricToggle — CA / Ventes / Panier moyen chips shared by the
/// Boutiques (Story 7.4) and Équipe (v1s-14-2) rankings. Selecting a metric
/// re-ranks locally; it never re-fetches.
class RankingMetricToggle extends StatelessWidget {
  final RankingMetric selected;
  final ValueChanged<RankingMetric> onChanged;

  const RankingMetricToggle({super.key, required this.selected, required this.onChanged});

  static const _options = [
    (RankingMetric.ca, 'CA'),
    (RankingMetric.salesCount, 'Ventes'),
    (RankingMetric.avgBasket, 'Panier Moyen'),
  ];

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: _options.map((pair) {
          final (option, label) = pair;
          final isSelected = selected == option;
          final theme = Theme.of(context);
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(label),
              selected: isSelected,
              onSelected: (_) => onChanged(option),
              selectedColor: theme.colorScheme.primary,
              backgroundColor: theme.colorScheme.surface,
              showCheckmark: false,
              shape: const StadiumBorder(),
              side: BorderSide(
                color: isSelected
                    ? theme.colorScheme.primary
                    : theme.colorScheme.outlineVariant,
              ),
              labelStyle: TextStyle(
                color: isSelected
                    ? theme.colorScheme.onPrimary
                    : theme.colorScheme.onSurfaceVariant,
                fontWeight:
                    isSelected ? FontWeight.w600 : FontWeight.normal,
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../domain/model/product_profitability_model.dart';

/// SortChips — row of FilterChips for profitability sort selection.
///
/// Active chip: filled with primary color, white text.
/// Inactive chip: outlined.
///
/// Story 7.4 — Task 17.3.
class SortChips extends StatelessWidget {
  final SortOption selected;
  final ValueChanged<SortOption> onChanged;

  const SortChips({
    super.key,
    required this.selected,
    required this.onChanged,
  });

  static const _options = [
    (SortOption.marginPctDesc, 'Marge %'),
    (SortOption.marginXafDesc, 'Marge XAF'),
    (SortOption.caDesc, 'CA'),
    (SortOption.unitsDesc, 'Unités'),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: _options.map((pair) {
          final (option, label) = pair;
          final isSelected = selected == option;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilterChip(
              label: Text(label),
              selected: isSelected,
              onSelected: (_) => onChanged(option),
              selectedColor: theme.colorScheme.primary,
              backgroundColor: Colors.transparent,
              side: BorderSide(
                color: isSelected
                    ? theme.colorScheme.primary
                    : theme.colorScheme.outline,
              ),
              labelStyle: TextStyle(
                color: isSelected ? Colors.white : null,
                fontWeight:
                    isSelected ? FontWeight.w600 : FontWeight.normal,
              ),
              showCheckmark: false,
            ),
          );
        }).toList(),
      ),
    );
  }
}

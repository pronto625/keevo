import 'package:flutter/material.dart';

/// Period preset of the profitability screens.
enum PeriodPreset { today, last7, last30, custom }

/// Selected period, kept as a preset *kind* so that the window follows the
/// calendar (after midnight "7 jours" is the 7 days ending today, not the
/// window computed yesterday). Only [PeriodPreset.custom] keeps fixed dates.
class PeriodSelection {
  final PeriodPreset preset;
  final DateTime? customFrom;
  final DateTime? customTo;

  const PeriodSelection._(this.preset, [this.customFrom, this.customTo]);

  static const today = PeriodSelection._(PeriodPreset.today);
  static const last7 = PeriodSelection._(PeriodPreset.last7);
  static const last30 = PeriodSelection._(PeriodPreset.last30);

  factory PeriodSelection.custom(DateTime from, DateTime to) =>
      PeriodSelection._(PeriodPreset.custom, from, to);

  /// The concrete window for [now] (defaults to the current time).
  ({DateTime from, DateTime to}) range([DateTime? now]) {
    final n = now ?? DateTime.now();
    final today = DateTime(n.year, n.month, n.day);
    return switch (preset) {
      PeriodPreset.today => (from: today, to: today),
      PeriodPreset.last7 =>
        (from: today.subtract(const Duration(days: 6)), to: today),
      PeriodPreset.last30 =>
        (from: today.subtract(const Duration(days: 29)), to: today),
      PeriodPreset.custom => (from: customFrom!, to: customTo!),
    };
  }
}

/// PeriodFilterChips — row of ChoiceChips for date range selection.
///
/// Emits [onChanged] when a preset or custom range is selected. The selected
/// period is managed by the parent/provider.
///
/// Story 7.4 — Task 17.2.
class PeriodFilterChips extends StatelessWidget {
  final PeriodSelection selected;
  final void Function(PeriodSelection selection) onChanged;

  const PeriodFilterChips({
    super.key,
    required this.selected,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final preset = selected.preset;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          _chip(context, "Aujourd'hui", preset == PeriodPreset.today,
              () => onChanged(PeriodSelection.today)),
          const SizedBox(width: 8),
          _chip(context, '7 jours', preset == PeriodPreset.last7,
              () => onChanged(PeriodSelection.last7)),
          const SizedBox(width: 8),
          _chip(context, '30 jours', preset == PeriodPreset.last30,
              () => onChanged(PeriodSelection.last30)),
          const SizedBox(width: 8),
          _chip(
            context,
            'Personnalisé',
            preset == PeriodPreset.custom,
            () async {
              final now = DateTime.now();
              final current = selected.range(now);
              final range = await showDateRangePicker(
                context: context,
                firstDate: DateTime(now.year - 2),
                lastDate: now,
                initialDateRange:
                    DateTimeRange(start: current.from, end: current.to),
                locale: const Locale('fr'),
              );
              if (range != null && context.mounted) {
                onChanged(PeriodSelection.custom(range.start, range.end));
              }
            },
          ),
        ],
      ),
    );
  }

  Widget _chip(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onSelected,
  ) {
    final theme = Theme.of(context);
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onSelected(),
      selectedColor: theme.colorScheme.primary,
      backgroundColor: theme.colorScheme.surface,
      showCheckmark: false,
      shape: const StadiumBorder(),
      side: BorderSide(
        color: selected
            ? theme.colorScheme.primary
            : theme.colorScheme.outlineVariant,
      ),
      labelStyle: TextStyle(
        color: selected
            ? theme.colorScheme.onPrimary
            : theme.colorScheme.onSurfaceVariant,
        fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
      ),
    );
  }
}

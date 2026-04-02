import 'package:flutter/material.dart';

/// PeriodFilterChips — row of ChoiceChips for date range selection.
///
/// Emits [onPeriodChanged] with (from, to) when a preset or custom range
/// is selected. The selected period is managed by the parent/provider.
///
/// Story 7.4 — Task 17.2.
class PeriodFilterChips extends StatelessWidget {
  final DateTime selectedFrom;
  final DateTime selectedTo;
  final void Function(DateTime from, DateTime to) onPeriodChanged;

  const PeriodFilterChips({
    super.key,
    required this.selectedFrom,
    required this.selectedTo,
    required this.onPeriodChanged,
  });

  // ── Preset helpers ────────────────────────────────────────────────────────

  static ({DateTime from, DateTime to}) todayRange() {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return (from: today, to: today);
  }

  static ({DateTime from, DateTime to}) last7Range() {
    final now = DateTime.now();
    final to = DateTime(now.year, now.month, now.day);
    final from = to.subtract(const Duration(days: 6));
    return (from: from, to: to);
  }

  static ({DateTime from, DateTime to}) last30Range() {
    final now = DateTime.now();
    final to = DateTime(now.year, now.month, now.day);
    final from = to.subtract(const Duration(days: 29));
    return (from: from, to: to);
  }

  // ── Preset matching ───────────────────────────────────────────────────────

  bool _isToday() {
    final r = todayRange();
    return selectedFrom == r.from && selectedTo == r.to;
  }

  bool _is7() {
    final r = last7Range();
    return selectedFrom == r.from && selectedTo == r.to;
  }

  bool _is30() {
    final r = last30Range();
    return selectedFrom == r.from && selectedTo == r.to;
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          _chip(context, "Aujourd'hui", _isToday(), () {
            final r = todayRange();
            onPeriodChanged(r.from, r.to);
          }),
          const SizedBox(width: 8),
          _chip(context, '7 jours', _is7(), () {
            final r = last7Range();
            onPeriodChanged(r.from, r.to);
          }),
          const SizedBox(width: 8),
          _chip(context, '30 jours', _is30(), () {
            final r = last30Range();
            onPeriodChanged(r.from, r.to);
          }),
          const SizedBox(width: 8),
          _chip(
            context,
            'Personnalisé',
            !_isToday() && !_is7() && !_is30(),
            () async {
              final now = DateTime.now();
              final range = await showDateRangePicker(
                context: context,
                firstDate: DateTime(now.year - 2),
                lastDate: now,
                initialDateRange:
                    DateTimeRange(start: selectedFrom, end: selectedTo),
                locale: const Locale('fr'),
              );
              if (range != null && context.mounted) {
                onPeriodChanged(range.start, range.end);
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
      labelStyle: TextStyle(
        color: selected ? Colors.white : null,
        fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
      ),
    );
  }
}

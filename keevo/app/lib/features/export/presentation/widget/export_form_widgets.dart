import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/date_range_clamp.dart';
import '../export_presenter.dart';

/// Shared pieces of the export forms — « Exporter » (v1s-18-4) and « Données
/// de cet appareil » (v1s-18-5): store / period pickers, section titles,
/// info banners.

/// Period presets of the « Période » filter (`null` = all dates).
enum ExportPeriod { today, week, month, custom }

/// A choice of [showExportOptionSheet]: a `null` [value] is « all ».
class ExportChoice<T> {
  final T? value;
  const ExportChoice(this.value);
}

/// A chosen period: preset and inclusive bounds (all `null` = all dates).
class ExportPeriodSelection {
  final ExportPeriod? period;
  final DateTime? from;
  final DateTime? to;

  const ExportPeriodSelection(this.period, this.from, this.to);
}

/// Subtitle of the « Période » tile.
String exportPeriodLabel(ExportPeriod? period, DateTime? from, DateTime? to) =>
    switch (period) {
      ExportPeriod.today => 'Aujourd\'hui',
      ExportPeriod.week => '7 jours',
      ExportPeriod.month => 'Mois (30 derniers jours)',
      ExportPeriod.custom => ExportPresenter.period(from, to),
      null => 'Toutes les dates',
    };

/// Bottom sheet with « all » + options (same pattern as the audit
/// filters). `null` when dismissed, a choice with a `null` value for « all ».
Future<ExportChoice<T>?> showExportOptionSheet<T>(
  BuildContext context, {
  required String title,
  required T? selected,
  required String allLabel,
  required List<(T, String)> options,
}) {
  return showModalBottomSheet<ExportChoice<T>>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (context) {
      final cs = Theme.of(context).colorScheme;
      Widget tile(T? value, String label) => ListTile(
            title: Text(label),
            trailing: value == selected
                ? Icon(Icons.check_rounded, color: cs.primary)
                : null,
            onTap: () => Navigator.of(context).pop(ExportChoice<T>(value)),
          );
      return SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * 0.7),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child:
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    tile(null, allLabel),
                    for (final o in options) tile(o.$1, o.$2),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

/// « Période » picker: Aujourd'hui / 7 jours / Mois / Personnalisé / Toutes
/// les dates. `null` when dismissed (or the custom range picker is).
Future<ExportPeriodSelection?> pickExportPeriod(
  BuildContext context, {
  required ExportPeriod? current,
  DateTime? from,
  DateTime? to,
}) async {
  final choice = await showExportOptionSheet<ExportPeriod>(
    context,
    title: 'Période',
    selected: current,
    allLabel: 'Toutes les dates',
    options: const [
      (ExportPeriod.today, 'Aujourd\'hui'),
      (ExportPeriod.week, '7 jours'),
      (ExportPeriod.month, 'Mois (30 derniers jours)'),
      (ExportPeriod.custom, 'Personnalisé…'),
    ],
  );
  if (choice == null || !context.mounted) return null;
  final now = DateTime.now();
  final startOfToday = DateTime(now.year, now.month, now.day);
  final endOfToday = startOfToday
      .add(const Duration(days: 1))
      .subtract(const Duration(milliseconds: 1));
  switch (choice.value) {
    case null:
      return const ExportPeriodSelection(null, null, null);
    case ExportPeriod.today:
      return ExportPeriodSelection(
          ExportPeriod.today, startOfToday, endOfToday);
    case ExportPeriod.week:
      return ExportPeriodSelection(ExportPeriod.week,
          startOfToday.subtract(const Duration(days: 6)), endOfToday);
    case ExportPeriod.month:
      return ExportPeriodSelection(ExportPeriod.month,
          startOfToday.subtract(const Duration(days: 29)), endOfToday);
    case ExportPeriod.custom:
      final first = DateTime(2020);
      final currentRange = from != null && to != null
          ? DateTimeRange(start: from, end: to)
          : null;
      final range = await showDateRangePicker(
        context: context,
        firstDate: first,
        lastDate: now,
        initialDateRange: clampDateRange(currentRange, first, now),
        helpText: 'Choisir une période',
      );
      if (range == null) return null;
      final end = DateTime(range.end.year, range.end.month, range.end.day)
          .add(const Duration(days: 1))
          .subtract(const Duration(milliseconds: 1));
      return ExportPeriodSelection(ExportPeriod.custom, range.start, end);
  }
}

/// Section title of the export forms.
class ExportSectionTitle extends StatelessWidget {
  final String text;
  const ExportSectionTitle(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(
        text,
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

/// Muted info banner (icon, text, optional action).
class ExportInfoBanner extends StatelessWidget {
  final IconData icon;
  final String text;
  final Widget? action;

  const ExportInfoBanner(
      {super.key, required this.icon, required this.text, this.action});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(AppTheme.radiusS),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: cs.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
          ),
          if (action != null) action!,
        ],
      ),
    );
  }
}

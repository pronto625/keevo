import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/dashboard_snapshot.dart';

/// SalesEvolutionChart — bar chart with period filter tabs and touch tooltips.
///
/// Story 7.1 — AC8.
class SalesEvolutionChart extends StatefulWidget {
  final List<DailyCA> data;
  final ChartPeriod period;
  final ValueChanged<ChartPeriod>? onPeriodChanged;

  const SalesEvolutionChart({
    super.key,
    required this.data,
    this.period = ChartPeriod.daily,
    this.onPeriodChanged,
  });

  @override
  State<SalesEvolutionChart> createState() => _SalesEvolutionChartState();
}

class _SalesEvolutionChartState extends State<SalesEvolutionChart> {
  int? _selectedIndex;

  static NumberFormat? _currencyFormatInstance;
  static DateFormat? _dateFormatInstance;
  static DateFormat? _monthFormatInstance;

  static NumberFormat get _currencyFormat =>
      _currencyFormatInstance ??= NumberFormat.currency(
        locale: 'fr_FR',
        symbol: 'XAF',
        decimalDigits: 0,
      );

  static DateFormat get _dateFormat =>
      _dateFormatInstance ??= DateFormat('dd/MM', 'fr_FR');

  static DateFormat get _monthFormat =>
      _monthFormatInstance ??= DateFormat('MMM yy', 'fr_FR');

  String _formatLabel(DailyCA entry) {
    switch (widget.period) {
      case ChartPeriod.daily:
        return _dateFormat.format(entry.date);
      case ChartPeriod.weekly:
        return 'Sem. ${_dateFormat.format(entry.date)}';
      case ChartPeriod.monthly:
        return _monthFormat.format(entry.date);
      case ChartPeriod.yearly:
        return '${entry.date.year}';
    }
  }

  @override
  void didUpdateWidget(covariant SalesEvolutionChart oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.period != widget.period) {
      _selectedIndex = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            'Évolution des ventes',
            style: Theme.of(context)
                .textTheme
                .titleSmall
                ?.copyWith(fontWeight: FontWeight.w600),
          ),
        ),
        const SizedBox(height: 8),

        // Period filter chips
        if (widget.onPeriodChanged != null)
          SizedBox(
            height: 32,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              children: ChartPeriod.values.map((p) {
                final selected = p == widget.period;
                return Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: ChoiceChip(
                    label: Text(_periodLabel(p)),
                    selected: selected,
                    onSelected: (_) => widget.onPeriodChanged!(p),
                    labelStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                    ),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                );
              }).toList(),
            ),
          ),

        if (_selectedIndex != null && _selectedIndex! < widget.data.length)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: Text(
              '${_formatLabel(widget.data[_selectedIndex!])} — ${_currencyFormat.format(widget.data[_selectedIndex!].amount)}',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: const Color(0xFF3B5BDB),
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
        const SizedBox(height: 8),
        SizedBox(
          height: 120,
          child: GestureDetector(
            onTapDown: (details) => _onTouch(details.localPosition),
            onPanUpdate: (details) => _onTouch(details.localPosition),
            onPanEnd: (_) => setState(() => _selectedIndex = null),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: CustomPaint(
                size: const Size(double.infinity, 120),
                painter: _BarChartPainter(
                  data: widget.data,
                  selectedIndex: _selectedIndex,
                  barColor: const Color(0xFF3B5BDB),
                  selectedColor: const Color(0xFF4DABF7),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  void _onTouch(Offset localPosition) {
    if (widget.data.isEmpty) return;
    final renderWidth = (context.size?.width ?? 300) - 32;
    final barCount = widget.data.length;
    const gap = 2.0;
    final barWidth = (renderWidth - (barCount - 1) * gap) / barCount;
    final idx = (localPosition.dx / (barWidth + gap)).floor();
    if (idx >= 0 && idx < barCount && idx != _selectedIndex) {
      setState(() => _selectedIndex = idx);
    }
  }

  static String _periodLabel(ChartPeriod p) {
    switch (p) {
      case ChartPeriod.daily:
        return 'Jour';
      case ChartPeriod.weekly:
        return 'Semaine';
      case ChartPeriod.monthly:
        return 'Mois';
      case ChartPeriod.yearly:
        return 'Année';
    }
  }
}

class _BarChartPainter extends CustomPainter {
  final List<DailyCA> data;
  final int? selectedIndex;
  final Color barColor;
  final Color selectedColor;

  _BarChartPainter({
    required this.data,
    this.selectedIndex,
    required this.barColor,
    required this.selectedColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (data.isEmpty) return;

    final maxVal = data
        .map((d) => d.amount.toDouble())
        .reduce((a, b) => a > b ? a : b);
    if (maxVal == 0) return;

    final barCount = data.length;
    const gap = 2.0;
    final barWidth = (size.width - (barCount - 1) * gap) / barCount;

    final paint = Paint()..style = PaintingStyle.fill;

    for (int i = 0; i < barCount; i++) {
      final ratio = data[i].amount / maxVal;
      final barHeight = ratio * (size.height - 4);
      final x = i * (barWidth + gap);

      paint.color = (i == selectedIndex)
          ? selectedColor
          : barColor.withValues(alpha: 0.5);

      final rect = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, size.height - barHeight, barWidth, barHeight),
        const Radius.circular(1.5),
      );
      canvas.drawRRect(rect, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _BarChartPainter oldDelegate) =>
      oldDelegate.selectedIndex != selectedIndex || oldDelegate.data != data;
}

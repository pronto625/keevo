import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/dashboard_snapshot.dart';

/// SalesEvolutionChart — bar chart with period filter tabs and touch tooltips.
/// Axes labels, grid lines, zero-fill, and day range selector included.
///
/// Story 7.1 — AC8.
class SalesEvolutionChart extends StatefulWidget {
  final List<DailyCA> data;
  final ChartPeriod period;
  /// Number of days shown when [period] == [ChartPeriod.daily]. One of 7 / 14 / 30.
  final int daysRange;
  final ValueChanged<ChartPeriod>? onPeriodChanged;
  /// Callback for day-range chip selection. Only provided when period == daily.
  final ValueChanged<int>? onDaysRangeChanged;

  const SalesEvolutionChart({
    super.key,
    required this.data,
    this.period = ChartPeriod.daily,
    this.daysRange = 7,
    this.onPeriodChanged,
    this.onDaysRangeChanged,
  });

  @override
  State<SalesEvolutionChart> createState() => _SalesEvolutionChartState();
}

class _SalesEvolutionChartState extends State<SalesEvolutionChart> {
  int? _selectedIndex;

  static const _barColor = Color(0xFF3B5BDB);
  static const _selectedBarColor = Color(0xFF4DABF7);

  static NumberFormat? _currencyFormatInstance;
  static NumberFormat get _currencyFormat =>
      _currencyFormatInstance ??= NumberFormat.currency(
        locale: 'fr_FR',
        symbol: 'XAF',
        decimalDigits: 0,
      );

  @override
  void didUpdateWidget(covariant SalesEvolutionChart oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.period != widget.period ||
        oldWidget.daysRange != widget.daysRange) {
      _selectedIndex = null;
    }
  }

  String _formatTooltip(DailyCA entry) {
    switch (widget.period) {
      case ChartPeriod.daily:
        return '${DateFormat('EEE dd/MM', 'fr_FR').format(entry.date)} — ${_currencyFormat.format(entry.amount)}';
      case ChartPeriod.weekly:
        return 'Sem. du ${DateFormat('dd/MM', 'fr_FR').format(entry.date)} — ${_currencyFormat.format(entry.amount)}';
      case ChartPeriod.monthly:
        return '${DateFormat('MMMM yyyy', 'fr_FR').format(entry.date)} — ${_currencyFormat.format(entry.amount)}';
      case ChartPeriod.yearly:
        return '${entry.date.year} — ${_currencyFormat.format(entry.amount)}';
    }
  }

  /// Computes x-axis label strings (empty string = no label for that bar).
  List<String> _computeXLabels() {
    final n = widget.data.length;
    if (n == 0) return [];
    int step;
    switch (widget.period) {
      case ChartPeriod.daily:
        step = n <= 7 ? 1 : n <= 14 ? 2 : 5;
      case ChartPeriod.weekly:
        step = 2;
      case ChartPeriod.monthly:
        step = 1;
      case ChartPeriod.yearly:
        step = 1;
    }
    return List.generate(n, (i) {
      if (i == 0 || i == n - 1 || i % step == 0) return _shortLabel(widget.data[i]);
      return '';
    });
  }

  String _shortLabel(DailyCA entry) {
    switch (widget.period) {
      case ChartPeriod.daily:
      case ChartPeriod.weekly:
        return DateFormat('dd/MM', 'fr_FR').format(entry.date);
      case ChartPeriod.monthly:
        return DateFormat('MMM', 'fr_FR').format(entry.date);
      case ChartPeriod.yearly:
        return '${entry.date.year}';
    }
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;
    final xLabels = _computeXLabels();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            'Évolution des ventes',
            style: textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
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

        // Day range sub-selector (only when period == daily)
        if (widget.period == ChartPeriod.daily &&
            widget.onDaysRangeChanged != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
            child: Row(
              children: [7, 14, 30].map((d) {
                final sel = d == widget.daysRange;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: GestureDetector(
                    onTap: () => widget.onDaysRangeChanged!(d),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 3),
                      decoration: BoxDecoration(
                        color: sel
                            ? _barColor.withValues(alpha: 0.12)
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(
                          color: sel ? _barColor : colorScheme.outlineVariant,
                          width: 1,
                        ),
                      ),
                      child: Text(
                        '${d}j',
                        style: textTheme.labelSmall?.copyWith(
                          fontWeight:
                              sel ? FontWeight.w700 : FontWeight.w400,
                          color: sel
                              ? _barColor
                              : colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),

        const SizedBox(height: 6),

        // Touch tooltip
        if (_selectedIndex != null && _selectedIndex! < widget.data.length)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
            child: Text(
              _formatTooltip(widget.data[_selectedIndex!]),
              style: textTheme.bodySmall?.copyWith(
                color: _barColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),

        // Bar chart (taller to fit axes)
        SizedBox(
          height: 150,
          child: GestureDetector(
            onTapDown: (d) => _onTouch(d.localPosition),
            onPanUpdate: (d) => _onTouch(d.localPosition),
            onPanEnd: (_) => setState(() => _selectedIndex = null),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: CustomPaint(
                size: const Size(double.infinity, 150),
                painter: _BarChartPainter(
                  data: widget.data,
                  selectedIndex: _selectedIndex,
                  barColor: _barColor,
                  selectedColor: _selectedBarColor,
                  xLabels: xLabels,
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
    const leftPad = _BarChartPainter.kLeftPad;
    final renderWidth =
        (context.size?.width ?? 300) - 32 - leftPad; // 32 = horizontal padding
    final barCount = widget.data.length;
    const gap = 2.0;
    final barWidth = (renderWidth - (barCount - 1) * gap) / barCount;
    final adjustedX = localPosition.dx - leftPad;
    if (adjustedX < 0) return;
    final idx = (adjustedX / (barWidth + gap)).floor();
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
  final List<String> xLabels;

  /// Left padding — reserved for y-axis labels.
  static const double kLeftPad = 44.0;

  /// Bottom padding — reserved for x-axis labels.
  static const double kBottomPad = 20.0;

  const _BarChartPainter({
    required this.data,
    this.selectedIndex,
    required this.barColor,
    required this.selectedColor,
    required this.xLabels,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (data.isEmpty) return;

    final maxVal = data
        .map((d) => d.amount.toDouble())
        .reduce((a, b) => a > b ? a : b);

    final chartW = size.width - kLeftPad;
    final chartH = size.height - kBottomPad;
    const originX = kLeftPad;
    final originY = chartH;

    final labelStyle = TextStyle(fontSize: 9, color: Colors.grey[500]);

    // ── Y-axis grid lines + labels (0, 50%, 100%) ──────────────
    final gridPaint = Paint()
      ..color = Colors.grey.withValues(alpha: 0.18)
      ..strokeWidth = 0.5;

    for (int level = 0; level <= 2; level++) {
      final ratio = level / 2.0;
      final y = originY - ratio * chartH;
      canvas.drawLine(
          Offset(originX - 4, y), Offset(size.width, y), gridPaint);

      final val = maxVal > 0 ? (ratio * maxVal).round() : 0;
      final tp = TextPainter(
        text: TextSpan(text: _compactNum(val), style: labelStyle),
        textDirection: ui.TextDirection.ltr,
      )..layout();
      tp.paint(
        canvas,
        Offset(
          (originX - tp.width - 5).clamp(0.0, originX),
          y - tp.height / 2,
        ),
      );
    }

    if (maxVal == 0) return; // grid shown, no bars

    // ── Bars ──────────────────────────────────────────────────
    final n = data.length;
    const gap = 2.0;
    final barWidth = (chartW - (n - 1) * gap) / n;
    final paint = Paint()..style = PaintingStyle.fill;

    for (int i = 0; i < n; i++) {
      final isZero = data[i].amount == 0;
      final barH = isZero
          ? 3.0
          : (data[i].amount / maxVal * chartH).clamp(3.0, chartH);
      final x = originX + i * (barWidth + gap);

      paint.color = (i == selectedIndex)
          ? selectedColor
          : isZero
              ? barColor.withValues(alpha: 0.15)
              : barColor.withValues(alpha: 0.65);

      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(
              x, originY - barH, barWidth.clamp(1.0, double.infinity), barH),
          const Radius.circular(2),
        ),
        paint,
      );

      // X-axis label
      if (i < xLabels.length && xLabels[i].isNotEmpty) {
        final tp = TextPainter(
          text: TextSpan(text: xLabels[i], style: labelStyle),
          textDirection: ui.TextDirection.ltr,
        )..layout();
        final barCenter = x + barWidth / 2;
        final labelX =
            (barCenter - tp.width / 2).clamp(originX, size.width - tp.width);
        tp.paint(canvas, Offset(labelX, originY + 3));
      }
    }
  }

  static String _compactNum(int n) {
    if (n >= 1000000) {
      return '${(n / 1000000).toStringAsFixed(n >= 10000000 ? 0 : 1)}M';
    }
    if (n >= 1000) return '${n ~/ 1000}k';
    return '$n';
  }

  @override
  bool shouldRepaint(covariant _BarChartPainter old) =>
      old.selectedIndex != selectedIndex ||
      old.data != data ||
      old.xLabels != xLabels;
}

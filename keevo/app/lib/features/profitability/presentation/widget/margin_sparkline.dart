import 'dart:math' show min;

import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

import '../../domain/model/product_profitability_model.dart';

/// MarginSparkline — 7-bar chart showing daily margin trend (last 7 days).
///
/// Bars are colored by margin level (green positive, red negative).
/// Uses CustomPainter for lightweight rendering — no external chart library.
///
/// Story 7.4 — Task 17.5.
class MarginSparkline extends StatelessWidget {
  final List<DailyMarginEntry> entries;

  const MarginSparkline({super.key, required this.entries});

  @override
  Widget build(BuildContext context) {
    if (entries.isEmpty) {
      return SizedBox(
        height: 60,
        child: Center(
          child: Text(
            'Pas de données',
            style: TextStyle(color: Theme.of(context).colorScheme.outline, fontSize: 12),
          ),
        ),
      );
    }

    final cs = Theme.of(context).colorScheme;
    return ClipRect(
      child: SizedBox(
        height: 60,
        child: CustomPaint(
          painter: _SparklinePainter(
            entries: entries,
            positiveColor: AppTheme.success,
            negativeColor: cs.error,
            zeroLineColor: cs.outlineVariant,
          ),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class _SparklinePainter extends CustomPainter {
  final List<DailyMarginEntry> entries;
  final Color positiveColor;
  final Color negativeColor;
  final Color zeroLineColor;

  _SparklinePainter({
    required this.entries,
    required this.positiveColor,
    required this.negativeColor,
    required this.zeroLineColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (entries.isEmpty) return;

    final maxVal = entries.map((e) => e.marginXaf.abs()).reduce((a, b) => a > b ? a : b);
    if (maxVal == 0) return;

    final barCount = entries.length;
    // Cap bar width so the sparkline stays readable with few data points.
    const maxBarWidth = 20.0;
    final rawBarWidth = size.width / (barCount * 2 - 1);
    final barWidth = min(rawBarWidth, maxBarWidth);
    final gap = barWidth;
    final midY = size.height / 2;

    // Clamp bar height so bars never overflow the widget bounds.
    // Maximum half-height is 90% of midY so bars always stay in [0, size.height].
    final maxPaint = midY * 0.9;

    for (int i = 0; i < barCount; i++) {
      final margin = entries[i].marginXaf;
      final barColor = margin >= 0 ? positiveColor : negativeColor;
      final paint = Paint()..color = barColor..style = PaintingStyle.fill;

      final barHeight = (margin.abs() / maxVal) * maxPaint;
      final x = i * (barWidth + gap);

      final rect = margin >= 0
          ? Rect.fromLTWH(x, midY - barHeight, barWidth, barHeight)
          : Rect.fromLTWH(x, midY, barWidth, barHeight);

      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, const Radius.circular(2)),
        paint,
      );
    }

    // Zero line
    final linePaint = Paint()
      ..color = zeroLineColor
      ..strokeWidth = 0.5;
    canvas.drawLine(Offset(0, midY), Offset(size.width, midY), linePaint);
  }

  @override
  bool shouldRepaint(_SparklinePainter old) => old.entries != entries;
}

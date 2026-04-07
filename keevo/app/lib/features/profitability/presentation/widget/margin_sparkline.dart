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

    return SizedBox(
      height: 60,
      child: CustomPaint(
        painter: _SparklinePainter(
          entries: entries,
          zeroLineColor: Theme.of(context).colorScheme.outlineVariant,
        ),
        child: const SizedBox.expand(),
      ),
    );
  }
}

class _SparklinePainter extends CustomPainter {
  final List<DailyMarginEntry> entries;
  final Color zeroLineColor;

  _SparklinePainter({required this.entries, required this.zeroLineColor});

  @override
  void paint(Canvas canvas, Size size) {
    if (entries.isEmpty) return;

    final maxVal = entries.map((e) => e.marginXaf.abs()).reduce((a, b) => a > b ? a : b);
    if (maxVal == 0) return;

    final barCount = entries.length;
    final barWidth = size.width / (barCount * 2 - 1);
    final gap = barWidth;
    final midY = size.height / 2;

    for (int i = 0; i < barCount; i++) {
      final margin = entries[i].marginXaf;
      final barColor = margin >= 0 ? AppTheme.success : AppTheme.errorColor;
      final paint = Paint()..color = barColor..style = PaintingStyle.fill;

      final barHeight = (margin.abs() / maxVal) * (size.height * 0.8);
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

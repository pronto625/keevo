import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// MorningSummaryHeroCard — glassmorphism hero card with today's CA,
/// trend badge, and 7-day sparkline.
///
/// Story 7.1 — AC2, UX26.
class MorningSummaryHeroCard extends StatelessWidget {
  final int todayCA;
  final double trendPercent;
  final List<DailyCA> last7Days;
  final DateTime lastUpdated;

  const MorningSummaryHeroCard({
    super.key,
    required this.todayCA,
    required this.trendPercent,
    required this.last7Days,
    required this.lastUpdated,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final currencyFormat = NumberFormat.currency(
      locale: 'fr_FR',
      symbol: 'XAF',
      decimalDigits: 0,
    );

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: isDark
              ? [AppTheme.darkSurface, const Color(0xFF1A365D)]
              : [AppTheme.primary, AppTheme.primaryGradientEnd],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 15, sigmaY: 15),
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(
                color: Colors.white.withValues(alpha: 0.3),
              ),
              borderRadius: BorderRadius.circular(16),
            ),
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  "CA D'AUJOURD'HUI",
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: Colors.white.withValues(alpha: 0.8),
                        letterSpacing: 1.2,
                      ),
                ),
                const SizedBox(height: 4),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: Text(
                        currencyFormat.format(todayCA),
                        style: const TextStyle(
                          fontSize: 32,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                    ),
                    _TrendBadge(trendPercent: trendPercent),
                  ],
                ),
                const SizedBox(height: 16),
                SizedBox(
                  height: 40,
                  child: CustomPaint(
                    size: const Size(double.infinity, 40),
                    painter: _SparklinePainter(
                      data: last7Days.map((d) => d.amount.toDouble()).toList(),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _relativeTimestamp(lastUpdated),
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Colors.white.withValues(alpha: 0.7),
                      ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _relativeTimestamp(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inMinutes < 1) return 'Mis à jour à l\'instant';
    if (diff.inMinutes < 60) return 'Mis à jour il y a ${diff.inMinutes} min';
    return 'Mis à jour il y a ${diff.inHours}h';
  }
}

class _TrendBadge extends StatelessWidget {
  final double trendPercent;
  const _TrendBadge({required this.trendPercent});

  @override
  Widget build(BuildContext context) {
    if (trendPercent == 0) return const SizedBox.shrink();
    final isUp = trendPercent > 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: (isUp ? AppTheme.success : AppTheme.errorColor)
            .withValues(alpha: 0.2),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        '${isUp ? "↑" : "↓"} ${isUp ? "+" : ""}${trendPercent.toStringAsFixed(1)}%',
        style: TextStyle(
          color: isUp ? AppTheme.success : AppTheme.errorColor,
          fontWeight: FontWeight.w600,
          fontSize: 13,
        ),
      ),
    );
  }
}

/// Simple bar sparkline painter — 7 bars, white on semi-transparent background.
class _SparklinePainter extends CustomPainter {
  final List<double> data;
  _SparklinePainter({required this.data});

  @override
  void paint(Canvas canvas, Size size) {
    if (data.isEmpty) return;
    final maxVal = data.reduce((a, b) => a > b ? a : b);
    if (maxVal == 0) return;

    final barCount = data.length;
    const gap = 4.0;
    final barWidth = (size.width - (barCount - 1) * gap) / barCount;
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: 0.6)
      ..style = PaintingStyle.fill;

    for (int i = 0; i < barCount; i++) {
      final ratio = data[i] / maxVal;
      final barHeight = ratio * size.height;
      final x = i * (barWidth + gap);
      final rect = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, size.height - barHeight, barWidth, barHeight),
        const Radius.circular(2),
      );
      canvas.drawRRect(rect, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SparklinePainter oldDelegate) =>
      oldDelegate.data != data;
}

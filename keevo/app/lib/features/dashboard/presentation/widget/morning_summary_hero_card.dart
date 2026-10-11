import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// MorningSummaryHeroCard — hero card with today's CA, trend badge, and
/// 7-day sparkline.
///
/// Story 7.1 — AC2, UX26. Révision 2026-09-16 (v2) : carte blanche + ombre
/// douce, badge icône rond — **même langage exact que [MetricBadgeCard]**
/// (v1 utilisait un fond pastel plein, jugé fade et sans rapport avec les
/// références fournies — corrigé pour que la hero card appartienne
/// visuellement à la même famille que les cartes métriques en dessous).
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
    final cs = Theme.of(context).colorScheme;
    final currencyFormat = NumberFormat.currency(
      locale: 'fr_FR',
      symbol: 'XAF',
      decimalDigits: 0,
    );

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: [
          BoxShadow(
            color: cs.shadow.withValues(alpha: 0.06),
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: AppTheme.softTint(Theme.of(context).brightness),
                  shape: BoxShape.circle,
                ),
                alignment: Alignment.center,
                child: Icon(
                  Icons.payments_rounded,
                  size: 18,
                  color: cs.primary,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  "CA D'AUJOURD'HUI",
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: cs.onSurfaceVariant,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.8,
                      ),
                ),
              ),
              _TrendBadge(trendPercent: trendPercent),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            currencyFormat.format(todayCA),
            style: TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
              letterSpacing: -0.5,
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 40,
            child: CustomPaint(
              size: const Size(double.infinity, 40),
              painter: _SparklinePainter(
                barColor: cs.primary,
                data: last7Days.map((d) => d.amount.toDouble()).toList(),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            _relativeTimestamp(lastUpdated),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: cs.onSurfaceVariant,
                ),
          ),
        ],
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

/// Simple bar sparkline painter — 7 bars in [barColor] on the pastel card
/// background (fond plein clair, plus de "blanc sur transparent").
class _SparklinePainter extends CustomPainter {
  final List<double> data;
  final Color barColor;
  _SparklinePainter({required this.data, required this.barColor});

  @override
  void paint(Canvas canvas, Size size) {
    if (data.isEmpty) return;
    final maxVal = data.reduce((a, b) => a > b ? a : b);
    if (maxVal == 0) return;

    final barCount = data.length;
    const gap = 4.0;
    final barWidth = (size.width - (barCount - 1) * gap) / barCount;
    final paint = Paint()
      ..color = barColor.withValues(alpha: 0.75)
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
      oldDelegate.data != data || oldDelegate.barColor != barColor;
}

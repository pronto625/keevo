import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

import '../../domain/model/inventory_gap_report_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';

/// UX2 color tokens.
const _colorSuccess = AppTheme.success;
const _colorWarning = AppTheme.warning;
const _colorError = AppTheme.errorColor;

/// GapReportSummaryHeader — Material Card with 4 stat badges.
///
/// Displays total counted, concordant, surplus, and shortage counts
/// with XAF values for surplus/shortage. Story 6.3 — AC1.
class GapReportSummaryHeader extends StatelessWidget {
  final InventoryGapSummaryModel summary;

  const GapReportSummaryHeader({super.key, required this.summary});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.assessment,
                    color: theme.colorScheme.primary, size: 20),
                const SizedBox(width: 8),
                Text(
                  'Résumé',
                  style: theme.textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            const SizedBox(height: 12),
            LayoutBuilder(
              builder: (context, constraints) {
                return Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _StatBadge(
                      width: _badgeWidth(constraints),
                      label: '${summary.totalCounted} comptés',
                      semanticLabel:
                          '${summary.totalCounted} produits comptés',
                      color: theme.colorScheme.outline,
                    ),
                    _StatBadge(
                      width: _badgeWidth(constraints),
                      label: '${summary.totalConcordant} concordants',
                      semanticLabel:
                          '${summary.totalConcordant} produits concordants',
                      color: _colorSuccess,
                    ),
                    _StatBadge(
                      width: _badgeWidth(constraints),
                      label: '${summary.totalSurplus} surplus',
                      subtitle: summary.totalSurplus > 0
                          ? '+${formatXaf(summary.totalSurplusValueXaf)}'
                          : null,
                      semanticLabel:
                          '${summary.totalSurplus} produits en surplus pour ${formatXaf(summary.totalSurplusValueXaf)}',
                      color: _colorWarning,
                    ),
                    _StatBadge(
                      width: _badgeWidth(constraints),
                      label: '${summary.totalShortage} manquants',
                      subtitle: summary.totalShortage > 0
                          ? '−${formatXaf(summary.totalShortageValueXaf)}'
                          : null,
                      semanticLabel:
                          '${summary.totalShortage} produits manquants pour ${formatXaf(summary.totalShortageValueXaf)}',
                      color: _colorError,
                    ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  double _badgeWidth(BoxConstraints constraints) {
    // 4-column on expanded (>840), 2-column otherwise
    if (constraints.maxWidth > 840) {
      return (constraints.maxWidth - 3 * 8 - 32) / 4;
    }
    return (constraints.maxWidth - 8 - 32) / 2;
  }
}

class _StatBadge extends StatelessWidget {
  final double width;
  final String label;
  final String? subtitle;
  final String semanticLabel;
  final Color color;

  const _StatBadge({
    required this.width,
    required this.label,
    this.subtitle,
    required this.semanticLabel,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Semantics(
      label: semanticLabel,
      child: Container(
        width: width,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w600,
                color: color,
              ),
            ),
            if (subtitle != null) ...[
              const SizedBox(height: 2),
              Text(
                subtitle!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

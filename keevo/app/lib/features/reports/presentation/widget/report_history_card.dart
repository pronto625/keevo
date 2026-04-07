import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/report_history_model.dart';

/// ReportHistoryCard — widget displaying a single report entry in the list.
/// Story 7.2 — Task 20.
class ReportHistoryCard extends StatelessWidget {
  final ReportHistoryModel report;
  final VoidCallback? onTap;

  const ReportHistoryCard({
    super.key,
    required this.report,
    this.onTap,
  });

  static final _dateFormat = DateFormat('d MMM yyyy', 'fr_FR');
  static final _shortDateFormat = DateFormat('d MMM', 'fr_FR');
  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final statusColor = _statusColor(report.deliveryStatus, theme.colorScheme);
    final statusIcon = _statusIcon(report.deliveryStatus);
    final formattedDate = report.reportType == 'WEEKLY'
        ? _weekLabel(report.reportDate)
        : _dateFormat.format(report.reportDate);
    final typeLabelColor = _typeLabelColor(report.reportType, theme.colorScheme);

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      elevation: 1,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              // Status dot
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: statusColor,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 12),
              // Content
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          report.storeName ?? 'Boutique',
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: typeLabelColor.withAlpha(30),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            _typeLabel(report.reportType),
                            style: TextStyle(
                              fontSize: 10,
                              color: typeLabelColor,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      formattedDate,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Text(
                          _currencyFormat.format(report.totalRevenue),
                          style: theme.textTheme.bodyMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                            color: AppTheme.success,
                          ),
                        ),
                        Text(
                          '  •  ${report.totalSales} vente(s)',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              // Status icon
              Icon(statusIcon, color: statusColor, size: 20),
            ],
          ),
        ),
      ),
    );
  }

  Color _statusColor(String status, ColorScheme cs) {
    switch (status) {
      case 'SENT':
        return AppTheme.success;
      case 'FAILED':
        return AppTheme.warning;
      case 'IN_APP_ONLY':
        return cs.onSurfaceVariant;
      default:
        return cs.primary;
    }
  }

  IconData _statusIcon(String status) {
    switch (status) {
      case 'SENT':
        return Icons.check_circle_outline;
      case 'FAILED':
        return Icons.error_outline;
      case 'IN_APP_ONLY':
        return Icons.phone_android;
      default:
        return Icons.schedule;
    }
  }

  String _typeLabel(String type) {
    switch (type) {
      case 'WEEKLY':
        return '📅 HEBDO';
      case 'DAILY_COMBINED':
        return 'Multi-boutiques';
      default:
        return 'Quotidien';
    }
  }

  Color _typeLabelColor(String type, ColorScheme cs) {
    switch (type) {
      case 'WEEKLY':
        return AppTheme.warning;
      case 'DAILY_COMBINED':
        return const Color(0xFF7048E8);
      default:
        return cs.primary;
    }
  }

  /// Returns "Semaine du [Mon] au [Sun YYYY]" for weekly reports.
  String _weekLabel(DateTime sunday) {
    final monday = sunday.subtract(const Duration(days: 6));
    return 'Semaine du ${_shortDateFormat.format(monday)} au ${_dateFormat.format(sunday)}';
  }
}

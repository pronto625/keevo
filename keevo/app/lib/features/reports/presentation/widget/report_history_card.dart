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
  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final statusColor = _statusColor(report.deliveryStatus);
    final statusIcon = _statusIcon(report.deliveryStatus);
    final formattedDate = _dateFormat.format(report.reportDate);
    final typeLabelColor = report.reportType == 'DAILY_COMBINED'
        ? Colors.purple.shade700
        : Colors.blue.shade700;

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
                        color: Colors.grey.shade600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Text(
                          _currencyFormat.format(report.totalRevenue),
                          style: theme.textTheme.bodyMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                            color: Colors.green.shade700,
                          ),
                        ),
                        Text(
                          '  •  ${report.totalSales} vente(s)',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: Colors.grey.shade600,
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

  Color _statusColor(String status) {
    switch (status) {
      case 'SENT':
        return Colors.green.shade600;
      case 'FAILED':
        return Colors.orange.shade600;
      case 'IN_APP_ONLY':
        return Colors.grey.shade500;
      default:
        return Colors.blue.shade400;
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
      case 'DAILY_COMBINED':
        return 'Multi-boutiques';
      default:
        return 'Quotidien';
    }
  }
}

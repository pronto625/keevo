import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/dashboard_snapshot.dart';

/// ShopStatusCard — store overview card with CA, status badge, staff count,
/// and left accent border.
///
/// Story 7.1 — AC4.
class ShopStatusCard extends StatelessWidget {
  final StoreOverview store;
  final VoidCallback? onTap;

  const ShopStatusCard({
    super.key,
    required this.store,
    this.onTap,
  });

  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    final status = computeStoreStatus(store.todayCA, store.yesterdayCA);
    final accentColor = _accentColorFor(status);

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: IntrinsicHeight(
          child: Row(
            children: [
              Container(width: 4, color: accentColor),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              store.storeName,
                              style: Theme.of(context)
                                  .textTheme
                                  .titleSmall
                                  ?.copyWith(fontWeight: FontWeight.w600),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          _StatusBadge(status: status),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Icon(
                            Icons.analytics_outlined,
                            size: 16,
                            color: Theme.of(context)
                                .colorScheme
                                .onSurface
                                .withValues(alpha: 0.5),
                          ),
                          const SizedBox(width: 4),
                          Text(
                            _currencyFormat.format(store.todayCA),
                            style:
                                Theme.of(context).textTheme.bodyMedium?.copyWith(
                                      fontWeight: FontWeight.w500,
                                    ),
                          ),
                          const Spacer(),
                          Icon(
                            Icons.people_outline,
                            size: 16,
                            color: Theme.of(context)
                                .colorScheme
                                .onSurface
                                .withValues(alpha: 0.5),
                          ),
                          const SizedBox(width: 4),
                          Text(
                            '${store.employeeCount}',
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Color _accentColorFor(StoreStatusLevel status) {
    switch (status) {
      case StoreStatusLevel.stable:
        return AppTheme.success;
      case StoreStatusLevel.attention:
        return AppTheme.warning;
      case StoreStatusLevel.enBaisse:
        return AppTheme.errorColor;
    }
  }
}

class _StatusBadge extends StatelessWidget {
  final StoreStatusLevel status;
  const _StatusBadge({required this.status});

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      StoreStatusLevel.stable => ('STABLE', AppTheme.success),
      StoreStatusLevel.attention => ('ATTENTION', AppTheme.warning),
      StoreStatusLevel.enBaisse => ('EN BAISSE', AppTheme.errorColor),
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}

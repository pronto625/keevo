import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'package:keevo_core/theme/app_theme.dart';
import '../../domain/model/seller_leaderboard_model.dart';

/// SellerLeaderboardRow — one ranked seller of the « Équipe » tab (FR44,
/// v1s-14-2): rank badge (medal tokens for the top 3), name (+ « Propriétaire »
/// tag), sales count, revenue and average basket in XAF.
class SellerLeaderboardRow extends StatelessWidget {
  final SellerLeaderboardEntry entry;

  const SellerLeaderboardRow({super.key, required this.entry});

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  static Color? _medal(int rank) => switch (rank) {
        1 => AppTheme.medalGold,
        2 => AppTheme.medalSilver,
        3 => AppTheme.medalBronze,
        _ => null,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    // Idle sellers (0 sale) never get a medal, even when ranked 1–3.
    final medal = entry.salesCount > 0 ? _medal(entry.rank) : null;
    final ventes = entry.salesCount <= 1 ? 'vente' : 'ventes';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          // ── Rank badge (soft, diluted — no emoji) ────────────────────────
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: medal?.withValues(alpha: 0.16) ??
                  AppTheme.softTint(theme.brightness),
              shape: BoxShape.circle,
              border: medal == null
                  ? null
                  : Border.all(color: medal, width: 1.5),
            ),
            child: Text(
              '#${entry.rank}',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 13,
                color: cs.onSurface,
              ),
            ),
          ),
          const SizedBox(width: 12),

          // ── Seller ───────────────────────────────────────────────────────
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        entry.sellerName,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: cs.onSurface,
                        ),
                      ),
                    ),
                    if (entry.owner &&
                        entry.sellerName != 'Propriétaire') ...[
                      const SizedBox(width: 6),
                      _OwnerTag(),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  '${entry.salesCount} $ventes · '
                  'Panier moyen ${_currencyFmt.format(entry.averageBasket)}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: cs.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),

          // ── Revenue ──────────────────────────────────────────────────────
          Text(
            _currencyFmt.format(entry.totalRevenue),
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
        ],
      ),
    );
  }
}

class _OwnerTag extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        'Propriétaire',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: theme.colorScheme.primary,
        ),
      ),
    );
  }
}

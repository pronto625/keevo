import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/model/dashboard_snapshot.dart';

/// TopProductsSection — top 5 weekly products with rank, name, units, revenue.
///
/// Story 7.1 — AC7.
class TopProductsSection extends StatelessWidget {
  final String title;
  final List<TopProduct> products;
  final VoidCallback? onViewAll;

  const TopProductsSection({
    super.key,
    this.title = 'Top Produits — toutes boutiques (7j)',
    required this.products,
    this.onViewAll,
  });

  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    if (products.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Text(
                title,
                style: Theme.of(context)
                    .textTheme
                    .titleSmall
                    ?.copyWith(fontWeight: FontWeight.w600),
              ),
              const Spacer(),
              if (onViewAll != null)
                TextButton(
                  onPressed: onViewAll,
                  child: const Text('Voir Tout'),
                ),
            ],
          ),
        ),
        const SizedBox(height: 4),
        ...products.asMap().entries.map(
              (e) => _TopProductRow(rank: e.key + 1, product: e.value),
            ),
      ],
    );
  }
}

class _TopProductRow extends StatelessWidget {
  final int rank;
  final TopProduct product;
  const _TopProductRow({required this.rank, required this.product});

  @override
  Widget build(BuildContext context) {
    final total = product.revenue;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 24,
            child: Text(
              '#$rank',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: rank <= 3
                        ? const Color(0xFF3B5BDB)
                        : Theme.of(context)
                            .colorScheme
                            .onSurface
                            .withValues(alpha: 0.5),
                  ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              product.name,
              style: Theme.of(context).textTheme.bodyMedium,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '${product.unitsSold} u.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context)
                      .colorScheme
                      .onSurface
                      .withValues(alpha: 0.5),
                ),
          ),
          const SizedBox(width: 12),
          Text(
            TopProductsSection._currencyFormat.format(total),
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
          ),
        ],
      ),
    );
  }
}

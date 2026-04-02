import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../provider/profitability_providers.dart';
import '../widget/margin_sparkline.dart';
import '../widget/period_filter_chips.dart';

export '../widget/margin_sparkline.dart' show MarginSparkline;

/// ProductProfitabilityDetailPage — AC4 of Story 7.4.
///
/// Route: /reports/rentabilite/:productId
/// Shows full product profitability breakdown including price range + sparkline.
class ProductProfitabilityDetailPage extends ConsumerWidget {
  final String productId;

  const ProductProfitabilityDetailPage({super.key, required this.productId});

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final period = ref.watch(selectedProfitabilityPeriodProvider);
    final params = ProfitabilityParams(from: period.from, to: period.to);
    final args = (productId: productId, params: params);
    final dataAsync = ref.watch(productProfitabilityDetailProvider(args));

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      body: CustomScrollView(
        slivers: [
          SliverAppBar(
            pinned: true,
            backgroundColor: AppTheme.primary,
            title: dataAsync.when(
              data: (d) => Text(d.productName,
                  style: const TextStyle(
                      color: Colors.white, fontWeight: FontWeight.bold)),
              loading: () => const Text('Chargement…',
                  style: TextStyle(color: Colors.white)),
              error: (_, __) =>
                  const Text('Détail', style: TextStyle(color: Colors.white)),
            ),
            iconTheme: const IconThemeData(color: Colors.white),
          ),

          // Period filter
          SliverToBoxAdapter(
            child: Container(
              color: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: PeriodFilterChips(
                selectedFrom: period.from,
                selectedTo: period.to,
                onPeriodChanged: (from, to) => ref
                    .read(selectedProfitabilityPeriodProvider.notifier)
                    .state = (from: from, to: to),
              ),
            ),
          ),

          dataAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: Center(child: Text('Erreur: $e')),
            ),
            data: (detail) => SliverList(
              delegate: SliverChildListDelegate([
                const SizedBox(height: 12),

                // ── Header card: current prices ───────────────────────────
                _SectionCard(
                  title: 'Prix actuels (catalogue)',
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceAround,
                    children: [
                      _PriceStat(
                          label: 'Catalogue',
                          value: _currencyFmt
                              .format(detail.currentCataloguePrice)),
                      _PriceStat(
                          label: 'Achat',
                          value: _currencyFmt
                              .format(detail.currentBuyPrice)),
                      _PriceStat(
                          label: 'Transport',
                          value: _currencyFmt
                              .format(detail.currentTransportCost)),
                    ],
                  ),
                ),

                // ── Metrics card ──────────────────────────────────────────
                _SectionCard(
                  title: 'Performance sur la période',
                  child: Wrap(
                    spacing: 16,
                    runSpacing: 8,
                    children: [
                      _MetricItem(
                          label: 'Unités vendues',
                          value: '${detail.unitsSold}'),
                      _MetricItem(
                          label: 'CA',
                          value: _currencyFmt.format(detail.totalRevenue)),
                      _MetricItem(
                          label: 'Coût total',
                          value: _currencyFmt.format(detail.totalCost)),
                      _MetricItem(
                          label: 'Marge brute',
                          value: _currencyFmt.format(detail.grossMarginXaf),
                          valueColor: detail.marginColor),
                      _MetricItem(
                          label: 'Marge %',
                          value:
                              '${detail.marginPercent.toStringAsFixed(1)}%',
                          valueColor: detail.marginColor),
                    ],
                  ),
                ),

                // ── Price range section ───────────────────────────────────
                _SectionCard(
                  title: 'Prix appliqués (sale_items)',
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceAround,
                    children: [
                      _PriceStat(
                          label: 'Min',
                          value: _currencyFmt
                              .format(detail.minAppliedPrice)),
                      _PriceStat(
                          label: 'Max',
                          value: _currencyFmt
                              .format(detail.maxAppliedPrice)),
                      _PriceStat(
                          label: 'Moyen',
                          value: _currencyFmt
                              .format(detail.avgAppliedPrice.round())),
                    ],
                  ),
                ),

                // ── Sparkline – last 7 days ───────────────────────────────
                _SectionCard(
                  title: 'Marge quotidienne (7 jours)',
                  child: MarginSparkline(entries: detail.dailyMarginLast7),
                ),

                // ── Top store ─────────────────────────────────────────────
                if (detail.topStoreName != null)
                  _SectionCard(
                    title: 'Meilleure boutique',
                    child: Row(
                      children: [
                        const Icon(Icons.storefront,
                            size: 18, color: AppTheme.primary),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '${detail.topStoreName}'
                            '  —  ${detail.topStoreUnitsSold} unités',
                            style: const TextStyle(fontWeight: FontWeight.w500),
                          ),
                        ),
                      ],
                    ),
                  ),

                const SizedBox(height: 24),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Sub-widgets ──────────────────────────────────────────────────────────────

class _SectionCard extends StatelessWidget {
  final String title;
  final Widget child;

  const _SectionCard({required this.title, required this.child});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      elevation: 0,
      shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: Colors.grey.shade200)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: Colors.black54)),
            const SizedBox(height: 10),
            child,
          ],
        ),
      ),
    );
  }
}

class _PriceStat extends StatelessWidget {
  final String label;
  final String value;

  const _PriceStat({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Column(
        children: [
          Text(label,
              style: const TextStyle(fontSize: 11, color: Colors.black45)),
          const SizedBox(height: 2),
          Text(value,
              style: const TextStyle(
                  fontWeight: FontWeight.bold, fontSize: 13)),
        ],
      );
}

class _MetricItem extends StatelessWidget {
  final String label;
  final String value;
  final Color? valueColor;

  const _MetricItem(
      {required this.label, required this.value, this.valueColor});

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: const TextStyle(fontSize: 11, color: Colors.black45)),
          Text(value,
              style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                  color: valueColor)),
        ],
      );
}

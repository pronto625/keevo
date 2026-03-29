import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/dashboard_snapshot.dart';
import '../provider/dashboard_providers.dart';
import '../widget/sales_evolution_chart.dart';
import '../widget/top_products_section.dart';

/// StoreDashboardPage — full-screen per-store dashboard.
///
/// Story 7.1 — AC4 (sub-dashboard).
class StoreDashboardPage extends ConsumerWidget {
  final String storeId;
  const StoreDashboardPage({super.key, required this.storeId});

  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storeOverviewsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Détails Boutique'),
      ),
      body: storesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => const Center(
          child: Text('Impossible de charger les données'),
        ),
        data: (stores) {
          final store = stores.where((s) => s.storeId == storeId).firstOrNull;
          if (store == null) {
            return const Center(
              child: Text('Boutique introuvable'),
            );
          }
          return _StoreDetail(store: store, storeId: storeId);
        },
      ),
    );
  }
}

class _StoreDetail extends ConsumerWidget {
  final StoreOverview store;
  final String storeId;
  const _StoreDetail({required this.store, required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = computeStoreStatus(store.todayCA, store.yesterdayCA);
    final statusLabel = switch (status) {
      StoreStatusLevel.stable => 'STABLE',
      StoreStatusLevel.attention => 'ATTENTION',
      StoreStatusLevel.enBaisse => 'EN BAISSE',
    };
    final statusColor = switch (status) {
      StoreStatusLevel.stable => const Color(0xFF51CF66),
      StoreStatusLevel.attention => const Color(0xFFFCC419),
      StoreStatusLevel.enBaisse => const Color(0xFFFA5252),
    };

    final chartPeriod = ref.watch(storeChartPeriodProvider(storeId));
    final chartAsync = ref.watch(storeChartDataProvider(storeId));
    final topAsync = ref.watch(storeTopProductsProvider(storeId));
    final worstAsync = ref.watch(storeWorstProductsProvider(storeId));
    final lowStockAsync = ref.watch(storeLowStockProductsProvider(storeId));

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Store header
        Text(
          store.storeName,
          style: Theme.of(context)
              .textTheme
              .headlineSmall
              ?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: statusColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              statusLabel,
              style: TextStyle(
                color: statusColor,
                fontWeight: FontWeight.w700,
                fontSize: 12,
              ),
            ),
          ),
        ),
        const SizedBox(height: 24),

        // Metrics row
        _MetricRow(
          label: "CA Aujourd'hui",
          value: StoreDashboardPage._currencyFormat.format(store.todayCA),
          icon: Icons.trending_up,
        ),
        _MetricRow(
          label: 'CA Hier',
          value: StoreDashboardPage._currencyFormat.format(store.yesterdayCA),
          icon: Icons.history,
        ),
        _MetricRow(
          label: 'Employés',
          value: '${store.employeeCount}',
          icon: Icons.people_outline,
        ),
        const SizedBox(height: 24),

        // Sales evolution chart
        chartAsync.when(
          data: (chartData) => chartData.isEmpty
              ? const SizedBox.shrink()
              : SalesEvolutionChart(
                  data: chartData,
                  period: chartPeriod,
                  onPeriodChanged: (p) =>
                      ref.read(storeChartPeriodProvider(storeId).notifier).state = p,
                ),
          loading: () => const SizedBox(
            height: 120,
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (_, __) => const SizedBox.shrink(),
        ),
        const SizedBox(height: 24),

        // Top products
        topAsync.when(
          data: (products) => products.isEmpty
              ? const SizedBox.shrink()
              : TopProductsSection(products: products),
          loading: () => const SizedBox.shrink(),
          error: (_, __) => const SizedBox.shrink(),
        ),
        const SizedBox(height: 16),

        // Worst products
        worstAsync.when(
          data: (products) => products.isEmpty
              ? const SizedBox.shrink()
              : TopProductsSection(
                  title: 'Produits les moins vendus (7j)',
                  products: products,
                ),
          loading: () => const SizedBox.shrink(),
          error: (_, __) => const SizedBox.shrink(),
        ),
        const SizedBox(height: 16),

        // Low stock products
        lowStockAsync.when(
          data: (items) =>
              items.isEmpty ? const SizedBox.shrink() : _LowStockSection(items: items),
          loading: () => const SizedBox.shrink(),
          error: (_, __) => const SizedBox.shrink(),
        ),

        const SizedBox(height: 24),
      ],
    );
  }
}

/// Low stock products section for store detail.
class _LowStockSection extends StatelessWidget {
  final List<LowStockProduct> items;
  const _LowStockSection({required this.items});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 0),
          child: Row(
            children: [
              const Icon(Icons.warning_amber_rounded,
                  size: 18, color: Color(0xFFFA5252)),
              const SizedBox(width: 6),
              Text(
                'Stock Bas (${items.length})',
                style: Theme.of(context)
                    .textTheme
                    .titleSmall
                    ?.copyWith(fontWeight: FontWeight.w600),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        ...items.map((item) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      item.name,
                      style: Theme.of(context).textTheme.bodyMedium,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: const Color(0xFFFA5252).withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      '${item.quantity} / ${item.threshold}',
                      style: const TextStyle(
                        color: Color(0xFFFA5252),
                        fontWeight: FontWeight.w600,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ],
              ),
            )),
      ],
    );
  }
}

class _MetricRow extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;

  const _MetricRow({
    required this.label,
    required this.value,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Icon(
            icon,
            size: 20,
            color: Theme.of(context)
                .colorScheme
                .onSurface
                .withValues(alpha: 0.5),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          Text(
            value,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
          ),
        ],
      ),
    );
  }
}

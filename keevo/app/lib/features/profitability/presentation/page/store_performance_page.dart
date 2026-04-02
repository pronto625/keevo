import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/theme/app_theme.dart';
import '../../domain/model/product_profitability_model.dart';
import '../provider/profitability_providers.dart';
import '../widget/period_filter_chips.dart';
import '../widget/store_performance_row.dart';

/// StorePerformancePage — AC5, AC6 of Story 7.4.
///
/// Displays ranked store comparative performance with period + metric filters.
class StorePerformancePage extends ConsumerWidget {
  const StorePerformancePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // AC10: Auto-refresh when device comes back online.
    ref.listen<AsyncValue<SyncStatus>>(syncStatusProvider, (prev, next) {
      if (next.valueOrNull == SyncStatus.online &&
          prev?.valueOrNull != SyncStatus.online) {
        ref.invalidate(storePerformanceProvider);
      }
    });

    final period = ref.watch(selectedProfitabilityPeriodProvider);
    final metric = ref.watch(selectedMetricProvider);
    final syncStatus = ref.watch(syncStatusProvider);
    final isOffline = switch (syncStatus.valueOrNull) {
      SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
      _ => false,
    };

    final params = StorePerformanceParams(
      from: period.from,
      to: period.to,
      metric: metric,
    );

    final dataAsync = ref.watch(storePerformanceProvider(params));

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      body: CustomScrollView(
        slivers: [
          // ── Header ────────────────────────────────────────────────────────
          SliverAppBar(
            pinned: true,
            backgroundColor: AppTheme.primary,
            title: const Text(
              'Performances Boutiques',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
            ),
            iconTheme: const IconThemeData(color: Colors.white),
          ),

          // ── Filters ───────────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: ColoredBox(
              color: Colors.white,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PeriodFilterChips(
                      selectedFrom: period.from,
                      selectedTo: period.to,
                      onPeriodChanged: (from, to) => ref
                          .read(selectedProfitabilityPeriodProvider.notifier)
                          .state = (from: from, to: to),
                    ),
                    const SizedBox(height: 4),
                    _MetricToggle(
                      selected: metric,
                      onChanged: (m) =>
                          ref.read(selectedMetricProvider.notifier).state = m,
                    ),
                  ],
                ),
              ),
            ),
          ),

          // ── Offline banner (AC10) ─────────────────────────────────────────
          if (isOffline)
            const SliverToBoxAdapter(child: _OfflineBanner()),

          // ── Data ──────────────────────────────────────────────────────────
          dataAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.cloud_off_outlined,
                        size: 48, color: Colors.black26),
                    const SizedBox(height: 12),
                    const Text(
                      'Impossible de charger les données',
                      style: TextStyle(color: Colors.black54),
                    ),
                    const SizedBox(height: 8),
                    FilledButton.tonal(
                      onPressed: () =>
                          ref.invalidate(storePerformanceProvider),
                      child: const Text('Réessayer'),
                    ),
                  ],
                ),
              ),
            ),
            data: (entries) {
              if (entries.isEmpty) {
                return const SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.storefront_outlined,
                            size: 48, color: Colors.black26),
                        SizedBox(height: 12),
                        Text(
                          'Aucune boutique active',
                          style: TextStyle(color: Colors.black45),
                        ),
                      ],
                    ),
                  ),
                );
              }

              return SliverList.separated(
                itemCount: entries.length,
                itemBuilder: (_, i) => StorePerformanceRow(entry: entries[i]),
                separatorBuilder: (_, __) => const Divider(height: 1),
              );
            },
          ),
        ],
      ),
    );
  }
}

// ── Metric toggle ─────────────────────────────────────────────────────────────

class _MetricToggle extends StatelessWidget {
  final RankingMetric selected;
  final ValueChanged<RankingMetric> onChanged;

  const _MetricToggle({required this.selected, required this.onChanged});

  static const _options = [
    (RankingMetric.ca, 'CA'),
    (RankingMetric.salesCount, 'Ventes'),
    (RankingMetric.avgBasket, 'Panier Moyen'),
  ];

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: _options.map((pair) {
          final (option, label) = pair;
          final isSelected = selected == option;
          final theme = Theme.of(context);
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(label),
              selected: isSelected,
              onSelected: (_) => onChanged(option),
              selectedColor: theme.colorScheme.primary,
              labelStyle: TextStyle(
                color: isSelected ? Colors.white : null,
                fontWeight:
                    isSelected ? FontWeight.w600 : FontWeight.normal,
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

// ── Offline banner ───────────────────────────────────────────────────────────

class _OfflineBanner extends StatelessWidget {
  const _OfflineBanner();

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: Colors.amber.shade50,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.cloud_off_outlined,
                size: 16, color: Colors.orange.shade700),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Mode hors ligne — données du dernier sync affiché',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.orange.shade900,
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── (no SliverPersistentHeaderDelegate needed — filters use SliverToBoxAdapter)

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import '../../domain/model/product_profitability_model.dart';
import '../provider/profitability_providers.dart';
import '../widget/offline_data_banner.dart';
import '../widget/period_filter_chips.dart';
import '../widget/ranking_metric_toggle.dart';
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

    final selection = ref.watch(selectedProfitabilityPeriodProvider);
    final period = selection.range();
    final metric = ref.watch(selectedMetricProvider);
    final syncStatus = ref.watch(syncStatusProvider);
    final isOffline = switch (syncStatus.valueOrNull) {
      SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
      _ => false,
    };

    // AC5: the ranking metric is applied locally — the fetch key never
    // carries it, so the toggle re-ranks without a new Drift/API query.
    final params = StorePerformanceParams(
      from: period.from,
      to: period.to,
    );

    final dataAsync = ref.watch(storePerformanceProvider(params));

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerLowest,
      body: CustomScrollView(
        slivers: [
          // ── Header ────────────────────────────────────────────────────────
          SliverAppBar(
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: Theme.of(context).colorScheme.surface,
            title: Text(
              'Performances Boutiques',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurface,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.2,
              ),
            ),
            iconTheme:
                IconThemeData(color: Theme.of(context).colorScheme.onSurface),
          ),

          // ── Filters ───────────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: ColoredBox(
              color: Theme.of(context).colorScheme.surface,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PeriodFilterChips(
                      selected: selection,
                      onChanged: (s) => ref
                          .read(selectedProfitabilityPeriodProvider.notifier)
                          .state = s,
                    ),
                    const SizedBox(height: 4),
                    RankingMetricToggle(
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
            const SliverToBoxAdapter(child: OfflineDataBanner()),

          // ── Data ──────────────────────────────────────────────────────────
          dataAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: AppErrorWidget(
                error: e,
                title: 'Impossible de charger les données',
                onRetry: () => ref.invalidate(storePerformanceProvider),
              ),
            ),
            data: (fetched) {
              final entries = rankStoresBy(fetched, metric);
              if (entries.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SoftIconBadge(icon: Icons.storefront_outlined, size: 72),
                        const SizedBox(height: 12),
                        Text(
                          'Aucune boutique active',
                          style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
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

// ── (no SliverPersistentHeaderDelegate needed — filters use SliverToBoxAdapter)

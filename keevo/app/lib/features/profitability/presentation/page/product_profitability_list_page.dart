import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/sync/sync_status.dart';
import 'package:keevo_core/sync/sync_status_provider.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import '../../domain/model/product_profitability_model.dart';
import '../provider/profitability_providers.dart';
import '../widget/offline_data_banner.dart';
import '../widget/period_filter_chips.dart';
import '../widget/profitability_row.dart';
import '../widget/sort_chips.dart';

/// ProductProfitabilityListPage — AC1, AC2, AC3 of Story 7.4.
///
/// Displays profitability per product for a date range, with sort chips.
/// Route guard for EMPLOYEE is handled at router level (app_router.dart),
/// not in this widget.
class ProductProfitabilityListPage extends ConsumerWidget {
  final String? storeId;
  const ProductProfitabilityListPage({super.key, this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // AC10: Auto-refresh when device comes back online.
    ref.listen<AsyncValue<SyncStatus>>(syncStatusProvider, (prev, next) {
      if (next.value == SyncStatus.online &&
          prev?.value != SyncStatus.online) {
        ref.invalidate(productProfitabilityProvider);
      }
    });

    final selection = ref.watch(selectedProfitabilityPeriodProvider);
    final period = selection.range();
    final sort = ref.watch(selectedSortProvider);
    final syncStatus = ref.watch(syncStatusProvider);
    final isOffline = switch (syncStatus.value) {
      SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
      _ => false,
    };

    // AC2: the sort is applied locally — the fetch key never carries it, so
    // tapping a sort chip re-sorts without a new Drift/API query.
    final params = ProfitabilityParams(
      from: period.from,
      to: period.to,
      storeId: storeId,
    );

    final dataAsync = ref.watch(productProfitabilityProvider(params));

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
              'Rentabilité Produits',
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
                          .set(s),
                    ),
                    const SizedBox(height: 4),
                    SortChips(
                      selected: sort,
                      onChanged: (option) =>
                          ref.read(selectedSortProvider.notifier).set(option),
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
                onRetry: () => ref.invalidate(productProfitabilityProvider),
              ),
            ),
            data: (fetched) {
              final entries = applyProductSort(fetched, sort);
              if (entries.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SoftIconBadge(icon: Icons.bar_chart_outlined, size: 72),
                        const SizedBox(height: 12),
                        Text(
                          'Aucune vente sur cette période',
                          style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                );
              }

              return SliverList.separated(
                itemCount: entries.length,
                itemBuilder: (ctx, i) {
                  final entry = entries[i];
                  return ProductProfitabilityRow(
                    entry: entry,
                    onTap: () {
                      ref.read(profitabilityStoreFilterProvider.notifier).set(storeId);
                      context.push('/reports/rentabilite/${entry.productId}',
                          extra: entry);
                    },
                  );
                },
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
